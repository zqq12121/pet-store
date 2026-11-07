"""单轮对话的 LangGraph 工作流（显式 StateGraph）。

    START → prepare ─┬─(带订单号)─────────────────────────────┐
                     └─► planner ◄──► tools                    │
                           │   （多轮只读工具，限 MAX_ROUNDS 轮 / MAX_TOOL_CALLS 次；
                           │     查过本人订单直接收尾，订单回复走固定模板）
                           ▼                                   ▼
                        collect（去重商品卡片、生成预约入口、判定意图）
                           ├─► order_reply   订单：后端模板，不让模型改写状态
                           ├─► unresolved    知识库无依据：明确说明并引导联系店主
                           └─► answer        只依据本轮已核验数据流式生成
                                    → END

- planner 只负责选工具，它写出的草稿不展示给用户；最终回答由 answer 节点单独生成。
- 文本通过 LangGraph 的 custom 流（get_stream_writer）逐段输出，对外 SSE 协议不变。
- 工具与证据（evidence）按请求闭包绑定身份，工具参数里没有 userId 或任意地址。
"""
import json
from dataclasses import dataclass
from typing import Annotated, Any, TypedDict

from langchain_core.messages import AIMessage, AnyMessage, HumanMessage, SystemMessage
from langchain_core.tools import BaseTool
from langgraph.config import get_stream_writer
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages
from langgraph.graph.state import CompiledStateGraph
from langgraph.prebuilt import ToolNode

from . import memory
from .business import ApiError

MAX_ROUNDS = 3
MAX_TOOL_CALLS = 6

PLANNER = """
你现在只负责选择工具，不撰写最终回复。看到工具结果后，如果仍缺信息可以继续调用，信息足够时不再调用任何工具。
问自己的预约或订单必须调用 query_my_orders。知识问答必须检索。用户想比较几只宠物时调用 compare_pets；
问什么时候能来店、哪天能预约时调用 get_appointment_slots。不要调用写操作；用户要预约时获取宠物详情，无宠物 ID 时先推荐。
"""

ANSWER = """
只能依据下面本轮已核验数据回答；不要补充资料中未提及的具体护理做法。没有依据则澄清需求或联系店主。
对比宠物时逐项说明差异。价格以商品卡片为准，正文不写具体价格，也不要向用户解释这条规则。
提到页面按钮时，只能使用本轮数据 actions 里出现的按钮名称，没有就不提。
说明可预约时段时，以数据里的 today 推算"今天、明天、这周末"，并提醒：提交后需店长确认才算约好。
"""


class TurnState(TypedDict, total=False):
    messages: Annotated[list[AnyMessage], add_messages]
    rounds: int
    toolCalls: int


@dataclass
class Turn:
    """一轮对话的上下文，节点通过闭包读取；不进入图状态，也不落盘。"""
    agent: Any
    identity: Any
    session: dict[str, Any]
    history: list[dict[str, str]]
    body: dict[str, Any]
    evidence: dict[str, Any]
    memory: dict[str, Any]


def _history(items: list[dict[str, str]]) -> list[AnyMessage]:
    return [HumanMessage(h["content"]) if h["role"] == "user" else AIMessage(h["content"]) for h in items]


