# 暖爪宠物门店后端

后端已拆分为 `order-server`、`admin-server` 和 `gateway-server`，接入 Nacos 服务发现及 OpenFeign 调用。保留原 MySQL `warmpaw` 数据、Redis 与前端 API。Java 服务运行在 Mac / IDEA，Nacos、MySQL、Redis、Nginx 运行在 Docker。

完整前后端与 AI 启停顺序见 [项目运行说明](../README.md)。当前订单采用到店预约；支付章节保留历史交易说明。
AI 已由独立 Python 服务提供，须另行启动，见 [AI 说明](../ai-service/README.md)。

## 启动与停止

从项目根目录进入后端后运行：

```bash
cd backend
mvn package
sh scripts/start-microservices.sh
```

首次启动会创建独立 Nacos 容器。现有 MySQL、Redis、Nginx 需处于运行状态；连接凭证从已忽略的 `backend/.env` 读取。先停止原来占用 8080 的单体后端。脚本发现端口冲突会退出，不自动杀死别的进程。

| 入口 | 地址 |
|---|---|
| 买家网站 | http://localhost/ |
| 管理网站 | http://localhost/admin/ |
| API 网关 | http://localhost:8080/api/v1/ |
| 订单服务健康检查 | http://localhost:8081/actuator/health |
| 管理服务健康检查 | http://localhost:8082/actuator/health |
| Nacos 控制台 | http://localhost:8088/ |

查看日志：`backend/data/logs/`。停止本次启动脚本管理的 Java 服务：

```bash
sh scripts/stop-microservices.sh
```

这不会停止或删除 Nacos、MySQL、Redis、Nginx 及数据卷。原 `sh scripts/start-docker-local.sh` 已兼容转到微服务启动脚本。

## 在 IDEA 中开发

1. 重新加载 **`backend/pom.xml`**，应看到 `common`、`order-server`、`admin-server`、`gateway-server`、`integration-tests` 五个子模块，JDK 选 21。
2. 三个主类分别是 `OrderServerApplication`、`AdminServerApplication`、`GatewayServerApplication`。工作目录统一设置为项目的 **backend 目录**，分别使用各自模块的 classpath。
3. 业务服务的环境变量使用 `backend/.env` 中的 MySQL、Redis 配置，活动 profile 设为 `local`。若 IDEA 支持导入 `.env`，直接选该文件；否则在运行配置的环境变量中配置，勿把密钥写进 Java/YAML。
4. 先启动 Nacos，再分别启动订单 8081、管理 8082、网关 8080。不要同时运行脚本启动的服务与 IDEA 中相同端口的服务。

也可先在 IDEA 的终端用已验证的脚本启动单个服务，它会自动加载 `.env`：

```bash
sh scripts/run-service.sh order-server
```

在其他终端分别运行 `admin-server` 与 `gateway-server`。原 `WarmPawApplication` 已迁为测试专用入口，不再作为后端运行。

首次运行需要单独启动 Nacos 时：

```bash
python3 scripts/init-nacos-env.py
docker compose -p warmpaw-micro -f deploy/compose-microservices.yml up -d
```

## 测试与数据

`mvn package` 默认使用独立 H2 执行业务回归。完整微服务联调：

```bash
python3 scripts/microservices_smoke.py
```

该脚本使用临时数据库和独立 Redis，验证真实跨进程调用，不向运行中的 MySQL 写测试订单。不要把测试数据库参数设置为实际运行库。

本次改造没有建表、迁移或清空数据。此前插入的 20 条猫狗示例仍保持未上架，宠物展示任务按要求暂停。详细职责、事务边界和验证方式见 [微服务结构](ARCHITECTURE.md)。

## 微服务实测结果（2026-09-12）

