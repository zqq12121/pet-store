# backend 代码清单与阅读顺序

> 本文件所有文件、行数、路径均于 2026-10-24 实际扫描 `backend/` 得到（排除 `target/`）。
> 业务描述取自每个类自己的 Javadoc 与方法注释，未凭文件名推测。
> 与 `docs/项目阅读顺序.md` 的区别：那份是全项目视角（含前端、AI、小程序），这份只讲 backend，且逐个文件标注业务。

---

## 一、先看规模

| 模块 | Java 文件 | 角色 | 读法 |
| --- | --- | --- | --- |
| `common` | 33 | 共享 JAR：业务骨架、持久化、认证、第三方接入 | 精读 11 个，其余按需 |
| `admin-server` | 36 | 独立 JVM:8082，认证/目录/社区/文件/后台管理 | 读 8 个，其余对照接口看 |
| `order-server` | 26 | 独立 JVM:8081，订单/预约/售后/支付/定时任务 | 精读 9 个 |
| `gateway-server` | 2 | 独立 JVM:8080，纯路由，无业务 | 20 分钟读完 |
| `integration-tests` | 20 | 回归测试 + 迁移工具，不部署 | 当行为规格用，后置 |
| **合计** | **117** | Java 源码约 2.03 万行 | **真正要读的约 30 个** |

另外：`schema.sql` 962 行、60 张表；`relational-model.json` 80KB、29 个模型；增量迁移 `V1`–`V7` 共 7 个 SQL。

**这些不要通读**：`schema.sql`、`relational-model.json`、`data/backups/*.sql`（备份）。

---

## 二、一次请求的分层骨架

看懂这个骨架，30 个类才有位置感：

```
浏览器
  └─ gateway-server          TraceIdGatewayFilter 发 traceId，按 Path 路由
       ├─ /api/v1/ai/**      → Python ai-service:8083
       └─ 其余                → admin-server:8082
                                └─ /admin/orders|refunds|pickups /**
                                     → OpenFeign → order-server:8081
买家订单 / 售后 / 支付 → order-server:8081（网关直连）

每个服务的每个接口，都走同一层壳：
Controller          只做 HTTP 适配，不写业务
  └─ ApiRequestExecutor        解身份 → 建 RequestContext → 交事务执行器 → 包响应
       └─ BusinessOperationExecutor   鉴权 → 全局写锁 → 事务 → 幂等记录
            └─ *ApplicationService    编排：取实体、校验归属、调领域服务
                 └─ *Service          业务规则唯一事实来源
                      └─ BusinessRepository  仓储门面
                           ├─ ResourceMapper        显式 SQL（锁/占位/幂等）
                           └─ RelationalRepository  模型驱动通用映射
```

三条必须记住的边界：

1. **写操作只在 `BusinessOperationExecutor` 里拿锁和事务**。Service 层方法常标 `Propagation.MANDATORY`，忘了开事务会在联调期直接抛异常。
2. **交易只在 order-server，目录写入只在 admin-server**。`CatalogReader` 在 common 是为了让订单在持锁时同库读价格和库存，避免锁内远程调用。
3. **网关只路由，不授予身份**。后台交易请求到 order-server 后会再校验一次身份和归属。

---

## 三、清单：common（33 个）

### 3.1 业务骨架（P0，必须按顺序读）

| 优先级 | 文件（`common/src/main/java/com/warmpaw/`） | 行数 | 业务 |
| --- | --- | --- | --- |
| P0-1 | `application/AccessLevel.java` | 56 | 接口"面向谁"的权限枚举。要求每个 controller 方法把级别当字面量传入，漏写编译不过 |
| P0-2 | `application/RequestContext.java` | 20 | 单次调用上下文。业务层拿不到 `HttpServletRequest`，只能从这里取方法和路径，路径还参与幂等作用域 |
| P0-3 | `application/OperationResult.java` | 22 | 业务执行结果：状态码 + 数据 + 实体类型 + 资源 id，供幂等重放和事务后钩子使用 |
| P0-4 | `application/BusinessOperationExecutor.java` | 152 | **全项目最重要的类**。写请求固定顺序：鉴权 → `SELECT ... FOR UPDATE` 锁 `business_lock` 单行 → 事务 → 幂等记录 |
| P0-5 | `application/BusinessHooks.java` | 44 | 事务结果扩展钩子。执行器在 common 不能依赖订单领域，故抽接口；只有 order-server 装配真实实现 |
| P0-6 | `controller/support/ApiRequestExecutor.java` | 95 | Controller 与业务之间唯一的胶水层：解身份 → 建上下文 → 交执行器 → 调 afterCommit → 包响应 |
| P0-7 | `controller/support/GlobalExceptionHandler.java` + `common/ApiException.java` | 143 / 46 | 异常如何变成 HTTP 状态码 + 稳定业务码 + 中文文案；内部异常不泄露 SQL、路径、令牌 |
| P0-8 | `dto/ApiResponse.java` + `controller/support/ApiResponses.java` | 13 / 46 | 成功响应信封 `{code:"OK", data, requestId, serverTime}`，与错误响应共用同一组键 |

