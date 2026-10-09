# 暖爪宠物门店

Vue 买家端与店主管理后台、Spring Cloud 业务服务、Python LangChain 智能助手组成的单店项目。
当前交易流程为 **预约到店 → 店长确认 → 到店线下收款与交付**，新预约不启用线上支付。
AI 提供知识问答和业务查询，不修改订单、价格或库存。

项目现用于个人练习与简历展示，不正式运营。门店为“茸茸星球”，20 只示例宠物与健康、检疫及协议资料均明确标注模拟。演示账号、隔离环境启动与讲解顺序见 [演示指南](docs/DEMO_GUIDE.md)，材料生成见 [模拟资料](docs/DEMO_MATERIALS.md)。

新到店预约的售后由门店线下处理，不开放在线退款或健康申诉；保留的历史订单支持诊断附件和在线售后审核。这两条流程在演示中分开验证。

## 主要功能

- 买家端：宠物浏览与筛选、宠物档案、到店预约、本人订单与售后、个人资料及账号安全。
- 管理后台：宠物与检疫材料、预约确认及线下交付、售后处理、门店设置、协议版本与管理员改密。
- 登录：短信登录及自动注册、手机号或用户名密码登录、首次设置／找回密码、可选微信登录；
  买家和管理员登录均接入阿里云弹出式图形认证。
- 智能助手：DeepSeek 流式回复、中文知识检索与来源引用、在售宠物推荐、本人预约查询、会话历史与反馈。
- 知识运营：草稿审核发布、CSV/Excel 批量导入、导入错误记录、失败索引重试与 AI 统计。
- 文件与通知：图片／视频上传、私有证明鉴权、可选阿里云 OSS 存储、预约短信队列及失败重试。

AI 使用 DeepSeek API 与 RAG；中文嵌入模型为 `BAAI/bge-small-zh-v1.5`，当前没有模型微调流程。
导入的知识先保存为草稿，逐条审核发布并完成索引后才参与检索。

## 项目结构

| 目录／文件 | 用途 |
| --- | --- |
| `frontend/buyer-web/` | Vue 3 + TypeScript + Vite 买家端 |
| `frontend/admin-web/` | Vue 3 + Element Plus 管理后台 |
| `frontend/shared/` | 双端请求、类型与 SSE 处理 |
| `backend/` | JDK 21、Spring Boot 4、Spring Cloud、Nacos、OpenFeign |
| `backend/common/` | 业务逻辑、持久化、安全与第三方服务接入 |
| `backend/order-server/` | 订单、预约与短信通知队列 |
| `backend/admin-server/` | 认证、宠物目录、门店及管理接口 |
| `backend/gateway-server/` | API 路由与 AI 转发 |
| `backend/integration-tests/` | 隔离数据库的业务回归测试 |
| `ai-service/` | Python 3.11、FastAPI、LangChain、SQLite 知识与会话 |
| `compose.yaml` | 本机完整容器部署 |
| `compose.production.yaml` | 公网 Caddy HTTPS 入口 |
| `scripts/`、`docs/` | 配置初始化、备份恢复、上线检查及部署文档 |

## Docker Compose 快速启动

推荐使用根目录 `compose.yaml`。八项常驻服务为 `web`、`gateway-server`、`admin-server`、
`order-server`、`ai-service`、`mysql`、`redis`、`nacos`；构建在容器内完成，不依赖宿主机 JDK、Node.js 或 uv。
需要 Docker Desktop 或 Docker Engine + Compose v2；配置初始化和备份脚本另需 Python 3。
公网覆盖配置要求 Compose **2.24.4+**。首次构建及知识索引需要联网下载依赖和模型。

以下命令均从项目根目录执行。

### 首次配置

```sh
# 仅在根目录尚无 .env 时执行；已有文件不会被覆盖。
python3 scripts/init-compose-env.py
```

脚本生成缺少的内部密码，也会读取本机 `backend/.env`、`ai-service/.env` 和环境变量中的同名配置。
然后在本机编辑根目录 `.env`，按 [.env.example](.env.example) 填写实际参数：