- `mvn package` 通过，原 31 项测试全部通过。
- 三个独立 JVM 在隔离 H2 + 独立 Redis 环境中跑通完整交易：Gateway 路由、Nacos 发现、登录、上传、下单、模拟付款、Feign 核销、同键重复核销与最终完成。
- 验证后台 401/403/404、查询参数透传；停止订单服务时后台返回 503，目录服务仍可访问。
- 已将本机原网站切换至网关 8080；三个服务在 Nacos 的 WARMPAW 分组中健康注册。
- 运行库只读核验：6 个公开接口切换前后数据一致；23 条宠物（20 条示例仍未上架）、3 条原订单保持；原有宠物摘要一致。未向运行库创建测试订单。
- 启动脚本使用独立进程会话，命令退出后 Java 服务继续运行；原单体 JAR 保留在旧 `target` 目录，未删除数据。
- 业务表 SQL 和字段映射文件与拆分前逐字节一致。真实微信/短信、生产容量及跨主机部署没有在本次验证。

## 本地短信和付款

图片验证码是真实随机 PNG；短信也是随机6位数字，不接受万能码。

- 图片验证码：120秒、一次性、绑定用途。
- 短信：300秒、绑定手机号/用途/订单或换宠方案，验证成功即消费。
- local 模式将开发验证码写入 `data/local-inbox/sms-<smsRequestId>.txt`；只在本机读取。
- 同手机号60秒内只能发送一条短信，登录后立即发送自提短信也须遵守限制。
- 已登录买家可在 local 模式调用 `POST /api/v1/demo/orders/{orderId}/pay`，也支持 `/dev/orders/{orderId}/pay`。该接口仅模拟本人待付款订单，拒绝过期订单。
- 模拟支付不会调用微信、扣款或发送真实短信；退款由每分钟对账任务推进。
- `prod` 环境强制禁用模拟服务，模拟接口不可用。真实支付能力未配置时返回明确不可用原因。

初始数据库没有预先发布的法律协议或已上架的真实宠物。需要通过后台接口上传材料、保存宠物、人工确认上架，发布测试或经经营者确认的协议后才能下单。

## 前端联调

已有前端可以通过其环境变量连接 Java 后端，不必改接口地址代码：

```dotenv
VITE_API_TARGET=http://127.0.0.1:8080
VITE_DEMO_MODE=false
```

写入 `frontend/.env.local` 后重启 Vite。原 Node 演示服务提示的固定 `DEMO` / `123456` **不适用于此后端**；请填写图片中的随机码，并从本机开发收件箱取短信码。AI 请求由网关转到独立 Python 服务；后台已提供 `/admin/agreements` 协议管理页，可创建、查看和发布版本。

## MySQL 与 Redis

生产连接独立数据库；不要把建表脚本直接执行到不属于本项目的现有库。先创建 UTF-8 的专用 MySQL 8 数据库，再执行 `common/src/main/resources/schema.sql`。

```bash
export SPRING_PROFILES_ACTIVE=prod
export PAW_DB_URL='jdbc:mysql://127.0.0.1:3306/warmpaw?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai'
export PAW_DB_USERNAME='warmpaw'
# PAW_DB_PASSWORD、PAW_ADMIN_PASSWORD 通过终端环境/IDE/部署密钥设置，不提交源码。
export PAW_REDIS_HOST='127.0.0.1'
export PAW_REDIS_PORT='6379'
sh scripts/run-service.sh order-server
# 另开终端启动 admin-server 和 gateway-server
```

Redis 密码用 `PAW_REDIS_PASSWORD`，生产通过 Redis 执行临时凭证、原子消费和限流。本地内存验证码会在重启后失效，订单、支付和会话凭证摘要仍持久化。

数据库现已按PRD和接口文档拆为 `pets`、`users`、`orders`、`payments`、`refunds`、`after_sales` 等独立领域表；图片、标签、检疫材料和轮播图单独关联，历史订单快照保留JSON。宠物筛选/排序/分页已下推SQL。`pet_occupancy`外键关联pets/orders，`business_lock`继续保护单店写事务。旧数据已显式迁移，旧表保留为resources_legacy，仅作迁移前参考。详细表清单、字段、备份和回退说明见 [数据库设计](DATABASE.md)。其他部分列表仍在内存筛选，尚未进行生产压测。

## 第三方接入配置

当前按用户要求优先完成**本地模拟验证**。接入代码已提供，但没有真实资质/密钥，不能宣称完成真实短信、微信授权、扣款或退款联调。

