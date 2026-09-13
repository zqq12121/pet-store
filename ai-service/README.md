# 暖爪宠物智能体

Python 3.11 + LangChain 1.x + DeepSeek，作为现有 Spring Cloud 项目的 AI 功能接入。

已支持：猫狗知识问答与来源引用、在售宠物推荐、本人预约/订单查询、跳转预约页面、多轮会话、流式回复、历史记录和赞踩反馈。**不会提交/取消预约、付款、退款或修改库存。**

## 启动

从项目根目录执行：

```sh
cd ai-service
uv sync --locked --python 3.11
# DEEPSEEK_API_KEY 已在环境变量中时无需重复配置。
# 也可参照 .env.example 新建 .env，仅在本机填写密钥。
sh run.sh
```

默认监听 `127.0.0.1:8083`，健康检查 `http://127.0.0.1:8083/health`。前台启动时按 Ctrl+C 停止。默认使用 `deepseek-chat`；环境变量名称和可选项见 `.env.example`。依赖精确版本由 `uv.lock` 固定。

需要关闭终端后继续运行时，在 `ai-service` 目录使用后台管理命令（macOS/Linux）：

```sh
.venv/bin/python manage.py start
.venv/bin/python manage.py status
.venv/bin/python manage.py stop
```

不要与前台 `run.sh` 同时启动。管理脚本检查端口与进程归属，只停止它记录的本项目进程；
默认日志为 `data/logs/ai-service.log`，PID 为 `data/run/ai-service.pid`，停止时保留所有持久数据。
修改 `AI_DB_PATH` 后，日志和 PID 随数据库父目录移动，启停时须使用相同配置。
完整前后端运行顺序见 [项目运行说明](../README.md)。

首次启动会把 `knowledge/entries.json` 导入数据库一次，并自动为已发布知识建立中文向量。模型 `BAAI/bge-small-zh-v1.5` 缓存在 `data/models`，后台显示“可用于回答”后即可检索。已有知识和向量在重启后保留；种子文件不会覆盖后台编辑。

Java 管理服务分别通过 `/api/v1/auth/ai-identity` 和 `/api/v1/admin/auth/ai-identity` 验证买家/游客与管理员；网关把 `/api/v1/ai/**` 和 `/api/v1/admin/knowledge/**` 路由到 Python。因此首次接入需要在项目根目录构建 Java 并重新启动管理服务和网关：

```sh
mvn -f backend/pom.xml package
# 完整 Java 服务的启动/停止仍使用 backend/scripts 下的现有脚本。
# Python 由上方 run.sh 单独运行，不由 Java 启动脚本自动启动。
```

买家端须使用真实网关配置。从项目根目录打开另一个终端：

```sh
cd frontend
VITE_API_TARGET=http://127.0.0.1:8080 VITE_DEMO_MODE=false npm run dev:buyer -- --port 5180
```

打开 `http://127.0.0.1:5180/ai`。5180 是本次联调端口；若 5173 空闲也可用默认端口。不要使用 `npm run dev` 中的 Node demo 来验收真实 AI。

## 业务接入

```text
买家聊天页 → Gateway 8080 → Python AI 8083 → DeepSeek
                              ├→ Java admin-server 8082：验证身份、商品、门店
                              ├→ Java order-server 8081：本人订单/预约
                              ├→ 本地中文向量模型：知识检索
                              └→ SQLite：会话、消息、引用快照和反馈
```

- `app/agent.py`：LangChain `create_agent` 选择一次工具批次，最多调用五个工具。工具执行后再流式生成回答；订单使用固定业务模板。
- `app/business.py`：只有白名单 GET 请求，没有通用 HTTP、SQL、写操作工具。用户身份绑定在工具闭包中，模型不能传入 `userId` 或认证凭证。
- `app/knowledge.py`：后台异步分块和嵌入，问答只检索当前已发布且向量版本匹配的知识，按猫狗类型和明确品种限定适用范围。
- `app/knowledge_store.py`、`app/knowledge_api.py`：SQLite 知识持久化、管理员接口、乐观版本锁及审核人记录；修改或归档会立即移除旧向量资格。
- `app/store.py`：SQLite 保存完整历史；每次模型调用只传最近十条文本，避免无限上下文。不会保存业务凭证、订单联系人、手机号、自提码或付款参数。
- `app/main.py`：FastAPI 会话接口和 SSE；断开网页连接后，已有后台生成仍在超时范围内完成并落库。