| 配置 | 用途 |
| --- | --- |
| `MYSQL_ROOT_PASSWORD`、`PAW_DB_PASSWORD`、`PAW_REDIS_PASSWORD`、`NACOS_AUTH_*` | 数据库、缓存与注册中心 |
| `PAW_ADMIN_PASSWORD` | 新管理员初始密码；默认用户名 `admin`，不重置已有账号 |
| `PAW_CAPTCHA_APP_ID`、`PAW_CAPTCHA_APP_KEY` | 阿里云号码认证控制台「图形认证方案」的 appId、appKey |
| `PAW_SMS_ACCESS_KEY_ID`、`PAW_SMS_ACCESS_KEY_SECRET`、`PAW_PNVS_*` | 登录／密码重置短信；未配置专用凭据时成对使用 OSS 凭据 |
| `PAW_SMS_SIGN_NAME`、`PAW_SMS_APPOINTMENT_*_TEMPLATE` | 预约提交、确认、取消、过期、交付五类通知 |
| `DEEPSEEK_API_KEY`、`DEEPSEEK_MODEL`、`DEEPSEEK_API_BASE` | AI 模型调用，默认 `deepseek-chat` |
| `PAW_OSS_ENABLED`、`PAW_OSS_BUCKET`、`PAW_OSS_ENDPOINT`、`OSS_ACCESS_KEY_*` | 可选私有 OSS 存储 |
| `PAW_WECHAT_APP_ID`、`PAW_WECHAT_APP_SECRET`、`PAW_WECHAT_OAUTH_REDIRECT` | 可选微信授权；未完整配置时隐藏入口 |

`.env` 只保存在本机，不提交或分享；图形认证 appKey 不得放入 `VITE_` 变量。
图形认证未配置时登录会明确失败，不回退旧图片验证码。配置与真实验收见 [登录人机验证](docs/GRAPH_CAPTCHA.md)。
Compose 固定使用真实服务模式，未配置的第三方能力不会模拟成功。

### 构建与访问

```sh
# 只验证配置，不打印解析后的密钥。
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 600
docker compose ps
```

| 入口 | 默认地址 |
| --- | --- |
| 买家网站／登录 | <http://localhost:8090/> · <http://localhost:8090/login> |
| 智能助手 | <http://localhost:8090/ai> |
| 管理后台／登录 | <http://localhost:8090/admin/> · <http://localhost:8090/admin/login> |

可用根目录 `.env` 中的 `WEB_PORT` 修改端口。默认只绑定 `127.0.0.1`；Java、AI、MySQL、Redis、
Nacos 仅在容器网络内访问。新库仅在空 MySQL 数据卷初始化；已有数据库升级前先备份并核对增量迁移。
容器健康不代表真实短信送达、AI 回复或公网运营验收完成。

### 日常启停、更新与备份

```sh
# 停止／恢复现有容器，保留数据。
docker compose stop
docker compose start

# 查看服务日志与 Nginx 配置。
docker compose logs --tail=100 gateway-server admin-server order-server ai-service
docker compose exec -T web nginx -t

# 已有环境更新：先构建，再备份，完成所需迁移后更新容器。
docker compose build
python3 scripts/backup-compose.py
docker compose up -d --no-build --wait --wait-timeout 600
```

MySQL、Redis、上传文件、AI 数据与模型缓存、Nacos 使用持久卷。不要执行 `docker compose down -v`，
它会删除数据卷。重建镜像不会自动升级已有数据库。
备份脚本短暂停止写入应用，备份 MySQL、上传文件与 AI 数据，再恢复原本运行的应用；
只有备份清单 `manifest.json` 中 `complete=true` 才表示完整。恢复须先在独立项目演练。
详细数据卷、OSS、排查及恢复步骤见 [Docker 交付说明](docs/DOCKER_DELIVERY.md)。

## 登录与数据库升级

点击“获取短信验证码”时弹出图形认证，通过服务端二次校验后才发送短信；短信登录提交时不重复弹窗。
买家密码登录与管理员登录的按钮、Enter 共用提交流程，先完成图形认证再校验账号密码。
取消弹窗不提交业务请求；验证失败后需重新验证。

买家可设置用户名与密码；密码设置／修改／重置共用滚动 7 天限制，用户名修改为滚动 3 天限制。
密码使用随机盐与 PBKDF2-HMAC-SHA256 哈希，成功更新后撤销该账号会话。
完整规则及迁移步骤见 [账号安全](docs/ACCOUNT_SECURITY.md)；其中验证码流程以
[登录人机验证](docs/GRAPH_CAPTCHA.md) 的买家和管理员统一方案为准。

旧数据库按当前表结构和迁移记录确定是否需要执行以下脚本：

- 预约及短信队列：`backend/deploy/appointment-migration.sql`、
  `backend/deploy/appointment-sms-outbox-migration.sql`，见 [预约部署说明](backend/deploy/APPOINTMENTS.md)。
- 用户名与密码安全：`backend/deploy/account-security-migration.sql`，迁移记录为 `account-security-v1`；
  已执行的数据库不要重复执行。

禁止向已有数据库重新执行 `schema.sql` 或重复导入旧备份。阿里云图形认证接入不修改数据库结构。

## 宿主机开发