### 3.2 持久化（P0 读门面，P1 其余）

| 优先级 | 文件 | 行数 | 业务 |
| --- | --- | --- | --- |
| P0 | `repository/BusinessRepository.java` | 263 | 仓储门面，业务访问数据的唯一入口。合并两套实现，并统一处理"实体不存在返 404"、补齐通用字段 |
| P1 | `repository/RelationalRepository.java` | 625 | 模型驱动映射：表名/字段名只取打包的 `relational-model.json` 白名单，业务值全部参数绑定 |
| P1 | `repository/ResourceMapper.java` | 105 | MyBatis 注解显式 SQL，承担 `lock` / `occupy`（主键冲突即抢占）/ 幂等回放这类必须精确控制语义的操作 |
| P1 | `repository/Filter.java` | 107 | 结构化单表条件（field + op + value）。用对象代替 where 字符串，是整个 SQL 安全边界 |
| P1 | `repository/PetQueries.java` | 120 | 宠物搜索/价格年龄筛选/分页下推到数据库，避免全表加载。返回实体，调用方必须再投影 |
| P2 | `service/Store.java` | 137 | `BusinessRepository` 的精简孪生体：同样方法体，但没有 lock/occupy/幂等这组交易能力 |

### 3.3 认证与外部接入（P1，做登录/安全时读）

| 优先级 | 文件 | 行数 | 业务 |
| --- | --- | --- | --- |
| P1 | `service/AuthService.java` | 752 | 认证与账号安全总入口：短信/密码/微信登录、不透明会话 token（库内只存 SHA-256 摘要）、验证码、改密改用户名。管理端会话 30 分钟、买家 2 小时 |
| P1 | `service/PasswordService.java` | 96 | 密码哈希与校验，从 AuthService 拆出的独立安全边界，无状态 |
| P1 | `service/GraphCaptchaService.java` | 136 | 阿里云图形认证二次校验，HMAC-SHA256 签名后调云端；未配置直接失败，不回退 |
| P1 | `service/SmsGateway.java` | 191 | 阿里云短信 RPC 签名发送。验证码走号码认证赠送模板；本地模式写进 `backend/data/local-inbox/` |
| P1 | `service/TemporaryStore.java` | 222 | 双实现临时键值：Redis（Lua 原子 take/replace/limit）或进程内内存，由 `app.redis-enabled` 决定 |
| P2 | `service/GuestService.java` | 71 | 游客身份签发，只存 TemporaryStore，TTL 24 小时，不可撤销 |
| P2 | `service/SchedulerLock.java` | 106 | 定时任务跨实例互斥（Redis `SET NX EX`），防多实例重复对账、重复发短信 |
| P2 | `common/ProviderSupport.java` | 53 | 跨服务环境读取 + 站内跳转校验（防开放重定向：拦 `//`、反斜杠、CR/LF、`%2f`） |
| P2 | `common/Json.java` | 245 | 全项目 Map 与 JSON 文本的唯一转换点，写库 JSON 列、幂等快照都走它 |

### 3.4 共享目录读取（P0，跨服务的关键设计）

| 优先级 | 文件 | 行数 | 业务 |
| --- | --- | --- | --- |
| P0 | `service/CatalogReader.java` | 618 | 只读投影层，零写入路径。三种视图：`card`（列表卡片）/ `detail`（详情）/ `adminPet`（后台）。统一裁剪成本价、审核备注、`ownerId` 等内部字段 |