| 模块 | 环境变量 |
|---|---|
| 阿里云短信 | `PAW_SMS_ACCESS_KEY_ID`、`PAW_SMS_ACCESS_KEY_SECRET`（两者均未配置时使用 `OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET`）；另需 `PAW_SMS_SIGN_NAME`、`PAW_SMS_LOGIN_TEMPLATE`、`PAW_SMS_CONFIRM_TEMPLATE`；模板参数为 `code`，凭据所属 RAM 身份需具备短信发送权限 |
| 微信网页授权 | `PAW_WECHAT_APP_ID`、`PAW_WECHAT_APP_SECRET`、`PAW_WECHAT_OAUTH_REDIRECT`（固定 HTTPS 前端回调页） |
| 微信 API v3 | `PAW_WECHAT_MCH_ID`、`PAW_WECHAT_MCH_SERIAL`、`PAW_WECHAT_PRIVATE_KEY_PATH`（PKCS#8 PEM）、`PAW_WECHAT_PUBLIC_KEY_PATH`、`PAW_WECHAT_PUBLIC_KEY_ID`、`PAW_WECHAT_API_V3_KEY` |
| 支付能力 | `PAW_WECHAT_JSAPI_ENABLED=true`、`PAW_WECHAT_H5_ENABLED=true`，分别开通后配置；`PAW_PUBLIC_BASE_URL` 为业务 HTTPS 地址 |
| 文件 | `PAW_STORAGE` 指向持久目录；MP4 校验需 `PAW_FFPROBE` 指向 ffprobe 可执行文件，缺少时拒绝上传视频而不假装校验成功 |

微信实现使用**微信支付公钥模式**，不是动态平台证书轮换模式。上线前需核验公众号/商户绑定、授权域名、支付域名、通知、公钥轮换、查单及异常退款行为。生产失败退款重试目前要求先确认平台允许重试；未确认的异常退款保持失败/处理中，不盲目创建新退款单号。

## 历史单体版本验证记录（重构前）

- `mvn package` 成功；22 项集成测试通过，0 失败、0 错误。
- 使用打包后的 JAR 监听本机8080，真实 HTTP 跑通：管理员登录 → 上传 PNG → 发布测试协议 → 商品上架 → 买家随机短信登录 → 结算/下单 → 模拟付款 → 买家短信确认 → 店主查码/核销 → 订单 completed。
- 停止并重启同一 JAR 后再次查询，订单仍为 completed、宠物仍为 sold，交付证据与协议快照保留。
- HTTP 冒烟脚本会等待61秒，遵守同手机号短信发送间隔；不会修改限流规则来通过测试。
- 原始实测基于 local/H2；2026-09-10 已补充 MySQL/Redis/Nginx 实测，见下文。微信、真实短信和视频环境尚未实测。

## 代码入口与验收

- `controller/`：按公开、买家和管理员拆分的明确接口方法；独立登录、文件及支付回调入口。
- `application/`：按业务模块组织用例；`BusinessOperationExecutor` 统一事务、权限和幂等。
- `repository/BusinessRepository`：业务数据访问门面，隐藏底层 Mapper。
- `controller/support/`：统一响应、身份适配和全局异常处理。
- [后端结构与阅读指南](ARCHITECTURE.md)：目录职责、调用链和扩展约定。
- `CatalogService`：商品、门店、协议与公开投影。
- `OrderService` / `AfterSaleService`：库存、快照、自提和退换货。
- `PaymentService` / `PaymentWorker`：支付事实、退款意图和提交后对账。
- `AuthService` / `TemporaryStore`：随机验证码、密码哈希、可撤销会话与限流。
- `FileService`：真实文件解码、用途与归属、私有临时链接。
- `MaintenanceService`：保守清理未引用临时文件，不删除订单证据。
- `CommerceIntegrationTest`：隔离库上的 HTTP、并发、越权、幂等、交付、退款和文件验证。

详见 [接口实现清单](API_STATUS.md)。本地测试不等于 MySQL/Redis 实例实测，也不等于第三方或生产环境验收。


## 历史 Docker 本机联调记录（2026-09-10）

