"""Java 保留身份与交易权限；Python 仅转发当前请求凭证读取白名单接口。"""
import re
from dataclasses import dataclass, field

import httpx

from .config import Settings


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        self.status, self.code, self.message = status, code, message
        super().__init__(message)


def resource_id(value: str) -> str:
    # 不允许模型用路径穿越、查询参数或任意 URL 扩展工具权限。
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", value):
        raise ApiError(400, "VALIDATION_ERROR", "资源编号格式不正确")
    return value


@dataclass
class Identity:
    owner: str
    role: str
    headers: dict = field(repr=False)


class BusinessClient:
    def __init__(self, settings: Settings, client: httpx.AsyncClient):
        self.settings, self.client = settings, client

    async def _get(self, base: str, path: str, headers=None, params=None):
        try:
            response = await self.client.get(base.rstrip("/") + "/api/v1" + path,
                                             headers=headers or {}, params=params)
            payload = response.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise ApiError(503, "BUSINESS_UNAVAILABLE", "业务服务暂时不可用，请稍后重试") from exc
        if response.status_code >= 400:
            # 不透传下游内部错误内容，保留业务状态与安全提示。
            if response.status_code == 401:
                raise ApiError(401, "UNAUTHORIZED", "身份无效或已过期，请重新登录")
            if response.status_code == 403:
                raise ApiError(403, "FORBIDDEN", "当前身份无访问权限")
            if response.status_code == 404:
                raise ApiError(404, "RESOURCE_NOT_FOUND", "记录不存在或不可访问")
            raise ApiError(503, "BUSINESS_UNAVAILABLE", "业务查询暂时不可用")
        if payload.get("code") != "OK":
            raise ApiError(503, "BUSINESS_UNAVAILABLE", "业务查询暂时不可用")
        return payload["data"]

    async def identify(self, authorization: str | None, guest: str | None) -> Identity:
        if authorization is not None and guest is not None:
            raise ApiError(400, "AMBIGUOUS_IDENTITY", "登录身份和游客身份不能同时传入")
        if not authorization and not guest:
            raise ApiError(401, "UNAUTHORIZED", "请先创建游客身份或登录")
        headers = {"Authorization": authorization} if authorization else {"X-Guest-Token": guest}
        actor = await self._get(self.settings.catalog_url, "/auth/ai-identity", headers)
        if actor.get("role") not in ("buyer", "guest"):
            raise ApiError(403, "FORBIDDEN", "请使用买家或游客身份")
        return Identity(f'{actor["role"]}:{actor["id"]}', actor["role"], headers)

    async def pet(self, pet_id: str):
        return await self._get(self.settings.catalog_url, "/pets/" + resource_id(pet_id))

    async def identify_admin(self, authorization: str | None, guest: str | None) -> Identity:
        if guest is not None:
            raise ApiError(403, "FORBIDDEN", "知识管理仅限管理员")
        if not authorization:
            raise ApiError(401, "UNAUTHORIZED", "请先登录管理后台")
        actor = await self._get(self.settings.catalog_url, "/admin/auth/ai-identity", {"Authorization": authorization})
        if actor.get("role") != "admin":
            raise ApiError(403, "FORBIDDEN", "知识管理仅限管理员")
        return Identity("admin:" + actor["id"], "admin", {"Authorization": authorization})

    async def pets(self, **filters):
        return await self._get(self.settings.catalog_url, "/pets", params={
            "status": "on_sale", "pageSize": 8, **{k: v for k, v in filters.items() if v is not None}})

    async def shop(self):
        return await self._get(self.settings.catalog_url, "/shop")

    async def orders(self, identity: Identity, order_id: str | None = None):
        if identity.role != "buyer":
            raise ApiError(401, "LOGIN_REQUIRED", "请登录后查看本人的预约和订单")
        path = "/orders/" + resource_id(order_id) if order_id else "/orders"
        data = await self._get(self.settings.order_url, path, identity.headers,
                               None if order_id else {"page": 1, "pageSize": 5})
        # 联系人、电话、付款参数、自提核销码不进入模型或 AI 存储。
        return [self.order_card(item) for item in ([data] if order_id else data["items"])]

    @staticmethod
    def order_card(order):
        hints = {"pending_confirmation": "等待店主确认预约", "reservation_confirmed": "已确认预约，请按约到店；尚未付款或交付",
                 "completed": "已完成", "cancelled": "已取消", "expired": "已过期"}
        return {"orderId": order["id"], "orderNo": order["orderNo"], "status": order["status"],
                "productName": order["product"]["name"], "detailPath": "/orders/" + resource_id(order["id"]),
                "nextAction": hints.get(order["status"], order.get("statusText", "查看详情")),
                "visitAt": (order.get("appointment") or {}).get("visitAt")}