> 为什么它在 common、`CatalogService` 在 admin-server：订单要在持有数据库锁的情况下校验价格、检疫和库存，不能在锁内发 HTTP。

### 3.5 公共校验与启动（P1/P2）

| 优先级 | 文件 | 行数 | 业务 |
| --- | --- | --- | --- |
| P1 | `common/Input.java` | 199 | **全项目主要 400 来源**。请求体字段白名单：出现白名单外的键直接 400，从根上禁止客户端提交 status/ownerId/金额 |
| P2 | `common/TimeRange.java` | 82 | 列表查询时间范围校验（顺序、跨度）与内存过滤，统一闭区间规则 |
| P2 | `common/AppointmentHours.java` | 97 | 营业时段解析与校验。只支持"每天同一时段"，拒绝跨天，`22:00-02:00` 直接 422 |
| P2 | `config/Bootstrap.java` | 169 | 首次启动初始化门店与管理员，缺才写所以幂等；三道启动闸门不过就起不来 |
| P2 | `web/TraceIdFilter.java` | 86 | 每个 HTTP 请求建 traceId，进 MDC 并在响应头回带 |
| P2 | `dto/FileDownload.java` | 18 | 文件下载描述，磁盘路径只在进程内传递，不进响应体 |
| 只看一眼 | `web/ApiErrors.java` | 107 | `GlobalExceptionHandler` 的早期版本，两个都注册，输出结构一致。知道它为何存在即可 |

---

## 四、清单：admin-server（36 个）

### 4.1 入口与转发（P0）

| 优先级 | 文件（`admin-server/src/main/java/com/warmpaw/`） | 行数 | 业务 |
| --- | --- | --- | --- |
| P0 | `boot/admin/AdminServerApplication.java` | 46 | 进程入口。`scanBasePackages="com.warmpaw"` 复用 common；只扫 `remote` 包做 Feign 客户端 |
| P0 | `remote/AdminTransactionController.java` | 271 | 后台交易请求转发：校验本服务能看到的身份（员工/店长两档）后，透传 Token、查询参数、请求体、`Idempotency-Key` |
| P0 | `remote/OrderAdminClient.java` | 74 | Feign 声明。返回原生 `feign.Response`，好让 4xx/5xx 不被解码成异常，原样回给浏览器 |
| P1 | `remote/RemoteOrderExceptionHandler.java` | 38 | 下游不可达返 503，不自动重试核销和退款。`@Order(-1)` 抢在通用处理器之前 |
| P2 | `config/FeignTraceConfig.java` | 43 | 让 Feign 出站带上当前 traceId，否则一次"确认预约"在日志里变成两条互不相关的链路 |

### 4.2 应用层（P1，编排逻辑）

| 优先级 | 文件 | 行数 | 业务 |
| --- | --- | --- | --- |
| P1 | `application/CatalogApplicationService.java` | 131 | 宠物与门店用例编排，核心是区分"公开展示"与"后台编辑"两套视图；个体校验全交给 CatalogService |
| P1 | `application/AccountApplicationService.java` | 79 | 买家资料查看与修改（昵称/头像）。身份只来自 `AuthService.Actor`，不接受客户端 userId |
| P1 | `application/AgreementApplicationService.java` | 101 | 协议展示与后台维护。所有出口经 `agreementDto` 裁剪，且必须带 `contentHash` 供签署一致性校验 |

### 4.3 Controller（P1 选读代表，P2 其余对照接口看）

