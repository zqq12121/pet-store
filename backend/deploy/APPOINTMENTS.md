# 到店预约版本部署与验收

本版本将买家 `POST /api/v1/orders` 改为创建到店预约，线上不收款。历史订单仍可查询；已有微信支付的通知、查单、关单和退款收尾代码保留，但不再接受新的线上支付请求。

## 部署顺序

1. 备份数据库，执行同目录 `appointment-migration.sql`。该脚本只新增 `order_appointments` 子表，可重复执行，不清空或重写原订单。生产配置禁止启动时自动建表，不能跳过此步骤。
2. 构建并更新 `common` 所属的 order-server、admin-server JAR，同时更新买家端、管理端静态资源。网关路由仍沿用 `/orders/**` 与 `/admin/**`，无新增路由。
3. 店长在门店设置中填写营业时间，如 `每天 10:00-20:00`，当前支持每天相同、不过夜的单时段，统一按北京时间。原先的“请联系门店确认”等文本会阻止新预约，不会猜测营业时间。
4. 验证提交预约、后台联系人/手机号/到店时间、确认、取消释放库存、到店收款交付、超时释放库存。不要在生产环境使用演示收款或假交易做测试。

构建：在项目根目录运行 `mvn -f backend/pom.xml package`、`npm --prefix frontend run build`。

## 接口变化

- `POST /api/v1/orders`：保留 petId、productVersion、expectedAmount、contactName、contactPhone、remark 及协议字段，新增必填 `visitAt`，示例 `2026-09-13T12:00:00+08:00`。必须是登录本人手机号、未来 7 天内、营业时间内。金额仍以服务端宠物价格为准，线上不扣款。
- 买家列表 `GET /orders?tab=pending_confirmation` 或 `reservation_confirmed`；详情新增 `appointment` 对象（visitAt、confirmationDeadlineAt、confirmedAt、completedBy）。
- `POST /orders/{id}/cancel`：买家只能取消本人有效预约；释放该预约的宠物占用。
- `POST /admin/orders/{id}/appointment/confirm`：店长接受预约，空 JSON 对象即可。
- `POST /admin/orders/{id}/appointment/cancel`：必填 `reason`，长度 1–200；原因展示给买家。
- `POST /admin/orders/{id}/appointment/complete`：必须明确提交 `paymentReceived: true`、`deliveryConfirmed: true`、`quarantineVerified: true`。要求已确认且未过期、库存归属于此预约、检疫证明仍有效。登记的是快照金额的线下收款，完成后宠物标记 sold，重复请求不会重复记账。
- 上述写接口沿用 `Idempotency-Key`；后台接口仍需管理员 Token，管理服务通过 Feign 原样透传。
- `POST /orders/{id}/payments` 返回 `409 ONLINE_PAYMENT_DISABLED`，支付能力接口的场景均为不可用。

## 状态与时间

- pending_confirmation：提交即占用；截止取“提交后 24 小时”和“到店时间”中较早者。
- reservation_confirmed：确认后继续占用，保留到到店时间后 2 小时。
- cancelled：买家取消或店长取消，释放本预约的占用；已下架或检疫失效的宠物不会被错误上架。
- expired：每分钟清理超时预约并释放占用，新预约提交时也执行过期清理。
- completed：店长已确认实际收款、核验证明、交付；库存变 sold。确认预约本身不代表收款。

同一账号最多一个有效预约；同一个体最多一个占用，以原数据库事务锁和宠物占用唯一记录保护，不使用前端按钮作为库存保障。

线下收款标记为 `offline_received`，没有微信流水；工作台将它计入收款总额。线下预约的退款、售后由门店联系处理，不进入微信自动原路退款。当前不发送短信或微信通知，店长在工作台和预约列表查看、刷新处理。
