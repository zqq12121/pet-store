# Docker Compose 本机交付

目标为本机完整容器化：买家端、后台、网关、订单、管理、AI、MySQL、Redis、Nacos。
外部 DeepSeek 与阿里云短信使用真实模式；未配置的能力不会改为模拟成功。
文件已接入阿里云 OSS，上传卷保留本地缓存。当前实测结果见 [交付验收记录](DELIVERY_STATUS.md)。

## 首次部署

需要 Docker Desktop（建议至少 8 GB 内存、20 GB 可用磁盘）和 Docker Compose v2 以上。
本机便捷配置、备份脚本需要 Python 3；镜像内完成 Node、Maven、Python 依赖安装和构建。
所有命令在项目根目录执行。

```sh
python3 scripts/init-compose-env.py
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 600
docker compose ps
```

初始化脚本读取现有 `backend/.env`、`ai-service/.env` 及环境中同名变量，生成权限 600 的根目录 `.env`，
生成缺少的内部密码，不覆盖已有根目录 `.env`。也可手工复制 `.env.example` 并填入配置。
不要使用 `docker compose config` 的无参数输出分享配置，其中可能包含解析后的密钥。
短信 RAM 权限、已审核签名与各业务模板需要自行在阿里云开通；凭据存在不代表可发送。
管理员密码变量只在创建新管理员时生效，不重置已迁移账号。

访问买家：<http://localhost:8090/>；后台：<http://localhost:8090/admin/>。
可在根目录 `.env` 修改 `WEB_PORT`。网站只绑定本机，数据库、Redis、Nacos 和内部 API 不向宿主机发布端口。
保持在 localhost 访问，使浏览器的安全上下文 API 可用。此配置尚不包括公网域名与 HTTPS。

AI 首次知识索引需要下载中文嵌入模型；服务 `/health` 返回 UP 仅表示进程可用，
须在后台知识库确认索引就绪并实际发送问题，才能确认 RAG 和模型调用成功。

## 配置与数据

| 数据 | Compose 数据卷 | 行为 |
| --- | --- | --- |
| 业务数据库 | `mysql-data` | 仅空卷执行 schema.sql；已有数据库不自动重建 |
| Redis | `redis-data` | AOF 持久化临时凭证和限流 |
| 上传文件 | `uploads` | 原图与私有证明保留；API 继续执行文件访问权限校验 |
| AI 会话、知识、向量、模型缓存 | `ai-data` | 单进程 SQLite，重新构建镜像不删除数据 |
| Nacos 数据和日志 | `nacos-data`、`nacos-logs` | 独立注册中心，不与原服务混用 |

默认 Compose 项目名为 `warmpaw`，实际卷名带此前缀。原名为 `mysql`、`redis`、`nginx` 及
`warmpaw-micro-nacos-1` 的容器不属于此项目；本配置不会接管或删除它们。
旧数据迁移应先暂停旧应用写入、备份 MySQL/上传/SQLite，再导入新卷并校验数量和媒体；
本机现有数据已经迁移并完成校验，详见交付验收记录。不要再次向运行库导入旧备份。

### OSS

根目录 `.env` 设置 `PAW_OSS_ENABLED=true`、`PAW_OSS_BUCKET`、`PAW_OSS_ENDPOINT`，
凭据使用 `OSS_ACCESS_KEY_ID` 和 `OSS_ACCESS_KEY_SECRET`。当前为北京 `aurt` Bucket。
对象固定在 `warmpaw/files/` 前缀，全部使用私有 ACL；公开图片也由现有媒体 API 提供，不开放 Bucket。
API 先执行原有权限校验，本地缓存缺失时再从 OSS 恢复，文件 ID 和访问 URL 不变。

新环境需迁移原磁盘文件时运行 `python3 scripts/migrate-oss.py`，它会校验内容、拒绝覆盖不同内容的同名对象，
并检查回读和私有 ACL。执行后保留原文件，备份目录生成校验清单。

## 启停、更新与排查