推荐会复核候选宠物实时详情，仅展示 `on_sale && purchaseAllowed` 的个体。预约入口来自核验过的 ID，前端路由为 `/checkout/:petId`，仍由已有页面完成登录、表单校验、协议确认和提交。查询预约使用现有 `/orders` 接口，`reservation_confirmed` 不会被解释成付款或交付完成。

同一请求只能使用买家 `Authorization` 或 `X-Guest-Token`。Java 每次核验不透明凭证；管理员身份不能查买家 AI 会话。游客登录后开启新的买家会话，不自动迁移游客历史。

## 知识维护与边界

在管理后台的“知识库”页面维护内容：

1. 点击“新建知识”，填写猫狗类型、分类、文章或问答对、适用品种和来源，保存草稿。
2. 编辑草稿，核对内容与来源，选择“审核并发布”，勾选审核确认后提交。
3. 页面自动刷新状态：等待更新 → 更新中 → 可用于回答。失败会显示“更新失败”，编辑并重新审核发布可重试。
4. 修改已发布内容会增加版本，新版本就绪前不再检索旧正文；归档立即停止用于新的检索。已有聊天引用保留当时快照。

管理员令牌由 Java 每次验证，买家、游客和过期凭证不能使用知识管理接口。来源链接限制为公开 HTTPS 地址，禁止用户名密码和常见凭证参数。文章按短块嵌入，避免长文尾部被截断。两个管理页面同时编辑时，过期版本返回 409，需重新加载后编辑。

管理端启动（从项目根目录）：

```sh
cd frontend
VITE_API_TARGET=http://127.0.0.1:8080 VITE_DEMO_MODE=false npm run dev:admin -- --port 5181
```

当前本机管理端入口为 `http://127.0.0.1:5181/admin/knowledge`；路径前缀由 `VITE_ADMIN_BASE` 配置。

种子知识含门店业务规则及 ASPCA 猫狗护理指南中文摘要，仍不覆盖完整品种百科。当前知识存储在 AI 的 SQLite 中，不写入原 MySQL AI 表。**批量导入、手动全量重建任务、AI 运营统计尚未开发**，管理页面已去掉未接通的导入入口。疾病诊断、处方和用药剂量仍不在回答范围内。

SQLite 位于 `data/ai.sqlite3`，模型和数据库均已加入 Git 忽略。当前按单店、单 worker 运行：全服务最多八条并发生成、每身份每分钟十次新提问、每身份最多 200 个会话，单次默认 30 秒超时。相同 `clientMessageId` 只生成一次，完成/失败后重放已保存结果；重新提问需使用新的 ID。进程重启会将未完成记录标为失败。当前没有历史自动清理和跨实例任务协调，多 worker/扩容前需要补齐。

原接口文档中的 `contact-shop` 事件统计、会话 30 分钟自动关闭及后台报表不属于这次交付；现有联系店主按钮直接跳转门店页，显式关闭会话接口可用。会话历史中的商品卡片是回答时快照，点击详情后由业务服务核验当前状态。

生产环境保持 AI 端口仅内网可达，通过网关对外提供请求；Nginx SSE 路由应关闭代理缓冲，读取超时大于生成超时。密钥只保存在服务端环境变量或本机 `.env`。

## 验证

```sh
cd ai-service
.venv/bin/python -m pytest -q
```

测试覆盖真实 LangChain 图的工具调用、游客登录提示、订单脱敏、跨身份隔离、下架/不可预约商品排除、幂等重放、并发、超时和失败恢复。模型与 Java HTTP 响应在自动化测试中替换为隔离样例；真实 DeepSeek、Java 网关、浏览器联调须另外执行，不把这些测试当作外部服务可用性的证明。
