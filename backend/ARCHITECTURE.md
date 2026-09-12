# 微服务结构与阅读指南

项目使用 Java 21、Spring Boot 4.0.7、Spring Cloud 2025.1.3、Spring Cloud Alibaba 2025.1.0.0。后台交易通过 OpenFeign 调用订单服务，服务地址由 Nacos 发现；前端 API 路径和响应格式保持原样。

## 模块与运行位置

| 模块 | 职责 | 默认端口 | 运行位置 |
|---|---|---|---|
| `gateway-server` | 对外统一 API 入口、按路径路由 | 8080 | Mac / IDEA，独立 JVM |
| `order-server` | 订单、支付、退款、自提、售后、交易统计 | 8081 | Mac / IDEA，独立 JVM |
| `admin-server` | 商品、门店、协议、账号、文件、后台入口 | 8082 | Mac / IDEA，独立 JVM |
| `common` | 共享校验、身份、Redis、关系存储、目录快照读取、事务接口 | 无 | 两个业务服务依赖的普通 JAR |
| `integration-tests` | 原有完整业务回归和离线迁移工具 | 随机测试端口 | 测试时运行，不部署 |
| Nacos | 服务注册与发现；控制台独立端口 | 8848 / 9848 / 控制台 8088 | 新增的 Docker 容器 |

Nginx、MySQL、Redis 沿用原有 Docker 容器。Nginx 继续将 `/api/` 转发给宿主机 8080，此端口现在由网关提供。

```text
浏览器 → Nginx → gateway-server
                    ├─ 买家订单 / 支付 / 售后 → order-server
                    └─ 商品 / 账号 / 文件 / 后台 → admin-server
                                                   └─ 后台订单、退款、核销、交易统计
                                                      → OpenFeign → order-server

三个服务注册到 Nacos；业务服务共享原 warmpaw 库及 Redis。
```

## 业务边界

- 商品、门店、协议的写入实现 `CatalogService` 只在 `admin-server`。订单、支付和售后的实现只在 `order-server`，管理服务不依赖订单服务 JAR。
- `admin-server/remote/OrderAdminClient` 显式声明后台交易接口；`AdminTransactionController` 校验管理员身份后透传原 Token、查询参数、请求体与 `Idempotency-Key`。不开放任意 URL 转发。
- 订单服务收到请求后再次校验身份、角色和资源归属。网关不会凭空授予管理员身份，也不会把客户端传入的用户 ID 当成认证结果。
- 下游业务 HTTP 状态保持原样；无法连接订单服务返回 503，不自动重试核销和退款。
- 订单服务停止时，后台订单操作不可用，商品和门店等管理功能仍由管理服务提供。
- 账号与文件暂由管理服务对外提供，共享身份组件供订单服务验证会话；上传凭证的订单归属检查由共享仓储完成。

## 为什么暂时仍共享数据库

本阶段按约定不分库、不建新业务表、不迁移运行数据。所有服务默认 `spring.sql.init.mode=never`。

`CatalogReader` 是不含目录写入的共享读取与快照投影。订单需要在持有数据库事务锁时检查价格、检疫状态和库存，因此直接读取同一数据库快照，不在锁内远程调用管理服务。订单服务仍负责宠物交易状态及 `pet_occupancy` 的原子变更；管理服务负责商品资料和上下架，两者沿用同一 `business_lock` 锁序。

这是独立进程、明确交易职责的微服务第一阶段，数据库尚未按领域隔离。后续若分库，需要单独设计库存预留、失败补偿与数据一致性，不能直接把当前本地事务换成 HTTP 调用。

## 事务、幂等与定时任务

- `common/application/BusinessOperationExecutor` 保留认证后的事务、数据库锁和幂等记录；幂等范围仍为用户、方法、原始业务路径及幂等键。
- `BusinessHooks` 解耦公共执行器与交易实现。只有订单服务装配 `OrderBusinessHooks`，负责交易状态回放与事务提交后的支付处理。
- `PaymentWorker` 只存在于订单服务，避免管理服务重复对账、取消超时订单或推进退款。
- `MaintenanceService` 只存在于管理服务，负责上传文件清理。
- 两个业务服务必须连接同一 Redis，验证码、限流和临时凭证才可跨服务使用。不要给实际微服务配置分别关闭 Redis；只有聚合单 JVM 测试使用内存替代。

## 代码阅读入口

各模块源码目录都是 `src/main/java`：

- `order-server/com/warmpaw/boot/order/OrderServerApplication.java`
- `admin-server/com/warmpaw/boot/admin/AdminServerApplication.java`
- `gateway-server/com/warmpaw/gateway/GatewayServerApplication.java`
- `admin-server/com/warmpaw/remote/`：Feign 客户端、后台交易转发和故障处理。
- `common/com/warmpaw/service/CatalogReader.java`：共享读取；目录写入在管理模块的 `CatalogService`。
- 原 Controller → ApplicationService → Service → Repository 的层次保留，源文件按业务迁入对应模块。

Nacos 当前只负责服务发现；业务配置继续来自本地 `.env` 和 YAML，不把密码提交到仓库。Nacos 客户端注册端口只绑定本机，容器采用独立持久卷；控制台初次使用按页面提示初始化管理员。

## 验证

在 `backend` 执行：

```bash
mvn package
python3 scripts/microservices_smoke.py
```

- Maven 保留原 31 项 HTTP、竞态、权限、持久化与迁移测试，默认独立内存 H2；`WarmPawApplication` 现在仅是集成测试聚合上下文，不是生产启动入口。
- `microservices_smoke.py` 启动三个真实 JVM、临时 H2 TCP 数据库和独立 Redis，通过独立 Nacos 分组验证服务发现、网关、Feign、登录、验证码、上传、订单、模拟支付、核销、错误状态及订单服务故障。仅需先启动 Nacos，不使用现有 MySQL。
- 冒烟日志保存在输出的临时目录；脚本结束会停止它自己创建的 JVM 和 Redis，不停止 Nacos，也不删除现有容器或卷。
- `pom.xml` 显式固定 MyBatis Starter 4.0.1，防止 Alibaba BOM 将传递依赖降为不兼容 Boot 4 的 3.x。

当前未增加消息队列、Seata、独立认证服务或数据库拆分。真实微信/短信、AI、生产扩容和多实例支付任务协调不在本次实现范围。