```sh
docker compose stop
docker compose start
docker compose up -d --build --wait --wait-timeout 600
docker compose logs --tail=100 gateway-server admin-server order-server ai-service
docker compose exec web nginx -t
```

`stop` 保留容器和数据；`down` 删除容器与网络但保留命名卷。
不要在需要保留数据时执行 `down -v`，该参数会删除数据卷。
代码或构建时前端配置变更必须重新构建镜像；后端密钥修改后运行 `up -d` 重建对应容器。

启动顺序由健康探针控制。Nacos 发现使用容器地址；Nginx 动态解析网关容器，支持重建后地址变化。
图片验证码所需字体及视频校验所需 ffprobe 已列入 Java 运行镜像。
前端构建固定关闭演示模式，不读取宿主机 `.env.local`；私有 `.env`、宿主机构建目录和数据不会进入镜像上下文。

Docker Hub 超时时先检查 Docker Desktop 的网络/代理；不要把网络超时当作应用编译错误。
Maven、npm、uv 安装阶段也需要可达各自官方依赖源。
本机 Docker Desktop 已配置 `http://127.0.0.1:7897` 代理，原设置保存在初始迁移备份中。
若 Docker pull 成功但 BuildKit 的认证请求仍超时，构建命令也需使用该代理：

```sh
HTTPS_PROXY=http://127.0.0.1:7897 HTTP_PROXY=http://127.0.0.1:7897 NO_PROXY=localhost,127.0.0.1 docker compose build
docker compose up -d --no-build --wait --wait-timeout 600
```

代理地址是本机配置；复制到其他电脑时按其网络配置调整，不要照搬不存在的代理。

## 备份与恢复

```sh
python3 scripts/backup-compose.py
```

此命令短暂停止本项目写入应用，备份 MySQL、上传文件及完整 AI 数据，再恢复原本运行的应用。
只有目录含 `manifest.json` 且 `complete=true` 才是完整备份。备份在根目录 `backups/`，已忽略 Git；
请复制到独立安全磁盘保存。Redis 中的验证码和限流不恢复，恢复后应重新登录。
根目录 `.env` 需独立加密保存；业务备份不包含明文凭据。

恢复先在另一 Compose 项目演练，禁止直接覆盖唯一运行库。基础步骤：

```sh
# 替换为自己的完整备份目录；项目名必须全新，并使用指定前缀。
python3 scripts/restore-compose.py backups/20260914-225121 --project warmpaw-restore-example
```

恢复脚本拒绝已有数据卷，恢复 MySQL、上传及 AI 数据并设置 UID/GID 10001；
只启动数据库和 Redis，不启动可能重复发送通知的业务服务。核验后再选择正式切换时间。
已完成一次独立恢复演练，结果位于备份目录的 `restore-verification.json`。

1. 停止目标项目所有应用，确认目标数据可以被备份替换。
2. 启动目标 MySQL，将 `mysql.sql` 导入其 `warmpaw` 库；先校验 dump 完整及源版本。
3. 将 `uploads` 内容恢复到管理服务 `/app/data/files`，`ai-data` 内容恢复到 AI `/app/data`，
   两者的容器用户 UID/GID 为 10001，恢复后校验所有权。
4. 保持目标 Redis 为独立实例，再启动全栈，检查账号、订单/宠物数量、图片、知识和历史会话。
5. 核对后再切换入口；原环境和备份保留，以便回退。

## 交付验收门槛

- 全部容器 healthy；买家和后台页面由容器 Nginx 提供；刷新深层路由正常。
- 登录、权限隔离、上传、预约库存、店长确认、线下收款交付、取消/过期、售后通过业务验证。
- 真实 DeepSeek 回复与知识引用、管理员知识管理与导入统计验证；不能只用健康接口代替。
- 真实短信由指定验收手机号确认送达；平台 accepted 不等于手机收到。
- 上传视频、OSS 回源与私有证明鉴权验证。
- 重建/重启保留数据，完成备份恢复演练；列出仍未提供的业务资料与第三方配置。

自动测试、配置校验、容器启动、第三方调用和浏览器验收分别记录实际结果，不互相替代。
