# 暖爪宠物门店后端

按根目录《宠物门店电商网站接口文档》v0.9 实现非 AI 业务。Java 21、Spring Boot 4.0.7、MyBatis、MySQL 8、Redis；本地开发使用 H2 文件库和随机验证码收件箱，无需先安装 MySQL/Redis。AI 会话、问答、知识库、向量索引和 AI 耗时埋点没有实现。

## 启动与验证

在 `backend` 目录执行：

```bash
mvn clean test
mvn package
java -jar target/warmpaw-backend-1.0.0.jar
```

默认监听 `http://127.0.0.1:8080`，接口前缀 `/api/v1`。默认 profile 为 `local`，数据在 `backend/data/`。当前项目已切换MySQL，请使用下文 `start-docker-local.sh`；原H2旧文件未迁移，启动检查会阻止静默初始化空业务数据。默认测试使用独立H2，只有显式指定测试库参数才连接MySQL。

首次启动预置 `admin`。如果没有设置 `PAW_ADMIN_PASSWORD`，本地生成随机密码保存在 `data/local-admin-password.txt`（仅当前用户可读）；不会在日志打印密码。指定初始密码至少12位；已有账号不会因重启覆盖密码。

```bash
curl http://127.0.0.1:8080/api/v1/home
python3 scripts/local_smoke.py
```

`local_smoke.py` 通过真实 HTTP 创建**醒目标记 TEST ONLY 的模拟材料**、测试协议、测试商品，并完成模拟付款与现场交付，数据持久保留。脚本不打印密码、Token 或自提码。脚本创建的协议和材料不能用于真实经营。测试脚本需要从 `backend` 启动服务，并使用首次随机管理员密码；如自行配置了初始密码，请通过接口手动联调。

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
VITE_DEMO_MODE=true
```

写入 `frontend/.env.local` 后重启 Vite。开发模式的模拟支付路径已兼容现有前端。原 Node 演示服务提示的固定 `DEMO` / `123456` **不适用于此后端**；请填写图片中的随机码，并从本机开发收件箱取短信码。原前端仍保留 AI 导航，但此后端不提供对应功能。后台已提供 `/admin/agreements` 协议管理页，可创建、查看和发布版本。

## MySQL 与 Redis

生产连接独立数据库；不要把建表脚本直接执行到不属于本项目的现有库。先创建 UTF-8 的专用 MySQL 8 数据库，再执行 `src/main/resources/schema.sql`。

```bash
export SPRING_PROFILES_ACTIVE=prod
export PAW_DB_URL='jdbc:mysql://127.0.0.1:3306/warmpaw?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai'
export PAW_DB_USERNAME='warmpaw'
# PAW_DB_PASSWORD、PAW_ADMIN_PASSWORD 通过终端环境/IDE/部署密钥设置，不提交源码。
export PAW_REDIS_HOST='127.0.0.1'
export PAW_REDIS_PORT='6379'
java -jar target/warmpaw-backend-1.0.0.jar
```

Redis 密码用 `PAW_REDIS_PASSWORD`，生产通过 Redis 执行临时凭证、原子消费和限流。本地内存验证码会在重启后失效，订单、支付和会话凭证摘要仍持久化。

数据库现已按PRD和接口文档拆为 `pets`、`users`、`orders`、`payments`、`refunds`、`after_sales` 等独立领域表；图片、标签、检疫材料和轮播图单独关联，历史订单快照保留JSON。宠物筛选/排序/分页已下推SQL。`pet_occupancy`外键关联pets/orders，`business_lock`继续保护单店写事务。旧数据已显式迁移，旧表保留为resources_legacy，仅作迁移前参考。详细表清单、字段、备份和回退说明见 [数据库设计](DATABASE.md)。其他部分列表仍在内存筛选，尚未进行生产压测。

## 第三方接入配置

当前按用户要求优先完成**本地模拟验证**。接入代码已提供，但没有真实资质/密钥，不能宣称完成真实短信、微信授权、扣款或退款联调。

| 模块 | 环境变量 |
|---|---|
| 阿里云短信 | `PAW_SMS_ACCESS_KEY_ID`、`PAW_SMS_ACCESS_KEY_SECRET`、`PAW_SMS_SIGN_NAME`、`PAW_SMS_LOGIN_TEMPLATE`、`PAW_SMS_CONFIRM_TEMPLATE`；模板参数为 `code` |
| 微信网页授权 | `PAW_WECHAT_APP_ID`、`PAW_WECHAT_APP_SECRET`、`PAW_WECHAT_OAUTH_REDIRECT`（固定 HTTPS 前端回调页） |
| 微信 API v3 | `PAW_WECHAT_MCH_ID`、`PAW_WECHAT_MCH_SERIAL`、`PAW_WECHAT_PRIVATE_KEY_PATH`（PKCS#8 PEM）、`PAW_WECHAT_PUBLIC_KEY_PATH`、`PAW_WECHAT_PUBLIC_KEY_ID`、`PAW_WECHAT_API_V3_KEY` |
| 支付能力 | `PAW_WECHAT_JSAPI_ENABLED=true`、`PAW_WECHAT_H5_ENABLED=true`，分别开通后配置；`PAW_PUBLIC_BASE_URL` 为业务 HTTPS 地址 |
| 文件 | `PAW_STORAGE` 指向持久目录；MP4 校验需 `PAW_FFPROBE` 指向 ffprobe 可执行文件，缺少时拒绝上传视频而不假装校验成功 |

微信实现使用**微信支付公钥模式**，不是动态平台证书轮换模式。上线前需核验公众号/商户绑定、授权域名、支付域名、通知、公钥轮换、查单及异常退款行为。生产失败退款重试目前要求先确认平台允许重试；未确认的异常退款保持失败/处理中，不盲目创建新退款单号。

## 本次已执行验证

- `mvn package` 成功；22 项集成测试通过，0 失败、0 错误。
- 使用打包后的 JAR 监听本机8080，真实 HTTP 跑通：管理员登录 → 上传 PNG → 发布测试协议 → 商品上架 → 买家随机短信登录 → 结算/下单 → 模拟付款 → 买家短信确认 → 店主查码/核销 → 订单 completed。
- 停止并重启同一 JAR 后再次查询，订单仍为 completed、宠物仍为 sold，交付证据与协议快照保留。
- HTTP 冒烟脚本会等待61秒，遵守同手机号短信发送间隔；不会修改限流规则来通过测试。
- 原始实测基于 local/H2；2026-09-10 已补充 MySQL/Redis/Nginx 实测，见下文。微信、真实短信和视频环境尚未实测。

## 代码入口与验收

- `web/ApiController`：HTTP、统一响应、身份和原始微信通知。
- `service/BusinessService`：接口编排、事务和幂等。
- `CatalogService`：商品、门店、协议与公开投影。
- `OrderService` / `AfterSaleService`：库存、快照、自提和退换货。
- `PaymentService` / `PaymentWorker`：支付事实、退款意图和提交后对账。
- `AuthService` / `TemporaryStore`：随机验证码、密码哈希、可撤销会话与限流。
- `FileService`：真实文件解码、用途与归属、私有临时链接。
- `MaintenanceService`：保守清理未引用临时文件，不删除订单证据。
- `CommerceIntegrationTest`：隔离库上的 HTTP、并发、越权、幂等、交付、退款和文件验证。

详见 [接口实现清单](API_STATUS.md)。本地测试不等于 MySQL/Redis 实例实测，也不等于第三方或生产环境验收。


## Docker 本机联调（2026-09-10 已验证）

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
