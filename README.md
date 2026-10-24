# 暖爪宠物门店

宠物门店的买家网站、店主管理后台、Java 微服务后端、Python 智能助手与原生微信小程序。

> 本文的命令、路径与访问地址已于 2026-10-24 在本机实测核对；仍未确定处保留「待补」并注明原因。
> 后续新增的脚本或目录若本文未提及，请勿默认存在。

## 项目用途

面向单店宠物销售场景的一套练习项目，当前用于个人练习与简历展示，不正式运营。门店名称为「茸茸星球」，示例宠物及健康、检疫、协议资料均为模拟数据。

**核心业务闭环**：浏览宠物 → 提交到店预约 → 店长确认 → 到店线下收款与交付。

**覆盖四个端**：

| 端 | 主要能力 |
| --- | --- |
| 买家网站 | 宠物浏览与筛选、宠物档案、到店预约、本人订单与售后、个人资料与账号安全 |
| 管理后台 | 宠物与检疫材料、预约确认及线下交付、售后处理、门店设置、协议版本、知识库运营、AI 统计 |
| 智能助手 | 猫狗知识问答与来源引用、在售宠物推荐、本人预约查询、多轮会话、流式回复、赞踩反馈 |
| 微信小程序 | 首页、选宠、动物详情、收藏、我的、微信登录、网站扫码确认（共 7 个页面，以 `app.json` 为准） |

**明确不做的事**：

- 新预约不启用线上支付，售后由门店线下处理；在线退款与健康申诉仅保留给历史订单。
- AI 只提供知识问答与业务查询，不提交或取消预约、不付款、不退款、不修改价格与库存。
- 不做疾病诊断、处方与用药剂量建议。

### 目录与子项目

| 目录 | 内容 |
| --- | --- |
| `frontend/buyer-web/` | Vue 3 + TypeScript + Vite 买家端 |
| `frontend/admin-web/` | Vue 3 + Element Plus 管理后台 |
| `frontend/shared/` | 双端共用的请求、类型与 SSE 处理 |
| `frontend/server/demo.mjs` | 前端自带的 Node 演示后端（仅演示，不连真实服务） |
| `backend/common/` | 业务逻辑、持久化、安全与第三方服务接入 |
| `backend/order-server/` | 订单、预约与短信通知队列 |
| `backend/admin-server/` | 认证、宠物目录、门店及管理接口 |
| `backend/gateway-server/` | API 路由与 AI 转发 |
| `backend/integration-tests/` | 隔离数据库上的业务回归测试 |
| `backend/deploy/` | 建表后的增量迁移 SQL 与部署说明 |
| `ai-service/` | Python 3.11 + FastAPI + LangChain + DeepSeek，SQLite 存知识与会话 |
| `mini-program/` | 原生微信小程序（WXML / WXSS / JavaScript） |
| `compose.yaml` | 本机完整容器部署 |
| `compose.production.yaml` | 公网 Caddy HTTPS 入口 |
| `compose.cloud-demo.yaml` | 云服务器演示环境覆盖配置 |
| `deploy/` | `Caddyfile` 与演示站点配置 |
| `scripts/` | 配置初始化、备份恢复、上线检查与验收脚本 |
| `docs/` | 部署、登录、演示与运营文档 |

## 安装步骤

### 1. 准备前置依赖

| 依赖 | 说明 |
| --- | --- |
| Docker Desktop 或 Docker Engine + Compose v2 | 容器方式运行必需；公网覆盖配置要求 Compose 2.24.4+ |
| Python 3 | 配置初始化与备份脚本使用 |
| JDK 25 + Maven | 仅在宿主机运行或构建 Java 服务时需要 |
| Node.js 22.12+ | 仅在宿主机运行或构建前端时需要 |
| Python 3.11 + uv | 仅在宿主机单独运行 AI 服务时需要 |
| 微信开发者工具 | 仅小程序联调时需要 |

版本以本机实测为准（2026-10-24 核对）：

| 依赖 | 实测版本 |
| --- | --- |
| JDK | Temurin 25.0.4.1 |
| Maven | 3.9.16 |
| Node.js | 22.22.2 |
| Python | 3.13.12（脚本用；AI 服务要求 3.11–3.13） |
| uv | 0.11.19 |
| Docker | 29.8.2（含 Compose v2） |

