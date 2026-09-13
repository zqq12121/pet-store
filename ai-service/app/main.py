"""买家 SSE 与管理员知识接口；交易写入不在此服务开放。"""
import asyncio
import json
from contextlib import asynccontextmanager
from typing import Annotated, Literal
from uuid import UUID

import httpx
from fastapi import Depends, FastAPI, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, ConfigDict, Field, model_validator

from .agent import PetAgent
from .business import ApiError, BusinessClient
from .config import Settings
from .knowledge import Knowledge
from .knowledge_store import KnowledgeStore
from .knowledge_api import register_knowledge_api
from .store import DISCLAIMER, Store, now, uid

Id = Annotated[str, Field(pattern=r"^[A-Za-z0-9_-]{1,100}$")]


class Input(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class SessionInput(Input):
    productId: Id | None = None


class MessageInput(Input):
    clientMessageId: UUID
    content: Annotated[str, Field(min_length=1, max_length=2000)]
    orderId: Id | None = None


class FeedbackInput(Input):
    value: Literal["up", "down", "none"]
    reason: Annotated[str, Field(max_length=500)] | None = None

    @model_validator(mode="after")
    def reason_only_for_down(self):
        if self.reason is not None and self.value != "down":
            raise ValueError("只有未解决反馈可填写原因")
        return self


def envelope(data):
    return dict(code="OK", message="success", data=data, requestId=uid("req"), serverTime=now())


def page(items, number, size):
    return envelope(dict(items=items[(number-1)*size:number*size], page=number, pageSize=size, total=len(items)))


def sse(event, data):
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


def create_app(settings=None, business=None, agent=None):
    settings = settings or Settings()
    store = Store(settings.db_path)
    http = httpx.AsyncClient(timeout=8, follow_redirects=False)
    business = business or BusinessClient(settings, http)
    knowledge = Knowledge(settings, KnowledgeStore(store, settings))
    agent = agent or PetAgent(settings, business, knowledge)
    tasks = set()

    @asynccontextmanager
    async def lifespan(app):
        knowledge.schedule()
        yield
        # 请求断流不取消后台生成；进程退出时才中断并把消息标为失败。
        for task in list(tasks):
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        await knowledge.stop()
        await http.aclose()

    app = FastAPI(title="WarmPaw Pet Agent", lifespan=lifespan, docs_url=None, redoc_url=None)
    app.state.store = store
    app.state.knowledge = knowledge
    register_knowledge_api(app, business, knowledge, envelope, page)

    @app.exception_handler(ApiError)
    async def api_error(request, exc):
        return JSONResponse(status_code=exc.status, content=dict(code=exc.code, message=exc.message, data=None),
                            headers={"Retry-After": "60"} if exc.status == 429 else {})

    @app.exception_handler(RequestValidationError)
    async def invalid(request, exc):
        return JSONResponse(status_code=400, content=dict(code="VALIDATION_ERROR", message="请求字段不正确", data=None))

    @app.middleware("http")
    async def no_store(request, call_next):
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store, no-transform"
        return response

    async def identity(request: Request):
        return await business.identify(request.headers.get("Authorization"), request.headers.get("X-Guest-Token"))

    @app.get("/health")
    async def health():
        return {"status": "UP", "modelConfigured": bool(settings.api_key)}

    @app.post("/api/v1/ai/sessions", status_code=201)
    async def create(body: SessionInput, actor=Depends(identity)):
        if body.productId:
            await business.pet(body.productId)
        return envelope(store.create(actor.owner, body.productId))

    @app.get("/api/v1/ai/sessions")
    async def sessions(page_number: int = Query(1, alias="page", ge=1), pageSize: int = Query(20, ge=1, le=100),
                       status: Literal["active", "closed"] | None = None, actor=Depends(identity)):
        return page(store.sessions(actor.owner, status), page_number, pageSize)

    @app.get("/api/v1/ai/sessions/{sid}")
    async def session(sid: str, actor=Depends(identity)):
        return envelope(store.session(sid, actor.owner))

    @app.get("/api/v1/ai/sessions/{sid}/messages")
    async def messages(sid: str, page_number: int = Query(1, alias="page", ge=1),
                       pageSize: int = Query(20, ge=1, le=100), afterSequence: int = Query(0, ge=0), actor=Depends(identity)):
        store.session(sid, actor.owner)
        return page([m for m in store.messages(sid) if m["sequence"] > afterSequence], page_number, pageSize)

    @app.get("/api/v1/ai/sessions/{sid}/messages/{mid}")
    async def message(sid: str, mid: str, actor=Depends(identity)):
        store.session(sid, actor.owner)
        return envelope(store.message(sid, mid))

    async def generate(actor, session, body, message):
        evidence = {}
        try:
            # 持久化完整历史，但只向模型发送有限最近上下文，不携带凭证或卡片原始记录。
            history = [{"role": m["role"], "content": m["content"][:2000]}
                       for m in store.messages(session["id"]) if m["sequence"] < message["sequence"]-1
                       and m["status"] == "completed"][-10:]
            async with asyncio.timeout(settings.generation_timeout):
                async for text in agent.stream(actor, session, history, body, evidence):
                    if len(message["content"]) + len(text) > 12000:
                        raise ApiError(503, "AI_UPSTREAM_UNAVAILABLE", "回复超出长度限制，请缩小问题范围")
                    message["content"] += text
                    store.save(message)
            message.update({k: evidence.get(k, []) for k in ("sources", "productCards", "orderCards", "actions")})
            message.update(status="completed", completedAt=now(), intent=evidence.get("intent", "unknown"),
                           outcome="unresolved" if evidence.get("unresolved") else "answered",
                           disclaimer=DISCLAIMER + "。健康问题建议及时就医。")
        except TimeoutError:
            store.fail(message, "AI_GENERATION_TIMEOUT", "回复超时，请联系店主或稍后重新提问")
        except asyncio.CancelledError:
            store.fail(message, "AI_UPSTREAM_UNAVAILABLE", "回复已中断，请重新提问")
        except Exception:
            store.fail(message, "AI_UPSTREAM_UNAVAILABLE", "智能助手暂时无法回答，请联系店主")
        finally:
            store.save(message)

    async def events(sid, message, replayed):
        yield sse("meta", dict(sessionId=sid, assistantMessageId=message["id"],
                                userMessageId=message.get("userMessageId"),
                                clientMessageId=message["clientMessageId"], replayed=replayed, requestId=uid("req")))
        sent, ticks = 0, 0
        while True:
            saved = store.message(sid, message["id"])
            if len(saved["content"]) > sent:
                yield sse("delta", {"messageId": saved["id"], "text": saved["content"][sent:]})
                sent = len(saved["content"])
            if saved["status"] != "streaming":
                yield sse("sources", {"messageId": saved["id"], "items": saved["sources"]})
                yield sse("cards", {"messageId": saved["id"], "productCards": saved["productCards"], "orderCards": saved["orderCards"]})
                yield sse("action", {"messageId": saved["id"], "items": saved["actions"]})
                if saved["status"] == "completed":
                    yield sse("done", {**{k: saved[k] for k in ("status", "intent", "outcome", "suggestedQuestions", "disclaimer")},
                                       "messageId": saved["id"], "serverTime": now()})
                else:
                    yield sse("error", dict(messageId=saved["id"], status="failed", code=saved["errorCode"],
                                             message=saved.get("errorMessage", "回复失败"), retryable=False,
                                             actions=saved["actions"], serverTime=now()))
                break
            await asyncio.sleep(0.1)
            ticks += 1
            if ticks % 100 == 0:
                yield ": ping\n\n"

    @app.post("/api/v1/ai/sessions/{sid}/messages")
    async def send(sid: str, body: MessageInput, actor=Depends(identity)):
        session = store.session(sid, actor.owner)
        request = body.model_dump(mode="json", exclude_none=True)
        saved = store.existing(sid, request)
        if saved:
            return StreamingResponse(events(sid, saved, True), media_type="text/event-stream", headers={"X-Accel-Buffering": "no"})
        if body.orderId:
            await business.orders(actor, body.orderId)
            # 归属查询期间其他同 ID 请求可能已完成，再检查一次以保持幂等。
            saved = store.existing(sid, request)
            if saved:
                return StreamingResponse(events(sid, saved, True), media_type="text/event-stream", headers={"X-Accel-Buffering": "no"})
        agent.ready()
        if len(tasks) >= 8:
            raise ApiError(429, "RATE_LIMITED", "当前咨询较多，请稍后再试")
        # 此处开始至创建任务之间无 await；单 worker 下不会重复创建同一消息。
        _, answer = store.begin(sid, actor.owner, request)
        task = asyncio.create_task(generate(actor, session, request, answer))
        tasks.add(task)
        task.add_done_callback(tasks.discard)
        return StreamingResponse(events(sid, answer, False), media_type="text/event-stream", headers={"X-Accel-Buffering": "no"})

    @app.put("/api/v1/ai/sessions/{sid}/messages/{mid}/feedback")
    async def feedback(sid: str, mid: str, body: FeedbackInput, actor=Depends(identity)):
        if store.session(sid, actor.owner)["status"] == "closed":
            raise ApiError(409, "AI_SESSION_CLOSED", "会话已结束")
        return envelope(store.feedback(sid, mid, body.value, body.reason))

    @app.post("/api/v1/ai/sessions/{sid}/close")
    async def close(sid: str, actor=Depends(identity)):
        return envelope(store.close(sid, actor.owner))

    return app
