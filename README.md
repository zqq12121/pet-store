# 暖爪宠物门店

Vue 买家端与管理后台、Spring Cloud 业务服务、Python LangChain 智能助手组成的单店项目。
当前交易流程为预约到店 → 店长确认 → 到店线下收款与交付；AI 只查询和解释，不修改订单、价格或库存。

## 本机运行

需要 JDK 21、Maven、Node.js 22、Python 3.11、uv 和 Docker Desktop。已有 MySQL、Redis 需先启动，
连接配置放在 `backend/.env`；AI 配置参照 `ai-service/.env.example` 写入本机 `.env`。
不要覆盖已有 `.env`，也不要向已有数据库直接重新执行建表脚本。
新环境数据库准备见 [后端说明](backend/README.md)，旧库预约迁移见 [预约部署说明](backend/deploy/APPOINTMENTS.md)。

以下命令均从项目根目录执行。首次安装依赖与构建：

```sh
mvn -f backend/pom.xml package
npm --prefix frontend ci
cd ai-service
uv sync --locked --python 3.11
cd ..
```

启动业务服务与 AI 后台进程：

```sh
sh backend/scripts/start-microservices.sh
ai-service/.venv/bin/python ai-service/manage.py start
ai-service/.venv/bin/python ai-service/manage.py status
```

Java 脚本会启动本项目 Nacos。AI 管理脚本使用独立后台进程，关闭终端后继续运行；
重复启动或端口被占用时退出，不接管其他进程。`status` 检查进程和 HTTP 健康，
“模型密钥已配置”只代表存在配置，不代表外部模型调用已验证。

分别在两个终端从项目根目录启动前端，显式连接真实网关：

```sh
VITE_API_TARGET=http://127.0.0.1:8080 VITE_DEMO_MODE=false npm --prefix frontend run dev:buyer -- --port 5180 --strictPort
```

```sh
VITE_API_TARGET=http://127.0.0.1:8080 VITE_DEMO_MODE=false VITE_ADMIN_BASE=/admin/ npm --prefix frontend run dev:admin -- --port 5181 --strictPort
```

| 服务 | 地址 |
| --- | --- |
| 买家 | http://127.0.0.1:5180/ |
| 智能助手 | http://127.0.0.1:5180/ai |
| 管理后台 | http://127.0.0.1:5181/admin/ |
| 网关健康 | http://127.0.0.1:8080/actuator/health |
| 订单服务健康 | http://127.0.0.1:8081/actuator/health |
| 管理服务健康 | http://127.0.0.1:8082/actuator/health |
| AI 健康 | http://127.0.0.1:8083/health |
| Nacos 控制台 | http://127.0.0.1:8088/ |

`npm run dev` 会同时启动 Node 演示后端；验收 Java/AI 应使用上面的独立前端命令。
本地 Java 登录使用随机图片验证码和 `backend/data/local-inbox/` 中的短信码，不使用演示万能码。

## 停止与日志

前端在对应终端按 Ctrl+C。从项目根目录停止后台应用：

```sh
ai-service/.venv/bin/python ai-service/manage.py stop
sh backend/scripts/stop-microservices.sh
```

AI 脚本仅停止 PID 文件对应且命令匹配本 checkout 的进程，等待正常退出；
若超时会保留 PID 文件并提示检查日志。使用原 `ai-service/run.sh` 前台启动的进程仍用 Ctrl+C 停止。
运行与停止管理命令时应保持相同的 `AI_DB_PATH`、`AI_HOST`、`AI_PORT` 配置。
停止应用不删除数据库、知识向量、模型缓存，不停止 MySQL、Redis 或 Nacos 容器。

| 内容 | 默认位置 |
| --- | --- |
| Java 日志 | `backend/data/logs/` |
| AI 日志 | `ai-service/data/logs/ai-service.log` |
| AI PID | `ai-service/data/run/ai-service.pid` |
| AI 数据库 | `ai-service/data/ai.sqlite3` |

自定义 `AI_DB_PATH` 后，AI 的日志及 PID 目录随数据库父目录移动。

## 构建部署与验证

```sh
mvn -f backend/pom.xml package
npm --prefix frontend test
npm --prefix frontend run build
cd ai-service
.venv/bin/python -m pytest -q
```

Java 测试默认使用隔离 H2；AI 测试使用临时数据及替代模型，启停测试需要允许监听本机临时端口。
真实 DeepSeek、短信送达与生产数据库验收须单独进行。

Nginx 静态部署分别使用 `frontend/dist/buyer/` 和 `frontend/dist/admin/`，配置见
[本机 Nginx 配置](backend/deploy/nginx-local.conf)。该配置将 `/api/` 转到网关，已关闭代理缓冲以支持 SSE。
更新源码后必须重新构建并更新静态目录；修改 Nginx 配置后先运行 `nginx -t`，通过后再重载。
本轮未自动替换运行中的静态站点或重启现有业务服务。

2026-09-13 本地验证：Java 39 项测试与打包通过；Python 44 项测试通过，包含真实临时进程的启动、
健康检查、重复启动拒绝、停止后保留数据、端口冲突和 PID 复用保护；前端 2 项 SSE 测试、类型检查和双端构建通过。
管理端构建仍有现存的大包体积提示。以上不代表正式环境部署或第三方服务验收完成。

更多说明：[业务服务](backend/README.md) · [AI 与知识库](ai-service/README.md) · [接口状态](backend/API_STATUS.md)。