安装方式按各项目官方文档，**不要照抄网上过期的 Homebrew 包名**（JDK 25 的 cask 名称与旧版本不同）：

```sh
# JDK 25（Temurin）  https://adoptium.net/temurin/releases/?version=25
# Maven              https://maven.apache.org/install.html
# Node.js 22+        https://nodejs.org/  （建议用 nvm 或 fnm 管理多版本）
# Python 3.11–3.13   https://www.python.org/downloads/
# uv                 https://docs.astral.sh/uv/getting-started/installation/
# Docker Desktop     https://docs.docker.com/desktop/install/mac-install/
```

安装完成后逐条核对，版本低于下表要求时后续命令会失败：

```sh
java -version && mvn -v && node -v && python3 -V && uv --version && docker --version
```

### 2. 获取代码

```sh
git clone git@github.com:zqq12121/pet-store.git
cd pet-store
```

SSH 未配置时改用 HTTPS：`git clone https://github.com/zqq12121/pet-store.git`。

### 3. 安装依赖

容器方式在构建时完成依赖安装，不需要在宿主机执行下列步骤；宿主机开发时才需要。

以下命令都在**项目根目录**执行，每行结尾回到根目录，可单独挑一条跑：

```sh
# Java：打包三个服务并运行 122 项测试，使用内存 H2，不需要数据库（本机实测约 13 秒）
cd backend && mvn package && cd ..

# 前端：必须用 npm ci 按 package-lock.json 安装
cd frontend && npm ci && cd ..

# AI 服务：按 uv.lock 创建虚拟环境
cd ai-service && uv sync && cd ..
```

> 注意：前端统一使用 npm，锁文件为 `frontend/package-lock.json`，`frontend/Dockerfile` 用 `npm ci` 安装。不要再用 pnpm，否则会生成第二套锁文件导致依赖版本漂移。

### 4. 初始化配置

根目录 `.env`、`backend/.env`、`ai-service/.env` 三处配置均由本机保管，不提交、不外发。模板文件为根目录 `.env.example`，AI 服务的模板为 `ai-service/.env.example`。

```sh
# 生成根目录 .env：自动填入随机密码与 Nacos token，权限 600。
# 已存在则直接退出且不覆盖，改配置请手工编辑该文件。
python3 scripts/init-compose-env.py

# AI 服务本地配置
cp ai-service/.env.example ai-service/.env
```

`backend/.env` **没有模板文件**，容器方式下也不需要它——compose 会把根目录 `.env` 的变量注入三个 Java 容器。只有「方式二：宿主机分服务启动」才需要手工创建 `backend/.env`，变量名参照根目录 `.env.example`。

配置项含义见下文「关键配置说明」。

### 5. 初始化数据

容器方式**不需要手动建库**：`compose.yaml` 已把 `backend/common/src/main/resources/schema.sql` 挂载到 MySQL 的 `docker-entrypoint-initdb.d`，空数据卷首次启动时自动建出 60 张表。已有数据卷不会再执行该脚本。

```sh
# 导入示例宠物档案：需要服务已启动，脚本走 http://localhost/api/v1，导入后保持未上架
python3 backend/scripts/seed_pet_examples.py
```

**已有数据库的增量迁移**：按 `backend/deploy/` 下的文件执行，执行后核对 `schema_migrations` 表的记录。

```sh
# 示例：走 compose 暴露的 13306 端口（仅本机）
mysql -h 127.0.0.1 -P 13306 -u warmpaw -p warmpaw < backend/deploy/account-security-migration.sql
```

预约相关的两个文件有明确先后要求，见 `backend/deploy/APPOINTMENTS.md`：先 `appointment-migration.sql`，再 `appointment-sms-outbox-migration.sql`。其余迁移文件未在文件头标注编号，执行前请对照 `schema_migrations` 现有记录确认顺序。

> 已有数据库禁止重复执行完整建表脚本，只能按需执行 `backend/deploy/` 下的增量迁移，并核对迁移记录。

## 运行方式

### 方式一：容器一键启动（推荐）

常驻服务共 8 个：`web`、`gateway-server`、`admin-server`、`order-server`、`ai-service`、`mysql`、`redis`、`nacos`。构建在容器内完成，不依赖宿主机的 JDK、Node.js 或 uv。