| 优先级 | 文件 | 行数 | 业务 |
| --- | --- | --- | --- |
| P0 | `controller/AuthController.java` | 458 | 认证接口总清单：短信登录、密码登录、管理端登录、密码重置、能力查询、图形验证码 |
| P1 | `controller/CommunityController.java` | 446 | 社区帖子/评论/评价接口，纯参数透传，审核逻辑在 CommunityService |
| P1 | `controller/FileController.java` | 149 | 文件上传与授权访问。同一方法挂 `/files` 与 `/admin/files`，靠请求路径区分权限强度 |
| P1 | `controller/publicapi/HomeController.java` | 51 | 首页聚合：门店 + 轮播 + 推荐宠物 + 分类，公开无鉴权 |
| P1 | `controller/publicapi/PetController.java` | 84 | 公开宠物查询：分类、列表、详情 |
| P2 | `controller/admin/AdminPetController.java` | 183 | 后台宠物管理，刻意分两档权限：读 `STAFF`，写 `ADMIN` |
| P2 | `controller/admin/AdminShopController.java` | 78 | 门店配置读写（营业时间、交易时限影响买家侧） |
| P2 | `controller/admin/AdminAgreementController.java` | 112 | 协议后台四组路径：列表/详情/新建/发布 |
| P2 | `controller/admin/AdminEmployeeController.java` | 136 | 员工账号维护，本类不做鉴权，全交 EmployeeService |
| P2 | `controller/buyer/AccountController.java` | 76 | 买家本人资料接口，用户 id 只取自会话 |
| P2 | `controller/buyer/PetFavoriteController.java` | 85 | 买家收藏，三个方法都是 `BUYER` 级 |
| P2 | `controller/publicapi/ShopController.java` | 47 | 公开门店信息，不含交易时限等后台字段 |
| P2 | `controller/publicapi/AgreementController.java` | 49 | 当前生效协议，公开（下单前必须能读原文） |
| P2 | `controller/WechatMiniAuthController.java` | 47 | 小程序 code 登录，纯公开入口 |
| P2 | `controller/WechatMiniQrController.java` | 129 | 小程序扫码登录，五个接口鉴权强度不同 |

### 4.4 领域服务（P1/P2）

| 优先级 | 文件 | 行数 | 业务 |
| --- | --- | --- | --- |
| P1 | `service/CatalogService.java` | 382 | 目录写服务：宠物档案、上架审核、协议发布、门店配置。继承 `CatalogReader` 获得读能力，写能力不下沉 |
| P1 | `service/FileService.java` | 438 | 文件存储：系统生成文件名（原始名不进路径，杜绝穿越）、按内容嗅探类型、私有附件短时授权 |
| P1 | `service/WechatLoginService.java` | 344 | 网页授权与开放平台扫码两套登录。state 与 HttpOnly Cookie 绑定，openid 只在服务端换取 |
| P1 | `service/EmployeeService.java` | 201 | 员工管理。只管 `role=staff` 的行，店长账号在这里一律"不存在"，因此没有删除操作，离职只能停用 |
| P2 | `service/CommunityService.java` | 692 | 社区业务：内容可见性、归属校验、审核。不开事务不加锁，假定调用方已在写事务内 |
| P2 | `service/PetFavoriteService.java` | 96 | 宠物收藏，`Propagation.MANDATORY`，业务键 `用户:宠物` 保证一行 |
| P2 | `service/WechatMiniQrService.java` | 259 | 扫码登录凭据流转：ticket / browserToken / 领取分离，手机不能领网站令牌 |
| P2 | `service/WechatMiniLoginService.java` | 135 | 小程序登录：code 换 openid，不要求手机号，不返回 session_key |
| P2 | `service/OssStorage.java` | 123 | OSS 存原件 + 本地卷做校验与读取缓存，对象全私有 |
| P2 | `service/MaintenanceService.java` | 144 | 每日清理未被引用的临时文件，判据刻意保守 |
| P2 | `service/CommunityInitializer.java` | 61 | 启动时补社区初始词条，只补不改不删 |

---

## 五、清单：order-server（26 个）

### 5.1 交易核心（P0）

| 优先级 | 文件（`order-server/src/main/java/com/warmpaw/`） | 行数 | 业务 |
| --- | --- | --- | --- |
| P0 | `boot/order/OrderServerApplication.java` | 28 | 进程入口：订单、预约、售后、支付回调、退款工作器都在这里 |
| P0 | `controller/buyer/OrderController.java` | 241 | 买家订单接口。类注释已写明权限模型、幂等策略、字段校验理由；`beginPayment` 固定返 409 |
| P0 | `application/OrderApplicationService.java` | 323 | 订单编排层：preview / create / cancel / 预约处理 / 自提码 / 核销 / 本地模拟支付。权限判定留给领域层 |
| P0 | `service/OrderService.java` | 655 | 订单领域核心：金额、协议签署、四类快照（价格/商品/门店/协议）、库存占用、交付状态的事实来源 |
| P0 | `service/AppointmentService.java` | 180 | 预约状态机本体：`pending_confirmation → reservation_confirmed → completed`，含 cancel / process / expire |
| P0 | `application/OrderBusinessHooks.java` | 107 | 交易域专属钩子：交易状态回放 + 事务提交后推进支付。只部署在 order-server |

