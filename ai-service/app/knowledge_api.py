"""管理员知识编辑接口；发布记录审核人，版本冲突返回 409。"""
import ipaddress
from typing import Annotated, Literal
from urllib.parse import parse_qsl, urlsplit

from fastapi import Depends, Query, Request
from pydantic import BaseModel, ConfigDict, Field, StrictInt, field_validator, model_validator
from .knowledge_import import Imports, register_import_api


class KnowledgeInput(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    title: Annotated[str, Field(min_length=1, max_length=120)]
    category: Literal["breed", "feeding", "training", "health", "grooming"]
    petType: Literal["cat", "dog", "both"]
    format: Literal["article", "qa"]
    content: Annotated[str, Field(min_length=1, max_length=30000)] | None = None
    question: Annotated[str, Field(min_length=1, max_length=2000)] | None = None
    answer: Annotated[str, Field(min_length=1, max_length=10000)] | None = None
    sourceName: Annotated[str, Field(min_length=1, max_length=200)]
    sourceUrl: Annotated[str, Field(max_length=2048)] | None = None
    breedNames: Annotated[list[Annotated[str, Field(min_length=1, max_length=50)]], Field(max_length=20)] = []
    status: Literal["draft", "published", "archived"] = "draft"

    @field_validator("breedNames")
    @classmethod
    def unique_breeds(cls, names):
        return list(dict.fromkeys(names))

    @field_validator("sourceUrl")
    @classmethod
    def public_source(cls, value):
        if value is None:
            return None
        url = urlsplit(value)
        if url.scheme != "https" or not url.hostname or url.username or url.password:
            raise ValueError("来源必须为公开 HTTPS 地址")
        if "." not in url.hostname or url.hostname.endswith((".local", ".localhost")):
            raise ValueError("来源不能为本机地址")
        try:
            address = ipaddress.ip_address(url.hostname)
        except ValueError:
            address = None
        if address and not address.is_global:
            raise ValueError("来源不能为内网地址")
        if any(key.lower() in {"token", "access_token", "key", "api_key", "apikey", "secret", "signature"}
               for key, _ in parse_qsl(url.query)):
            raise ValueError("来源链接不能包含凭证")
        return value

    @model_validator(mode="after")
    def content_matches_format(self):
        if self.format == "article" and (not self.content or self.question is not None or self.answer is not None):
            raise ValueError("文章只能填写正文")
        if self.format == "qa" and (not self.question or not self.answer or self.content is not None):
            raise ValueError("问答必须同时填写问题和答案")
        return self


class KnowledgeUpdate(KnowledgeInput):
    version: Annotated[StrictInt, Field(ge=1)]


def register_knowledge_api(app, business, knowledge, envelope, page):
    repository = knowledge.repository

    async def administrator(request: Request):
        return await business.identify_admin(request.headers.get("Authorization"), request.headers.get("X-Guest-Token"))

    imports = Imports(repository, KnowledgeInput)
    app.state.imports = imports
    register_import_api(app, imports, administrator, envelope, page)

    @app.post('/api/v1/admin/knowledge/entries/{entry_id}/retry-index')
    async def retry_index(entry_id: str, version: int = Query(..., ge=1), actor=Depends(administrator)):
        result = repository.retry_index(entry_id, version, actor.owner)
        knowledge.schedule()
        return envelope(result)

    @app.get("/api/v1/admin/knowledge/entries")
    async def entries(page_number: int = Query(1, alias="page", ge=1), pageSize: int = Query(20, ge=1, le=100),
                      keyword: str = Query("", max_length=100), category: Literal["breed", "feeding", "training", "health", "grooming"] | None = None,
                      petType: Literal["cat", "dog", "both"] | None = None, format: Literal["article", "qa"] | None = None,
                      status: Literal["draft", "published", "archived"] | None = None,
                      indexStatus: Literal["not_indexed", "pending", "indexing", "ready", "failed"] | None = None,
                      actor=Depends(administrator)):
        return page(repository.list(keyword, category=category, petType=petType, format=format, status=status,
                                    indexStatus=indexStatus), page_number, pageSize)

    @app.get("/api/v1/admin/knowledge/entries/{entry_id}")
    async def detail(entry_id: str, actor=Depends(administrator)):
        return envelope(repository.get(entry_id))

    @app.post("/api/v1/admin/knowledge/entries", status_code=201)
    async def create(body: KnowledgeInput, actor=Depends(administrator)):
        result = repository.save(body.model_dump(), actor.owner)
        knowledge.schedule()
        return envelope(result)

    @app.put("/api/v1/admin/knowledge/entries/{entry_id}")
    async def update(entry_id: str, body: KnowledgeUpdate, actor=Depends(administrator)):
        result = repository.save(body.model_dump(), actor.owner, entry_id)
        knowledge.schedule()
        return envelope(result)

    @app.delete("/api/v1/admin/knowledge/entries/{entry_id}")
    async def delete(entry_id: str, version: int = Query(..., ge=1), actor=Depends(administrator)):
        repository.delete(entry_id, version, actor.owner)
        return envelope(dict(id=entry_id, deleted=True))