```sh
# 校验 compose 语法与必填变量（不启动任何容器）
docker compose config -q

# 首次构建并启动全部 8 个服务
docker compose up -d --build

# 查看状态：等 8 个服务都变成 healthy
docker compose ps
```

| 入口 | 地址 |
| --- | --- |
| 买家网站 | http://127.0.0.1:8090/ |
| 买家登录 | http://127.0.0.1:8090/login |
| 智能助手 | http://127.0.0.1:8090/ai |
| 管理后台 | http://127.0.0.1:8090/admin/ |
| 管理后台登录 | http://127.0.0.1:8090/admin/login |

端口取自根目录 `.env` 的 `WEB_PORT`（默认 8090）。Nginx 把 `/` 指向买家端、`/admin/` 指向管理后台、`/api/` 反向代理到网关（见 `frontend/deploy/nginx.conf`）。

默认只绑定本机回环地址，Java、AI、MySQL、Redis、Nacos 仅在容器网络内访问。首次构建与知识索引需要联网下载依赖与模型。

### 方式二：宿主机分服务启动

适用于本地改代码调试，需要自行准备 MySQL、Redis 与 Nacos。

```sh
# 1. 先打包：脚本会检查 backend/*/target/*-exec.jar 是否存在，缺一个就退出
cd backend && mvn package && cd ..

# 2. 启动 Nacos 容器 + 三个微服务（脚本内部会等注册中心就绪，最多 120 秒）
./backend/scripts/start-microservices.sh

# 3. AI 服务：二选一
cd ai-service && ./run.sh                    # 前台运行（exec 直接替换进程），Ctrl+C 停止
cd ai-service && python3 manage.py start     # 后台托管；stop / status 同理

# 4. 前端，两个终端各跑一个
cd frontend && npm run dev:buyer
cd frontend && npm run dev:admin
```

注意三点：

- 启动脚本会先检查 8080 / 8081 / 8082 是否被占用，占用则直接退出，不会杀其他进程。
- 这条路线需要本机已有 MySQL、Redis 和 `backend/.env`；容器方式不需要。
- `npm run dev` 会同时拉起 8787 的 Node 演示后端，联调真实接口时**不要用**它，只用 `dev:buyer` / `dev:admin`。

| 服务 | 开发地址 |
| --- | --- |
| 买家端 / 智能助手 | http://127.0.0.1:5173/ |
| 管理后台 | http://127.0.0.1:5174/ |
| 网关 | http://127.0.0.1:8080 |
| 订单服务 | http://127.0.0.1:8081 |
| 管理服务 | http://127.0.0.1:8082 |
| AI 服务 | http://127.0.0.1:8083 |
| Nacos 控制台 | http://127.0.0.1:8088（仅宿主机启动方式发布；容器方式不对外暴露） |

前端连接真实后端时，需把接口目标指向网关并关闭演示模式；`frontend` 的 `dev` 脚本会同时拉起 Node 演示后端，联调真实接口时不要使用该脚本。

### 方式三：微信小程序

```sh
cd mini-program
npm run check    # WXML/WXSS 实际编译 + JavaScript/JSON + 页面注册校验（本机实测 7 个页面）
npm test         # node:test 单元测试
npm run release  # 生成发布包
```

微信开发者工具中选择 `mini-program/` 目录导入。`config.js` 默认 `demo: true`（本机演示、不调用真实微信登录）；切换真实接口需要在 `config.js` 填写 `apiBase` 与 `assetOrigin`，并在微信后台配置 HTTPS 合法域名。

### 启动、停止与日志

```sh
# 停止但保留容器与数据卷
docker compose stop

# 停止并删除容器（数据卷仍保留，数据不会丢）
docker compose down

# 查看日志
docker compose logs -f --tail=200 gateway-server
docker compose logs -f --tail=200 order-server
docker compose logs -f --tail=200 ai-service

# 停止宿主机方式启动的三个 Java 服务
./backend/scripts/stop-microservices.sh

# 校验 Nginx 配置（容器内执行）
docker compose exec web nginx -t
```

**不要执行 `docker compose down -v`**，见「常见问题」Q5。

