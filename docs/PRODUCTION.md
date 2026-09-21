# 正式运营部署与验收

本项目继续采用单店、单机部署：预约到店 → 店主确认 → 到店线下收款及交付。新交易不启用线上支付。
业务、AI、数据库、缓存、注册中心、网页和 HTTPS 入口全部由 Docker Compose 运行。
服务器只需 Docker Engine 和 Compose 2.24.4+；现有配置初始化、完整备份与恢复辅助脚本另需 Python 3。
建议先以 4 核、8 GB 内存、40 GB 可用磁盘作为单店验收环境，再依据实际访问量确定容量；这不是压测承诺。

## 上线前必须准备

可先填写 [运营资料准备表与短信模板草案](运营资料准备.md)。当前店主已确认这些业务资料尚未准备，因此不能宣布正式运营验收完成。

- 一台可长期运行的服务器及真实域名。DNS A/AAAA 必须指向该服务器；只发布实际可达的 IPv6 地址。
- 服务器开放 TCP 80、443；UDP 443 可选。不开放 MySQL、Redis、Nacos、Java 或 AI 端口。
- 根目录 `.env` 填写 `PAW_DOMAIN`（只有域名，不带协议/路径）和 `ACME_EMAIL`。不得提交该文件。
- 已审核的阿里云通知签名 `PAW_SMS_SIGN_NAME` 及五个预约模板。模板必须与下面参数匹配，不能拿登录验证码模板代替。
- 店主在后台录入真实地址、联系电话、营业时间（例如 `每天 10:00-20:00`），核实宠物资料、检疫材料和正式交易协议。

| 配置名 | 模板参数 | 触发时机 |
| --- | --- | --- |
| `PAW_SMS_APPOINTMENT_SUBMITTED_TEMPLATE` | `orderId`、`visitAt` | 买家提交预约 |
| `PAW_SMS_APPOINTMENT_CONFIRMED_TEMPLATE` | `orderId`、`visitAt` | 店主确认预约 |
| `PAW_SMS_APPOINTMENT_CANCELLED_TEMPLATE` | `orderId`、`visitAt`、`reason` | 预约取消 |
| `PAW_SMS_APPOINTMENT_EXPIRED_TEMPLATE` | `orderId`、`visitAt` | 预约过期 |
| `PAW_SMS_APPOINTMENT_COMPLETED_TEMPLATE` | `orderId`、`visitAt` | 收款及交付确认 |

模板参数的长度及内容限制应按实际模板审核要求核对。`visitAt` 为北京时间 `yyyy-MM-dd HH:mm`。
验证码仍使用已有号码认证服务。短信平台 accepted 表示受理，不能替代手机送达验收。
微信登录可选：未完整配置时不展示按钮；启用时填写 App ID、App Secret、`https://域名/login` 回调，并在微信平台完成回调域名设置与实际授权验收。

## 部署

在服务器项目根目录操作。新环境可先执行 `python3 scripts/init-compose-env.py` 生成私有配置，再由负责人填写真实参数。
已有环境不得覆盖 `.env` 或重新导入旧数据库。迁移现有本机数据时，先按 [备份与恢复说明](DOCKER_DELIVERY.md#备份与恢复) 完成独立恢复、数据校验和切换；不要同时运行两套通知 Worker。

```sh
# 先检查配置；返回 1 说明存在阻挡项，不能当作通过。
docker compose --profile tools run --rm readiness --config-only
docker compose -f compose.yaml -f compose.production.yaml config --quiet

# 先构建成功，再备份与更新，降低停机时间。
docker compose -f compose.yaml -f compose.production.yaml build
# 已有运行环境执行；全新空环境尚无数据可备份。
python3 scripts/backup-compose.py

# 已有旧库首次升级预约/短信队列时，按 backend/deploy/APPOINTMENTS.md 执行增量迁移。
docker compose -f compose.yaml -f compose.production.yaml up -d --no-build --wait --wait-timeout 600
docker compose -f compose.yaml -f compose.production.yaml ps
docker compose --profile tools run --rm readiness --public
```

生产覆盖文件移除网页的 8090 宿主机端口，只由 Caddy 提供 80/443；证书自动申请及续期，保存在 `caddy-data` 卷。
Caddy 直接将 API 转发到网关，静态页面交给 Nginx；保留 SSE 实时响应。生产入口不在前面再叠加未经配置的 CDN/代理。
若添加 CDN，必须重新核验信任来源与真实 IP 限流，不能直接信任任意 `X-Forwarded-For`。
边缘容器 healthy 仅说明进程正常，公网 TLS 必须由 `readiness --public` 和外网浏览器验证。

## 运营验收

自动检查只读配置、公开页面、权限隔离及部分占位资料。它不证明门店资质、协议法律效力、第三方审核、压力容量或短信送达。
正式开放前，应完成并记录：

1. 真实域名的 HTTPS、HTTP 跳转、手机访问、管理员登录；未登录与非本人订单权限隔离。
2. 指定验收手机号真实收码并登录。预约提交、确认、取消、过期、交付通知分别检查平台受理和手机送达。
3. 隔离副本验证预约占用与释放、重复请求、超时、线下交付、售后；运行库不创建虚假收款和交付。
4. AI 真实回复、知识引用及订单身份隔离；上传文件、视频及私有证明鉴权。
5. 完整备份、独立恢复、数据数量和文件校验；保存恢复耗时和责任人。
6. 根据预期并发进行容量验收，明确可接受的响应时间、停机窗口和备份丢失窗口。

## 日常运维

```sh
docker compose -f compose.yaml -f compose.production.yaml ps
docker compose -f compose.yaml -f compose.production.yaml logs --tail=100 edge gateway-server admin-server order-server ai-service
docker compose --profile tools run --rm readiness --public
python3 scripts/backup-compose.py
# 维护停机，保留数据；恢复仍用上面的 up 命令。
docker compose -f compose.yaml -f compose.production.yaml stop
```

全部常驻服务限制容器日志大小。仍需监测磁盘、备份空间、容器健康和后台短信失败数量。
至少在每日营业结束及更新前保存完整备份，并复制到独立安全存储；备份包含个人数据，应限制访问。
`manifest.json` 的 `complete=true` 才表示备份完整；本机备份不是异地备份。
没有部署外部告警服务或自动备份调度；运营方需确定告警渠道、备份时间与保存期限后落实，不能仅依赖人工偶尔查看。

短信最终失败时先核对平台是否已经受理、通知是否仍有效，再由店主联系顾客或决定补发；不要批量把所有失败记录改成待发送。
队列是至少一次投递，平台受理后进程崩溃仍可能重复通知；租约保护不能实现第三方短信的恰好一次。
不要执行 `docker compose down -v`。回退时保留数据卷与新增队列表，切回已验证的旧镜像；不要用旧备份覆盖更新后产生的真实交易。

本轮实测范围和未解决阻挡项见 [交付验收记录](DELIVERY_STATUS.md)。
