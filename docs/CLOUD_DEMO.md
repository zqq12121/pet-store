# 云服务器演示环境

这套环境用于练习与简历展示，沿用项目中明确标注的模拟资料。公网 IP 使用 HTTP；正式域名与 HTTPS 继续使用 `compose.production.yaml`。

## 当前服务器

- 公网 IP：`223.4.178.224`，项目目录：`/opt/warmpaw`。
- 系统：Alibaba Cloud Linux 4，x86_64；2 核、2 GiB 内存，已有 4 GiB SWAP。
- 买家入口：`http://223.4.178.224/`；管理后台：`http://223.4.178.224/admin/`。
- 安全组入方向允许本机公网出口 IP 访问 TCP 80，当前按来源 IP 限制演示访问。更换网络或供他人访问时，需要另行调整来源白名单。MySQL 仅监听服务器回环地址，其他内部服务不发布端口。

`compose.cloud-demo.yaml` 缩小 JVM 堆、MySQL 缓存和连接池，并为容器保留 SWAP 空间。这只适合低并发演示，仍须观察实际内存、交换及响应时间；项目正式部署文档建议 4 核、8 GB。

2026-10-16 启动验收：八项服务均为 healthy；60 张表行数及 74 个文件哈希与备份一致。通过 SSH 隧道验证首页、猫咪筛选和后台登录页；安全组来源调整后，已从本机公网出口直连验证首页、后台登录页、健康检查、门店与宠物 API 均返回 200，后台未登录接口返回 401，公网请求已命中主机端口转发规则。当前验证范围为获准的本机来源，不代表所有网络均可访问。

公网链路未打通时，本机可用 SSH 隧道查看云端页面；该终端需保持运行，入口只在本机可用：

```sh
ssh -i ~/.ssh/_Fable.pem -N -L 127.0.0.1:18090:127.0.0.1:80 root@223.4.178.224
# 在本机浏览器访问 http://127.0.0.1:18090/ 或 http://127.0.0.1:18090/admin/
```

## 登录与启停

在本机连接服务器，密钥始终保存在本机：

```sh
ssh -i ~/.ssh/_Fable.pem root@223.4.178.224
cd /opt/warmpaw

# 启动或恢复；必须同时指定演示覆盖文件，才能应用内存和公网端口配置。
docker compose -f compose.yaml -f compose.cloud-demo.yaml up -d --no-build --wait --wait-timeout 900
docker compose -f compose.yaml -f compose.cloud-demo.yaml ps

# 暂停服务，保留数据库、上传文件和 AI 数据。
docker compose -f compose.yaml -f compose.cloud-demo.yaml stop

# 排查状态；日志不应复制公开分享。
docker compose -f compose.yaml -f compose.cloud-demo.yaml logs --tail=80 gateway-server admin-server order-server ai-service
docker stats --no-stream
free -m
```

Docker 服务设置开机启动，应用使用 `restart: unless-stopped`。手动停止的应用需要使用上面的启动命令恢复。

低内存机器在全量冷启动或构建后可能出现交换和磁盘等待。建议用 `sh scripts/start-cloud-demo.sh` 逐项等待基础服务、认证、订单、网关、AI 和网页健康；它保留数据并使用已有镜像，不同时启动多个 JVM。若已出现交换抖动，先停 AI、网关和订单，再用该脚本恢复，最终必须确认八项服务健康。构建仍按下文要求先暂停业务。

该脚本用于当前 HTTP 演示入口；切换 HTTPS 后使用 [小程序上线指南](MINI_PROGRAM_RELEASE.md) 的三份覆盖文件配置，避免重新发布 web 的 80 端口与 Caddy 冲突。

网关保留 384 MiB 内存上限，交换上限设为相同值，即不允许该容器交换。其余服务仍使用原交换配置；本轮实测网关没有 OOM，但反复换页导致冷启动健康探测耗尽，临时禁用其交换后恢复健康。后续以实际响应和内存压力确认容量，不能据此承诺高并发。

2026-10-16 初次收尾复验：已应用该交换配置，顺序启动后八项服务恢复健康，Nacos 中认证/订单实例均健康，真实目录转发返回 200。新版认证 JAR 与已测试产物哈希一致；真实微信重复登录/退出撤销、真实 DeepSeek 流式回复以及公网 IP 页面验收通过。该阶段运行 JRE 21。