### 5.2 支付与退款（P1）

| 优先级 | 文件 | 行数 | 业务 |
| --- | --- | --- | --- |
| P1 | `service/PaymentService.java` | 502 | 支付与退款两阶段：事务内只落"意图"，平台调用交给提交后的工作器 |
| P1 | `service/PaymentWorker.java` | 328 | 真正调微信平台 + 每分钟对账。核心原则：外部调用永远在数据库提交之后 |
| P1 | `service/WechatGateway.java` | 379 | 微信支付 API v3 协议层：请求签名、响应验签、回调验签、AES-256-GCM 解密。不含业务判断 |
| P1 | `service/PaymentCallbackService.java` | 93 | 回调处理顺序不可换：先验签（用原始报文）→ 再解密 → 最后同事务加锁校验金额幂等入账 |
| P2 | `controller/PaymentCallbackController.java` | 67 | 微信支付/退款回调入口，公开，靠签名认证，网关不对前端开放 |
| P2 | `application/PaymentApplicationService.java` | 66 | 只剩两个能力：能力查询、受限的退款重试（仅本地模式） |
| P2 | `controller/admin/AdminRefundController.java` | 56 | 后台退款重试，`ADMIN` 级 |
| P2 | `controller/dev/LocalPaymentController.java` | 64 | 本地模拟支付，非本地模式返 404 而不是 403（线上"不存在"这个后门） |

### 5.3 售后与经营看板（P1）

| 优先级 | 文件 | 行数 | 业务 |
| --- | --- | --- | --- |
| P1 | `service/AfterSaleService.java` | 555 | 售后：申请、审核、退回验收、换宠、退款编排。售后单与退款单是两张表 |
| P1 | `application/AfterSaleApplicationService.java` | 190 | 售后编排：买家提交/查看、换宠确认、店长审核/选替换宠/验收/交付/转退款 |
| P2 | `application/TransactionDashboardService.java` | 152 | 交易看板。营业额必须把历史线上支付与当前线下收款相加，否则会出现"卖了 5 只、营业额 0" |
| P2 | `controller/admin/AdminDashboardController.java` | 52 | 后台首页看板接口，`ADMIN` 级 |

### 5.4 预约短信（P1，分布式事务的典型样本）

| 优先级 | 文件 | 行数 | 业务 |
| --- | --- | --- | --- |
| P1 | `service/AppointmentSmsOutbox.java` | 226 | 短信发件箱。让"状态变更"和"要发短信"同事务生效，含租约、重试次数上限、退避 |
| P1 | `service/AppointmentSmsWorker.java` | 126 | 发送执行者。两个触发源：提交后立即发、每 30 秒扫一次兜底 |
| P1 | `service/AppointmentSmsNotifier.java` | 89 | 登记入口：判断该不该发、固化内容快照、注册提交后回调。强制要求已在事务内 |

### 5.5 后台交易接口（P2，配合 4.1 一起看）

| 优先级 | 文件 | 行数 | 业务 |
| --- | --- | --- | --- |
| P1 | `controller/admin/AdminOrderController.java` | 98 | 后台订单查询：读 `STAFF`，唯一写接口 `ADMIN` |
| P2 | `controller/admin/AdminPickupController.java` | 87 | 到店核销：扫码/输码查单 + 完成交付 |
| P2 | `controller/admin/AdminAfterSaleController.java` | 197 | 后台售后处理，全部 `ADMIN` |
| P2 | `controller/buyer/AfterSaleController.java` | 151 | 买家售后：挂在订单下与直接访问售后单两类路径 |

---

## 六、清单：gateway-server（2 个）+ 配置与 SQL

| 优先级 | 文件 | 行数 | 业务 |
| --- | --- | --- | --- |
| P0 | `gateway/GatewayServerApplication.java` | 16 | 网关入口。不扫 `com.warmpaw`，刻意与业务 Bean 隔离 |
| P1 | `gateway/TraceIdGatewayFilter.java` | 95 | 全链路 traceId 起点。WebFlux 线程模型，不能用 MDC 那套 |
| P1 | `resources/application.yml` | 46 | 路由表。注意 `ai-api` 必须排在 `/api/**` 兜底之前；后台交易先到 admin 再经 Feign |

配置文件（P2，查配置时看）：