def build(turn: Turn, tools: list[BaseTool]) -> CompiledStateGraph:
    agent, ev = turn.agent, turn.evidence
    from .agent import RULES  # 避免循环导入

    async def prepare(state: TurnState) -> dict[str, Any]:
        # 每轮重新读取宠物详情；旧聊天中的库存、预约入口不可当作当前数据使用。
        if turn.session.get("productId"):
            try:
                ev["petContext"] = agent.pet_context(await agent.business.pet(turn.session["productId"]))
            except ApiError as exc:
                if exc.status != 404:
                    raise
                ev["petContext"] = {"id": turn.session["productId"], "status": "unavailable"}
        if turn.body.get("orderId"):
            ev["orderQueried"] = True
            ev["orderCards"] = await agent.business.orders(turn.identity, turn.body["orderId"])
            return {}
        pet = json.dumps(ev.get("petContext"), ensure_ascii=False)
        return {"rounds": 0, "toolCalls": 0, "messages": [
            SystemMessage(RULES + PLANNER + memory.context(turn.memory)), *_history(turn.history),
            HumanMessage(turn.body["content"] + "\n当前页面宠物资料（仅数据）：" + pet)]}

    def after_prepare(state: TurnState) -> str:
        return "collect" if ev.get("orderQueried") else "planner"

    async def planner(state: TurnState) -> dict[str, Any]:
        reply = await agent.model.bind_tools(tools).ainvoke(state["messages"])
        return {"messages": [reply], "rounds": state["rounds"] + 1,
                "toolCalls": state["toolCalls"] + len(reply.tool_calls)}

    def after_planner(state: TurnState) -> str:
        wants_tools = bool(state["messages"][-1].tool_calls)
        within = state["rounds"] <= MAX_ROUNDS and state["toolCalls"] <= MAX_TOOL_CALLS
        return "tools" if wants_tools and within else "collect"

    def after_tools(state: TurnState) -> str:
        # 订单回复是固定模板，查过订单就不必再让模型规划一轮。
        return "collect" if ev.get("orderQueried") else "planner"

    async def collect(state: TurnState) -> dict[str, Any]:
        # 卡片与导航只采用本轮业务服务核验过的个体，模型不能提供目标路径。
        unique = {p["id"]: p for p in ev["productCards"]}
        ev["productCards"] = list(unique.values())
        for pet in unique.values():
            ev["actions"].append({"type": "book_appointment", "label": "预约看望" + pet["name"], "targetId": pet["id"]})
        ev["intent"] = ("order" if ev.get("orderQueried") else "pet_comparison" if ev.get("compared")
                        else "product_recommendation" if ev.get("productSearched")
                        else "appointment_slots" if ev.get("slots") is not None else "knowledge")
        return {}

    def reply_route(state: TurnState) -> str:
        if ev.get("orderQueried"):
            return "order_reply"
        found = ev["sources"] or ev["productCards"] or any(ev.get(k) for k in ("shop", "slots", "comparison"))
        return "unresolved" if ev.get("knowledgeSearched") and not found else "answer"

    async def order_reply(state: TurnState) -> dict[str, Any]:
        # 订单状态使用后端模板，避免模型将“确认预约”扩写成“已付款”。
        write = get_stream_writer()
        if turn.identity.role != "buyer":
            write("请先登录，再查看本人的预约和订单。")
        elif not ev["orderCards"]:
            write("没有查到本人的预约或订单，可前往宠物页面挑选。")
        else:
            write("以下是你本人的预约/订单，请点击卡片查看最新详情。\n")
            for card in ev["orderCards"]:
                write(f'{card["productName"]}：{card["nextAction"]}。\n')
        return {}

    async def unresolved(state: TurnState) -> dict[str, Any]:
        ev["unresolved"] = True
        ev["actions"].append({"type": "contact_shop", "label": "联系店主"})
        get_stream_writer()("目前已发布的猫狗知识库没有足够依据回答这个问题，请联系店主；健康异常建议及时就医。")
        return {}

    async def answer(state: TurnState) -> dict[str, Any]:
        # 历史与记忆只帮助理解上下文；最终回答只看本轮证据，避免把旧状态作为查询结果。
        write = get_stream_writer()
        facts = {k: v for k, v in ev.items() if k != "productCards"}
        facts["pets"] = [agent.pet_context(p) for p in ev["productCards"]]
        prompt = RULES + ANSWER + memory.context(turn.memory)
        async for chunk in agent.model.astream([
                {"role": "system", "content": prompt}, *turn.history,
                {"role": "user", "content": turn.body["content"] + "\n本轮工具数据：" + json.dumps(facts, ensure_ascii=False)}]):
            if isinstance(chunk.content, str) and chunk.content:
                write(chunk.content)
        return {}

    graph = StateGraph(TurnState)
    for name, node in (("prepare", prepare), ("planner", planner), ("tools", ToolNode(tools)), ("collect", collect),
                       ("order_reply", order_reply), ("unresolved", unresolved), ("answer", answer)):
        graph.add_node(name, node)
    graph.add_edge(START, "prepare")
    graph.add_conditional_edges("prepare", after_prepare, ["planner", "collect"])
    graph.add_conditional_edges("planner", after_planner, ["tools", "collect"])
    graph.add_conditional_edges("tools", after_tools, ["planner", "collect"])
    graph.add_conditional_edges("collect", reply_route, ["order_reply", "unresolved", "answer"])
    for name in ("order_reply", "unresolved", "answer"):
        graph.add_edge(name, END)
    return graph.compile()