当前买家入口：http://localhost/ ，管理入口：http://localhost/admin/ 。Nginx 提供构建后的页面并代理 `/api/` 到宿主机 Java 8080。MySQL 为 8.4（3306），Redis 为 8.2（宿主机6380）。

- `warmpaw`：项目运行库，专用账号仅获项目及测试库权限；原 H2 文件保留，未迁移。
- `warmpaw_test`：本次独立测试库，保留测试记录；再次运行完整测试应使用新的独立测试库，不能指向运行库。
- `backend/.env`：本地连接密钥，权限600且已被 Git 忽略，不打印或提交。
- 本次沿用 `data/local-admin-password.txt` 的管理员密码，账号 `admin`。
- 验证码仍在 `data/local-inbox/` 本机读取；不提供网页读取验证码接口。

后端重启：

```bash
cd '/Users/zhangquan/Documents/ChatGPT/宠物门店电商网站/backend'
sh scripts/start-docker-local.sh
```

该脚本启用 `local,docker`，连接真实 MySQL/Redis，保留本地模拟短信和付款。它为 Docker Desktop 访问绑定 `0.0.0.0:8080`，属于本机开发配置，不是生产部署配置。先停止占用8080的本项目旧进程再启动。生产仍使用 `prod`，不启用模拟功能。

前端本地 `.env.local` 已设置 `VITE_API_TARGET=http://127.0.0.1:8080`、`VITE_DEMO_MODE=true`、`VITE_JAVA_LOCAL=true`、`VITE_ADMIN_BASE=/admin/`。`VITE_JAVA_LOCAL` 只修正本地登录提示，不改变认证规则。源码修改后在 `frontend` 执行 `npm run build`，再将 `dist/buyer/` 内容更新到 Nginx 的 `/usr/share/nginx/html/warmpaw/`，将 `dist/admin/` 内容更新到其 `admin/` 子目录。站点配置保存在 `deploy/nginx-local.conf`，已安装到容器 `/etc/nginx/conf.d/warmpaw.conf`。源码变更不会自动更新 Nginx 静态页面。

本次验证：

- H2 原有22项测试通过；同一套22项业务测试在独立 MySQL 库及 Redis 15号库通过。
- 前端类型检查及双应用构建通过，2项 SSE 测试通过；构建仍有现存的大包体积提示。
- 直连8080和经过 Nginx 两次完整 HTTP 冒烟均通过，涵盖随机验证码登录、上传、协议发布、商品上架、下单、模拟支付、短信确认和交付完成。
- Playwright 验证后台登录、协议创建草稿、查看正文及未确认禁止发布；修复嵌套表单引起的刷新。手机390×844登录页及验证码刷新通过，所检查页面控制台无错误。
- 新页面“发布”按钮的实际点击被自动审批拦截（替换当前生效协议），尚未完成浏览器发布验证；草稿 `ui-test-20260910` 保留待审核。此前 HTTP 流程中的协议接口已验证，不等同于这项 UI 验证。

所有冒烟协议、商品和订单均为本地测试数据，不能用于真实经营；没有调用微信扣款或真实短信。AI/RAG仍未实现，原有AI入口暂保留。HTTPS、真实第三方服务、视频及生产容量仍待验收。


## 2026-09-11 关系表改造

运行库现使用独立业务表，已完成备份、副本演练及26条旧记录的逐字段迁移校验。旧订单/ID/协议内容保留，旧表名为 `resources_legacy`，后端不再使用它。参见 [数据库设计与验收](DATABASE.md)。AI/知识库仅建立表结构，未实现模型或向量检索业务。

## 历史单体结构重构验收（2026-09-11）

- 单一 Controller 和字符串路径分发已替换为按模块的显式接口；应用编排、业务规则、数据访问、DTO 和 HTTP 公共处理分层，详见 [阅读指南](ARCHITECTURE.md)。
- `mvn clean package` 通过：27 项 HTTP/业务集成测试、4 项关系存储/迁移测试，共 31 项。
- 新 JAR 已使用原有 local,docker 配置在 8080 启动；通过 Nginx 对比重启前后的首页、门店、分类、宠物列表、支付能力、当前协议，6 个接口的数据一致。
- 未登录访问后台宠物和个人资料返回 401，未知接口返回 404；买家页与管理页返回 200。
- 实际 MySQL 核对仍为 51 张表、3 条宠物和 3 条订单。本次没有执行建表或数据迁移脚本。
- 完整交易回归在隔离 H2 测试库运行；本轮实际 MySQL/Redis/Nginx 验证为启动和只读接口检查，不等同于第三方支付或生产性能验收。