| 文件 | 行数 | 内容 |
| --- | --- | --- |
| `common/src/main/resources/application-common.yml` | 61 | 数据源、Redis、Flyway（`baseline-version=7`，V1–V7 标记为已应用）、上传 100MB |
| `admin-server/src/main/resources/application.yml` | 14 | 端口 8082 + Feign 超时 3s/15s |
| `order-server/src/main/resources/application.yml` | 10 | 端口 8081 |
| `common/src/main/resources/schema.sql` | 962 | 60 张表全量建表。**不通读**，跟着链路查 |
| `common/src/main/resources/relational-model.json` | — | 29 个模型的表/字段/子表映射，`RelationalRepository` 的唯一配置源 |
| `db/migration/V1`–`V7*.sql` | — | 历次增量迁移，看文件名即知业务：账号安全、员工、预约、短信 outbox、社区、收藏、门店资料 |

---

## 七、清单：integration-tests（20 个）

**这些是最好的行为规格**，比读实现更快理解"系统应该怎样"。等主链路读完再回来看。

| 优先级 | 文件 | 行数 | 覆盖业务 |
| --- | --- | --- | --- |
| P1 | `CommerceIntegrationTest.java` | 1392 | 34 个用例，交易全链路：HTTP 契约、并发抢购唯一赢家、幂等重放、越权隔离、超时退款互斥 |
| P1 | `AccountSecurityTest.java` | 189 | 账号安全 8 例：首次登录哈希、会话撤销、改密冷却、并发重置只成功一次 |
| P1 | `AppointmentSmsOutboxTest.java` | 100 | outbox 5 例：回滚不发、租约不可重复领取、崩溃 8 次停手、退避与上限 |
| P2 | `AppointmentSmsNotifierTest.java` | 189 | 短信登记 6 例：事务内持久化、提交前不发、重复事件不重发 |
| P2 | `CommunityIntegrationTest.java` | 308 | 社区 15 例：匿名只读、评价资格、两级回复、敏感词、并发唯一 |
| P2 | `EmployeeManagementTest.java` | 157 | 员工 9 例：不可提权、禁用不恢复旧会话、改密撤销全部会话 |
| P2 | `WechatWebLoginTest.java` | 188 | 网页登录 7 例：state 绑定浏览器、单次有效、失败不建账号 |
| P2 | `WechatMiniQrTest.java` | 164 | 扫码登录 6 例：一次消费、过期不复活、并发只有一个赢家 |
| P2 | `WechatMiniLoginTest.java` | 158 | 小程序登录 5 例：同一身份复用账号、上游故障不报"code 过期" |
| P2 | `GraphCaptchaTest.java` | 160 | 图形验证 5 例：HMAC 表单编码、不可重放、失败绝不放行 |
| P2 | `RelationalPersistenceTest.java` | 166 | 映射层 4 例：写列读改动、子表失败整体回滚、未映射字段写前失败 |
| P2 | `PetFavoriteIntegrationTest.java` | 136 | 收藏 5 例：跨会话持久、归属只来自会话、并发只建一行 |
| P2 | `PnvsSmsTest.java` | 108 | 短信 5 例：赠送模板、成功标志、失败不存码、码与手机号绑定 |
| P2 | `EmployeeForwardingTest.java` | 57 | 转发 2 例：透传身份与幂等，但员工令牌不能确认/取消订单 |
| P2 | `AiIdentityTest.java` / `WechatCapabilityTest.java` | 60 / 29 | AI 身份校验；微信凭证完整性 |
| P2 | `service/OssStorageTest.java` | 64 | OSS 存取 |
| 跳过 | `WarmPawApplication.java` | 12 | 集成测试聚合上下文，**不是启动入口** |
| 跳过 | `demoacceptance/DemoAdminApplication.java` | 95 | 演示环境专用配置 |
| P2 | `main/java/com/warmpaw/tools/MigrateLegacy.java` | 213 | 一次性离线迁移工具：旧单表 JSON 拆到领域表。不启动 Spring，不删旧数据 |

---

## 八、阅读顺序

### 第 0 轮：地图（30 分钟，不碰代码）