| 日志 / 数据 | 位置 |
| --- | --- |
| Java 日志 | `backend/data/logs/` |
| 本地模拟短信收件箱 | `backend/data/local-inbox/` |
| AI 日志 | `ai-service/data/logs/ai-service.log`（随 `AI_DB_PATH` 的父目录变化） |
| AI 进程号 | `ai-service/data/run/ai-service.pid` |
| AI 数据库 | `ai-service/data/ai.sqlite3` |

## 使用示例

以下为业务操作路径，具体地址与账号由最终环境决定。

### 买家端：完成一次到店预约

1. 打开买家网站，输入手机号获取短信验证码；点击「获取短信验证码」时会弹出图形认证，通过后才会真正下发短信。
2. 首次登录的手机号自动注册；也可使用已设置的用户名与密码登录。
3. 在宠物列表按分类、品种、价格筛选，进入详情查看档案与检疫材料。
4. 选择宠物并提交预约，选择到店时间、填写联系方式、确认协议版本。
5. 在「我的」中查看预约状态：待确认 → 已确认 → 已完成。
6. 店长确认后到店，由门店线下收款并交付；交付完成后订单状态变为已完成。

### 管理后台：确认预约并交付

1. 打开管理后台登录页，输入管理员账号与密码（管理员走密码校验，不做图形认证）。
2. 在工作台查看待确认预约与短信重试失败数量。
3. 进入预约详情确认到店时间，确认后系统向预约电话发送通知短信。
4. 到店线下收款后标记交付完成，订单归档。
5. 在宠物管理页维护上架状态、检疫材料与图片。
6. 在协议管理页创建并发布协议版本，未发布的草稿不会出现在买家端。
7. 在知识库页面新建知识 → 保存草稿 → 审核并发布 → 等待索引完成（显示「可用于回答」后才参与检索）。

### 智能助手：知识问答与业务查询

1. 从买家端进入智能助手页面：http://127.0.0.1:8090/ai（开发态为 http://127.0.0.1:5173/ai）。
2. 提问示例：「英短掉毛严重正常吗」「有哪些在售的幼猫」「我最近的预约是什么状态」。
3. 回答以流式方式返回；知识类回答附带来源引用，点击可查看原文。
4. 回答中的宠物卡片是回答时的快照，点击详情会由业务服务核验当前在售状态。
5. 会话历史保存在 AI 服务的 SQLite 中，可对单条回答赞或踩。

### 微信小程序：演示流程

1. 用微信开发者工具打开 `mini-program/`（演示模式）。
2. 浏览动物 → 按名字、品种、分类或价格筛选 → 查看详情与成长档案。
3. 收藏 → 阅读隐私说明 → 点击「演示微信登录」→ 返回原页面再次收藏。
4. 在收藏页查看或取消收藏（演示模式只保存在本机缓存）。
5. 网站扫码确认：扫码打开小程序 → 微信登录 → 核对电脑上的核对码并主动确认 → 网页自动登录。演示模式下不批准网站登录。

## 关键配置说明

### 配置文件位置

| 文件 | 作用 | 是否入库 |
| --- | --- | --- |
| `.env`（项目根目录） | 容器部署的全部参数 | 否，仅本机 |
| `.env.example` | 根目录配置模板 | 是 |
| `backend/.env` | Java 服务的数据库、Redis、第三方凭据 | 否 |
| `ai-service/.env` | AI 服务的模型密钥与数据库路径 | 否，模板为 `ai-service/.env.example` |
| `mini-program/config.js` | 小程序演示模式与接口地址 | 是（不含密钥） |

### 主要配置分组