### 到店预约短信

提交预约、店长确认、店长取消、预约过期和线下交付完成会通知预约联系电话。
本地 `app.mock-providers=true` 时仅写入 `data/local-inbox/sms-appointment-<订单ID>-<节点>.txt`，
JSON 中 `status=simulated` 表示模拟通知，绝不调用短信平台；买家自行取消不触发店长取消短信。
真实模式复用阿里云短信凭据与签名，必须额外配置以下独立模板，不回退到验证码模板：

| 节点 | 环境变量 | 模板参数 |
| --- | --- | --- |
| 提交预约 | `PAW_SMS_APPOINTMENT_SUBMITTED_TEMPLATE` | `orderId`、`visitAt` |
| 店长确认 | `PAW_SMS_APPOINTMENT_CONFIRMED_TEMPLATE` | `orderId`、`visitAt` |
| 店长取消 | `PAW_SMS_APPOINTMENT_CANCELLED_TEMPLATE` | `orderId`、`visitAt`、`reason` |
| 预约过期 | `PAW_SMS_APPOINTMENT_EXPIRED_TEMPLATE` | `orderId`、`visitAt` |
| 交付完成 | `PAW_SMS_APPOINTMENT_COMPLETED_TEMPLATE` | `orderId`、`visitAt` |

`visitAt` 使用上海时区的 `yyyy-MM-dd HH:mm`。短信平台模板须与参数对应。
通知先与预约状态写入同一事务的 `appointment_sms_outbox`，提交后立即发送；失败按指数退避自动重试，
最多八次，进程重启后继续。多实例使用两分钟租约避免同时发送，后台工作台显示等待重试和最终失败数量。
缺少配置或平台拒绝不会伪装成功，预约操作仍然生效。上线前先执行
`deploy/appointment-sms-outbox-migration.sql`。阿里云不接受业务幂等键，因此平台受理后、成功状态落库前若进程崩溃，
仍可能产生重复短信；真实手机送达与该边界需上线联调验证。

### 号码认证短信登录（Dypnsapi）

买家登录和微信绑定调用 `SendSmsVerifyCode`，使用赠送签名与模板。
默认签名为 `恒创联众`、模板为 `100001`；可通过 `PAW_PNVS_SIGN_NAME`、
`PAW_PNVS_LOGIN_TEMPLATE` 覆盖。参数为 `code`（后端生成的六位数字）和 `min=5`。
验证码由本项目 Redis 校验、限流并一次性消费，不调用云端 `CheckSmsVerifyCode`。
凭据优先读取成对的 `PAW_SMS_ACCESS_KEY_ID` / `PAW_SMS_ACCESS_KEY_SECRET`，
两者均为空时读取 `OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET`。
凭据所属 RAM 身份需有 `dypns:SendSmsVerifyCode` 权限；仅 OSS 权限不足。

真实联调时，在 admin-server 进程环境中设置 `PAW_MOCK_PROVIDERS=false`，
并保持 Redis 可用；local profile 默认模拟，但允许该变量显式覆盖。
买家前端以 `VITE_DEMO_MODE=false` 启动并指向 Java 网关。
短信发送响应 `status=accepted` 仅表示平台受理，不代表运营商已送达；
`status=simulated` 表示写入开发收件箱。密钥只配置在后端，不能加入 `VITE_*` 变量。
预约通知及交付确认继续使用原 Dysmsapi 和各自模板。
图形验证码当前仍由后端本地生成，尚未接入阿里云验证码产品。

通过 `scripts/run-service.sh admin-server` 启动时，登录服务默认真实模式；仅需模拟时显式设置 `PAW_AUTH_MOCK_PROVIDERS=true`。订单服务的模拟配置不受此登录启动设置影响。