1. `README.md` 的「项目用途」「目录与子项目」——记住核心闭环：**浏览 → 预约 → 店长确认 → 到店线下收款，没有线上支付**。这一条决定了后面一半代码为什么长这样。
2. `backend/ARCHITECTURE.md`（85 行，全项目信息密度最高）——重点看「模块与运行位置」的转发图和「业务边界」六条。
3. 本文第二、三节的骨架图。

**自检**：浏览器的一个请求要经过几个进程？为什么要拆 admin 和 order？为什么拆了还共用一个库？

---

### 第 1 轮：common 底座（1.5 小时，7 个文件）

顺序不能乱，前一个不懂就停下：

| 顺序 | 文件 | 只关注 |
| --- | --- | --- |
| 1 | `application/AccessLevel.java` | 有哪几级，级别不是线性高低 |
| 2 | `application/RequestContext.java` | 携带什么，路径为什么参与幂等 |
| 3 | `application/OperationResult.java` | 四个字段各给谁用 |
| 4 | `controller/support/ApiRequestExecutor.java` | `execute` 的签名和 `idempotent` 参数 |
| 5 | `application/BusinessOperationExecutor.java` | 四步顺序：鉴权 → 锁 → 事务 → 幂等 |
| 6 | `application/BusinessHooks.java` | 为什么必须抽接口 |
| 7 | `common/Input.java` | 白名单为什么比逐字段校验更强 |

**自检**：一个带 `Idempotency-Key` 的 POST，从进 Controller 到事务提交经过哪几层？幂等键的作用域是什么？为什么 `PaymentWorker` 不在 common？

---

### 第 2 轮：纵向打穿"提交预约"（2 小时，核心）

这是唯一的核心闭环，串起七个层次。严格按调用顺序：

| 顺序 | 文件 | 关注点 |
| --- | --- | --- |
| 1 | `order-server/controller/buyer/OrderController.java` | 类注释里的权限模型、幂等策略、为什么 404 不 403 |
| 2 | `order-server/application/OrderApplicationService.java` | `create` / `preview` / `cancel` 三者的差别；`beginPayment` 为什么固定 409 |
| 3 | `order-server/service/AppointmentService.java` | 状态机四个方法，谁能推动状态 |
| 4 | `order-server/service/OrderService.java` | 只读 `create` 附近：快照冻结、金额校验、库存占用 |
| 5 | `common/repository/BusinessRepository.java` | 只查上一步调到的接口 |
| 6 | `common/repository/RelationalRepository.java` | 只看 `create`/`save` 走到的那条路径，别顺读 625 行 |
| 7 | `common/mapper/ResourceMapper.java` | `occupy` 为什么靠主键冲突 |
| 8 | `schema.sql` 跳读 | `orders`、`order_appointments`、`order_snapshots`、`agreement_versions`、`pet_occupancy`、`business_lock` |
| 9 | `order-server/service/AppointmentSms{Notifier,Outbox,Worker}.java` | 为什么走 outbox 而不是同步发 |
| 10 | `order-server/application/OrderBusinessHooks.java` | 事务提交后做了什么 |
| 11 | `integration-tests/CommerceIntegrationTest.java` | 只挑 `concurrentPurchaseHasOneWinner`、`idempotencyReplaysAndRejectsDifferentBody`、`rejectsUnknownFieldsAndStalePrice` 三个 |

**自检**：一宠一锁锁在哪张表的哪一行？为什么下单要回传 `expectedAmount` 和 `productVersion`？短信 worker 挂了会怎样，为什么可接受？

---

### 第 3 轮：横向三个域（各 40 分钟）

**3.1 认证登录**（最通用）
`admin-server/controller/AuthController.java`（接口清单即功能清单）→ `common/service/AuthService.java`（**只找三块**：`role()` 权限判定、会话校验、`identify`）→ `PasswordService` → `GraphCaptchaService` → `SmsGateway` → `WechatLoginService`（两套应用别混用）。

**3.2 目录读写分离**
`admin-server/controller/publicapi/PetController.java` → `application/CatalogApplicationService.java` → `service/CatalogService.java`（写）→ `common/service/CatalogReader.java`（读，只看 `card` 和 `adminPet` 的差集）。
关键：为什么 `CatalogReader` 在 common 而 `CatalogService` 在 admin-server。