| 配置分组 | 关键变量 | 说明 |
| --- | --- | --- |
| 数据库与缓存 | `MYSQL_ROOT_PASSWORD`、`PAW_DB_PASSWORD`、`PAW_REDIS_PASSWORD`、`NACOS_AUTH_*` | 未填会导致容器启动失败 |
| 管理员 | `PAW_ADMIN_PASSWORD` | 新库初始化时创建管理员，默认用户名 `admin`；不会重置已存在账号的密码 |
| 图形认证 | `PAW_CAPTCHA_APP_ID`、`PAW_CAPTCHA_APP_KEY` | 阿里云「图形认证方案」的 appId / appKey；未配置时登录明确失败，不回退图片验证码 |
| 短信 | `PAW_SMS_ACCESS_KEY_ID`、`PAW_SMS_ACCESS_KEY_SECRET`、`PAW_PNVS_*`、`PAW_SMS_SIGN_NAME` | 登录与密码重置；未单独配置时成对复用 OSS 凭据 |
| 预约通知模板 | `PAW_SMS_APPOINTMENT_*_TEMPLATE` | 提交、确认、取消、过期、交付五类模板，各自独立，不回退验证码模板 |
| AI 模型 | `DEEPSEEK_API_KEY`、`DEEPSEEK_MODEL`、`DEEPSEEK_API_BASE` | 默认模型 `deepseek-chat` |
| AI 数据 | `AI_DB_PATH` | 修改后日志与进程号随数据库父目录移动，启停需保持同一配置 |
| 文件存储 | `PAW_STORAGE`、`PAW_OSS_ENABLED`、`PAW_OSS_BUCKET`、`PAW_OSS_ENDPOINT`、`OSS_ACCESS_KEY_*` | 默认本机目录，可选私有 OSS |
| 微信 | `PAW_WECHAT_APP_ID`、`PAW_WECHAT_WEB_APP_ID`、`PAW_WECHAT_MINI_APP_ID` 及对应 `SECRET` / 回调 | 三套凭据互相独立：公众号、开放平台网站扫码、小程序；未完整配置时前端隐藏入口 |
| 端口 | `WEB_PORT` | 容器方式下网站的对外端口 |
| 前端 | `VITE_API_TARGET`、`VITE_DEMO_MODE`、`VITE_ADMIN_BASE` | 决定前端连真实网关还是 Node 演示后端 |

### 配置原则

- 密钥只放在服务端环境变量或本机 `.env`，不得写入源码、YAML 或日志。
- `VITE_` 前缀的变量会被打包进浏览器产物，任何密钥都不能放入（图形认证 appKey、短信密钥、模型密钥均不可）。
- 未配置的第三方能力直接报错，不做假的成功回退；`prod` 环境强制禁用模拟服务。
- 本地模拟模式下的验证码写入 `backend/data/local-inbox/`，仅在隔离开发中使用；模拟短信不调用短信平台，也不能绕过真实图形认证。

## 常见问题

**Q1：点击「获取短信验证码」没有反应或直接失败？**
图形认证未配置或配置错误时会明确失败，不会回退到旧图片验证码。检查 `PAW_CAPTCHA_APP_ID` / `PAW_CAPTCHA_APP_KEY` 是否与阿里云控制台的图形认证方案一致，并确认 appKey 只配在服务端。

**Q2：页面提示短信已发送，但手机没收到？**
接口返回 `accepted` 只代表短信平台受理，不代表运营商已送达。本地模拟模式下不会真的发短信，验证码只会出现在 `backend/data/local-inbox/` 对应文件里。

**Q3：前端改了页面，刷新后还是旧内容？**
源码变更不会自动更新 Nginx 里的静态文件。容器方式的 `web` 镜像在构建时已把双端产物打进镜像（`dist/buyer` → 站点根目录，`dist/admin` → `/admin/`），所以改完要重新构建镜像：

```sh
cd frontend && npm run build && cd ..
docker compose build web && docker compose up -d web
```

只改后端时同理，重建对应服务镜像即可（`docker compose build order-server`）。

**Q4：容器都在运行，但业务还是用不了？**
容器健康只说明进程活着，不代表短信送达、AI 回复或第三方接入已经验收通过。先看服务日志，再逐个入口实际走一遍业务。

**Q5：能不能执行 `docker compose down -v` 重新来一遍？**
不要。该命令会删除数据卷，MySQL、Redis、上传文件、AI 数据与模型缓存、Nacos 数据都会丢。日常只用停止/启动，不做卷删除。

**Q6：新代码部署后数据库没变化？**
重建镜像不会自动升级已有数据库。需要在备份后按需执行 `backend/deploy/` 下的增量迁移，并核对迁移记录；已执行的迁移不要重复执行。

**Q7：管理员密码在哪里改？重置后没生效？**
`PAW_ADMIN_PASSWORD` 只在新库初始化时生效，不会覆盖已存在账号。已有账号请在管理后台修改密码，或按「管理员改密」流程处理。

**Q8：AI 一直回答「没有找到相关资料」？**
知识需要先保存草稿、审核发布，并完成向量索引（后台显示「可用于回答」）才参与检索。索引失败可在后台点「重试更新」；首次启动需要联网下载中文嵌入模型。