需要 JDK 21、Maven、Node.js 22.12+、Python 3.11、uv 和 Docker。
先准备独立 MySQL、Redis 及数据库结构；Java 连接和图形认证配置放在 `backend/.env`，
AI 配置参照 `ai-service/.env.example` 写入 `ai-service/.env`。不要覆盖已有配置。
数据库和 IDEA 配置见 [后端说明](backend/README.md)。

```sh
# 首次安装依赖并构建。
mvn -f backend/pom.xml package
npm --prefix frontend ci
(cd ai-service && uv sync --locked --python 3.11)

# Java 脚本会启动独立开发 Nacos；AI 使用后台进程。
sh backend/scripts/start-microservices.sh
ai-service/.venv/bin/python ai-service/manage.py start
ai-service/.venv/bin/python ai-service/manage.py status
```

分别在两个终端从项目根目录启动前端：

```sh
VITE_API_TARGET=http://127.0.0.1:8080 VITE_DEMO_MODE=false npm --prefix frontend run dev:buyer -- --port 5180 --strictPort
```

```sh
VITE_API_TARGET=http://127.0.0.1:8080 VITE_DEMO_MODE=false VITE_ADMIN_BASE=/admin/ npm --prefix frontend run dev:admin -- --port 5181 --strictPort
```

| 服务 | 开发地址 |
| --- | --- |
| 买家／智能助手 | <http://127.0.0.1:5180/> · <http://127.0.0.1:5180/ai> |
| 管理后台 | <http://127.0.0.1:5181/admin/> |
| 网关健康 | <http://127.0.0.1:8080/actuator/health> |
| 订单／管理服务健康 | <http://127.0.0.1:8081/actuator/health> · <http://127.0.0.1:8082/actuator/health> |
| AI 健康 | <http://127.0.0.1:8083/health> |
| Nacos 控制台 | <http://127.0.0.1:8088/> |

Java 启动脚本中的认证服务默认调用真实短信；仅在隔离开发中显式设置 `PAW_AUTH_MOCK_PROVIDERS=true`
才将短信验证码写入 `backend/data/local-inbox/`。图形认证仍需有效配置，不能使用演示万能码。
`npm --prefix frontend run dev` 会同时启动 Node 演示后端，当前阿里云认证联调应使用上面的独立前端命令。

前端按 Ctrl+C 停止；后台服务使用：

```sh
ai-service/.venv/bin/python ai-service/manage.py stop
sh backend/scripts/stop-microservices.sh
```

Java 日志在 `backend/data/logs/`；AI 默认日志为 `ai-service/data/logs/ai-service.log`，
PID 为 `ai-service/data/run/ai-service.pid`，数据库为 `ai-service/data/ai.sqlite3`。
自定义 `AI_DB_PATH` 后，AI 日志及 PID 随数据库父目录移动，启停应保持相同配置。
停止应用不会删除数据，也不会停止 MySQL、Redis 或开发 Nacos 容器。

## 构建与验证

```sh
# Java 测试与各微服务打包。
mvn -f backend/pom.xml package
# 前端测试；build 包含类型检查和双端生产构建。
npm --prefix frontend test
npm --prefix frontend run build
# Python 测试须在 ai-service 目录运行。
(cd ai-service && .venv/bin/python -m pytest -q)
docker compose config --quiet
```

Java 测试默认使用隔离 H2；AI 测试使用临时数据与替代模型，部分测试需要监听本机临时端口。
自动测试不替代自有图形认证成功、真实短信送达、DeepSeek 回复及生产数据库验收。
前端输出为 `frontend/dist/buyer/` 和 `frontend/dist/admin/`，容器 Nginx 已配置网关代理与 SSE 支持。

## 公网运营与文档

公网部署使用 `compose.yaml` + `compose.production.yaml`，由 Caddy 提供 80/443 和自动 HTTPS，
并移除本机 8090 入口。先准备真实域名、证书邮箱、门店与宠物资料、正式协议、已审核短信签名及五个预约模板。
上线步骤、只读检查和人工验收见 [正式运营部署](docs/PRODUCTION.md)；资料填写见
[运营资料准备](docs/运营资料准备.md)。

当前运行状态应通过 `docker compose ps` 和实际业务验收确认。历史部署、测试与第三方接入证据保存在
[交付验收记录](docs/DELIVERY_STATUS.md) 和 [图形认证实测记录](docs/GRAPH_CAPTCHA.md)，按各记录日期与范围阅读。
本机容器可用不代表公网运营验收完成；短信平台受理也不代表手机收到。

更多说明：[微服务结构](backend/ARCHITECTURE.md) · [接口实现状态](backend/API_STATUS.md) ·
[AI 与知识库](ai-service/README.md) · [前端与演示模式](frontend/README.md)。