**3.3 后台交易转发**（微服务里最容易看不懂的地方）
`admin-server/remote/OrderAdminClient.java` → `remote/AdminTransactionController.java` → `order-server/controller/admin/AdminOrderController.java` → `remote/RemoteOrderExceptionHandler.java`。
关键：为什么网关不给后台请求"授予"管理员身份？order-server 挂了哪些后台功能还能用？

---

### 第 4 轮：支付与售后（1.5 小时，可延后）

`PaymentService`（两阶段：先落意图，再异步调平台）→ `PaymentWorker`（对账与重试）→ `WechatGateway`（纯协议层）→ `PaymentCallbackService`（验签→解密→入账，顺序不可换）→ `AfterSaleService`（售后单与退款单为什么两张表）。

**自检**：为什么不能在事务里同步调微信？超时了怎么办？

---

### 第 5 轮：看懂测试与坑（1 小时）

`CommerceIntegrationTest` 全文 → `AppointmentSmsOutboxTest` → `backend/API_STATUS.md`（哪些接口真跑通）→ 其余测试按需。

---

### 两小时版

只读 **第 0 轮 + 第 1 轮 + 第 2 轮前 5 步**。足够回答"这个项目的骨架是什么"。

---

## 九、按业务域找文件（改功能时用）

| 我要改… | 从这几个文件入手 |
| --- | --- |
| 登录 / 会话 / 验证码 | `AuthController` → `AuthService` → `PasswordService` / `GraphCaptchaService` / `SmsGateway` |
| 微信登录（三套） | `WechatLoginService`（网页+扫码）、`WechatMiniLoginService`（小程序）、`WechatMiniQrService`（扫码确认） |
| 宠物上下架 / 字段 | `AdminPetController` → `CatalogApplicationService` → `CatalogService` → `CatalogReader`（投影） |
| 门店设置 / 协议 | `AdminShopController` / `AdminAgreementController` → `AgreementApplicationService` → `CatalogService` |
| 下单 / 预约状态 | `OrderController` → `OrderApplicationService` → `AppointmentService` / `OrderService` |
| 支付 / 退款 | `PaymentService` → `PaymentWorker` → `WechatGateway`；回调走 `PaymentCallbackService` |
| 售后 / 换宠 | `AfterSaleController` / `AdminAfterSaleController` → `AfterSaleApplicationService` → `AfterSaleService` |
| 社区 / 评价 | `CommunityController` → `CommunityService`（+ `CommunityInitializer`） |
| 文件上传 / 清理 | `FileController` → `FileService` → `OssStorage`；清理在 `MaintenanceService` |
| 员工管理 | `AdminEmployeeController` → `EmployeeService` |
| 跨服务调用出错 | `AdminTransactionController` → `OrderAdminClient` → `RemoteOrderExceptionHandler` |

---

## 十、六个坑，先知道省时间

1. **`WarmPawApplication` 不是启动入口**，只是集成测试的聚合上下文。真实入口是三个 `*ServerApplication`。
2. **`web/ApiErrors.java` 和 `controller/support/GlobalExceptionHandler.java` 同时存在**，都会注册成 `@RestControllerAdvice`，输出结构一致。看到两份别以为是重复代码没删干净——这是历史遗留，改错误处理时优先改后者。
3. **`Store.java` 是 `BusinessRepository` 的精简孪生体**，方法体逐行相同但少了交易能力。不要以为有两个仓储实现是设计失误。
4. **Service 方法上的 `Propagation.MANDATORY` 不是摆设**。绕过 `ApiRequestExecutor` 直接调服务会抛异常，这是刻意的。
5. **`schema.sql` 962 行、60 张表不要通读**。而且它和 `V1`–`V7` 是两条线：新建库走 `schema.sql`，已有库走 Flyway（`baseline-version=7`）。
6. **技术栈很新**：Java 25 / Spring Boot 4.0.7 / Spring Cloud 2025.1.3。网关配置是 `spring.cloud.gateway.server.webflux.routes` 这套新结构，和网上 Boot 3.x 教程写法不一样。对不上时先怀疑教程版本。

---

## 十一、一句话记忆法

> 网关只路由不授身份 → 所有接口过 `ApiRequestExecutor` → 写操作在 `BusinessOperationExecutor` 里拿锁和事务 → 规则在 `*Service`、编排在 `*ApplicationService` → 交易只在 order-server，目录写只在 admin-server → 对外一律经 `CatalogReader` 投影裁剪 → 外部调用永远在事务提交之后。