**Q9：AI 服务的日志和进程号找不到？**
日志与进程号跟随 `AI_DB_PATH` 所在目录。自定义过数据库路径的话，启停时要用同一份配置，否则会看错目录。

**Q10：小程序切到真实模式后请求全部失败？**
真实模式要求服务端配置小程序凭据、微信后台配置 HTTPS request 合法域名，并部署可用的 HTTPS 接口。请求失败不会回退演示数据。当前开发目录仍是演示模式，详见 `docs/MINI_PROGRAM_RELEASE.md`。

**Q11：备份到底算不算完整？**
只有备份清单 `manifest.json` 中 `complete=true` 才表示完整。恢复必须先在独立项目中演练，不要直接覆盖运行中的环境。

## 已知限制

**项目定位**

- 练习与简历展示项目，不对外运营；示例宠物、健康与检疫资料、协议内容均为模拟数据。
- 未做生产容量压测，并发与扩容结论不成立。

**交易与支付**

- 新预约全程无线上支付，收款与交付在线下完成；新预约不提供在线退款与健康申诉。
- 微信支付按公钥模式接入，真实扣款、退款回调、轮换与查单未做真实资质联调。
- 支付宝、银行卡等其他支付方式未接入。

**智能助手**

- 按单店、单 worker 运行：全服务最多八条并发生成，每身份每分钟十次新提问，每身份最多 200 个会话，单次默认 30 秒超时。
- 没有历史自动清理，也没有跨实例任务协调，多 worker 或扩容前需要补齐。
- 知识只存在 AI 服务的 SQLite 中，不写入原 MySQL；没有手动全量重建索引与商品向量索引。
- 疾病诊断、处方与用药剂量不在回答范围内；知识库不覆盖完整品种百科。
- 原接口文档中的 `contact-shop` 事件统计与会话 30 分钟自动关闭尚未实现。

**小程序**

- 当前开发目录仍为演示模式；缺可用的 HTTPS 接口，手机真机与网站扫码中转尚未完成验收。
- 真实模式收藏的跨设备同步需要有真实微信登录和两台设备验证。
- 预约、预约记录、养宠助手、门店指南等页面已从注册与打包中排除，旧源码仅作历史参考。

**工程与验证**

- 自动测试使用隔离数据库与替代模型，不能替代真实图形认证、真实短信送达、真实模型回复与生产数据库验收。
- 部分列表筛选仍在内存中完成，未下推 SQL；前端构建存在包体积提示。
- 旧单体版本的启动脚本与冒烟脚本保留在仓库中，仅作历史验证参考，不适用于当前的微服务与预约流程。
- 交付记录、实测结论按文档记录日期与范围阅读，不能当作当前状态证明。

## 相关文档

| 文档 | 内容 |
| --- | --- |
| `docs/DEMO_GUIDE.md` | 演示账号、隔离环境启动与讲解顺序 |
| `docs/DOCKER_DELIVERY.md` | 数据卷、OSS、排查与恢复步骤 |
| `docs/PRODUCTION.md` | 公网部署与上线检查 |
| `docs/CLOUD_DEMO.md` | 云服务器演示环境 |
| `docs/GRAPH_CAPTCHA.md` | 登录人机验证配置与实测 |
| `docs/ACCOUNT_SECURITY.md` | 密码与用户名规则、迁移步骤 |
| `docs/WECHAT_WEB_LOGIN.md` | 网站微信扫码登录 |
| `docs/MINI_WECHAT_QR_LOGIN.md` | 小程序扫码中转 |
| `docs/MINI_PROGRAM_RELEASE.md` | 小程序上线与真机验收 |
| `backend/ARCHITECTURE.md` | 微服务结构、职责与事务边界 |
| `backend/DATABASE.md` | 表结构与数据迁移 |
| `backend/API_STATUS.md` | 接口实现状态 |
| `宠物门店电商网站接口文档.md` | 接口文档 |
| `宠物门店电商网站需求文档.md` | 需求文档 |
| `ai-service/README.md` | AI 服务、知识维护与边界 |
| `frontend/README.md` | 前端结构与演示模式 |
| `mini-program/README.md` | 小程序导入与联调 |