同日晚间已按用户确认升级三个业务 Java 服务至 Temurin 25.0.4.1，八项服务健康，实际运行版本与已测试 JAR 哈希均已核对。云端 Docker Hub 超时，采用本机下载官方 AMD64 Java 25 运行时并离线导入的方式，复用原镜像中的媒体工具及用户配置；项目标准 Dockerfile 也已切换至 Java 25。升级备份 `/opt/warmpaw/backups/20261016-201320/` 与旧镜像 `before-java25-20261016-201300` 保留；离线构建 Dockerfile、回滚标签清单和源码更新前副本位于 `/opt/warmpaw/output/java25-20261016-201300/`。完整复验见 [当日交付记录](DELIVERY_STATUS.md#2026-10-16-java-25-升级复验)。

## 配置、备份与更新

服务器根目录 `.env` 权限为 600，使用独立的数据库及 Redis 密码，保留本机已配置的第三方服务参数。迁移数据库后仍使用原账号和密码；Redis 会话不迁移，需要重新登录。

首次迁移的完整备份保存在本机 `backups/20261016-101623/`，云端副本位于 `/opt/warmpaw/backups/local-20261016-101623/`。包含数据库、上传文件及 AI 数据，不能公开分享。迁移基线为 60 张表、343 行记录、74 个文件；关键业务数量为 23 只宠物、3 笔订单、4 个用户及 54 条文件记录。

云端更新前先备份。备份脚本使用默认 Compose 文件操作同名容器；只读备份不会修改演示覆盖配置：

```sh
cd /opt/warmpaw
python3 scripts/backup-compose.py
```

低内存机器连续执行备份与升级时，使用 `python3 scripts/backup-compose.py --leave-stopped`。完整备份成功后保持写入应用停止，直接构建并运行 `sh scripts/start-cloud-demo.sh` 顺序恢复；备份失败仍自动恢复原先运行的应用。未指定该选项时保留原来的备份后恢复行为。

当日实测存在大量磁盘换页，已在云端应用 `deploy/99-warmpaw-cloud-demo.conf`（`vm.swappiness=1`），保留原交换区。该主机的持久配置为 `/etc/sysctl.d/99-warmpaw-cloud-demo.conf`；应用方式为 `sysctl -p /etc/sysctl.d/99-warmpaw-cloud-demo.conf`。原运行值为 20，若需回退应同时修改持久文件与运行值。调整后重新验证真实 AI、网页和业务接口通过；这仍是 2 GiB 低并发演示配置。

首次构建通过仅监听服务器回环地址的临时 SSH 代理下载官方依赖。日常启动不需要代理。重新构建需要先确认服务器能访问 Docker Hub、GHCR、Maven、npm、PyPI 等依赖来源，再按顺序构建，避免小内存机器同时运行多个构建：

所有云端镜像构建前均先停写；定向离线构建同样可能触发换页和 API 超时。构建完成后逐项恢复服务，再验证真实业务接口和 AI 流式回复；容器进程健康不能替代这两项检查。

```sh
# 2 GiB 机器构建前暂停业务，避免类型检查与 Java 同时争用内存。
docker compose -f compose.yaml -f compose.cloud-demo.yaml stop
docker compose --parallel 1 -f compose.yaml -f compose.cloud-demo.yaml build
sh scripts/start-cloud-demo.sh
```

不得执行 `docker compose down -v`。公网页面及容器健康不代表真实短信、微信平台回调或生产容量已经验收。

## 微信登录真实联调更新 · 2026-10-16

已完成 admin-server 定向更新，更新前完整备份位于 `/opt/warmpaw/backups/20261016-150530/`，旧镜像保留为 `warmpaw-admin-server:before-mini-login-20261016-79d916`。更新后 8 项服务均 healthy。

灵宠小珠使用开发者工具真实 `wx.login` code，经加密 SSH 交给云端 Java 再调用微信官方 code2Session，首次自动注册、再次复用同一 MySQL 账号、本人资料与收藏查询以及退出撤销均通过。两条验证会话已撤销，真实账号保留。此记录不代表手机直连、跨设备同步或网站扫码中转通过；当前仍缺正式 HTTPS API 与微信合法域名。详见 [真实联调记录](../output/mini-program-real-login-2026-10-16/验证记录.md)。
