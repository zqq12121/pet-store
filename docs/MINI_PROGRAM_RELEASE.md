# 微信小程序上线配置与验收

范围：首页、选宠、动物详情、微信登录、账号收藏、我的，以及网站扫码确认页。保留练习项目和模拟资料标识；上线供访问与真实门店经营验收分开。短信、支付、预约、AI 不属于小程序的上线前置条件。网站扫码确认流程见 [中转登录](MINI_WECHAT_QR_LOGIN.md)。

## 2026-10-16 实测状态

| 项目 | 已核对情况 | 后续 |
| --- | --- | --- |
| 云服务器 | 现有 `/opt/warmpaw` 部署的 8 个服务均健康 | 继续复用；当前小内存配置只验证低并发演示，长期开放前验证容量 |
| 微信凭证 | 云端 `.env` 与运行 admin-server 均已配置，AppID 与小程序一致 | 已完成开发者工具真实 wx.login 换码及云端 Java/MySQL 验证；手机直连仍待 HTTPS |
| 数据库 | 已有 MySQL，收藏表的列、账号与动物唯一约束、查询索引均存在 | 不需要新买数据库；更新前备份，旧库按实际结构使用增量脚本 |
| 公开 API | 云端内部入口 home/pets 返回 200，游客收藏返回 401；空 code 登录返回 400 | 仍须通过正式公网 HTTPS 复验 |
| 域名与证书 | 本机和云端 PAW_DOMAIN、ACME_EMAIL 均未填写 | 提供正式域名、备案状态及证书联系邮箱 |
| 当前入口 | HTTP 公网 IP；安全组按来源限制演示访问 | 正式入口改为可被手机与微信审核网络访问的 HTTPS 域名 |
| 小程序后台 | 主体、认证、类目、备案、隐私指引及合法域名尚未核对 | 管理员在公众平台确认 |

## 需要提供或在平台填写的内容

1. **正式域名及其 ICP 备案状态**。域名可用已有主域名的子域名，例如 `pet.你的域名`；DNS A 记录指向现有服务器 `223.4.178.224`，不要配置未实际可达的 AAAA。域名 ICP 备案与微信小程序备案是两项独立事项。[阿里云小程序服务说明](https://help.aliyun.com/zh/ecs/user-guide/develop-your-wechat-mini-program-in-10-minutes)说明了微信小程序服务域名的 HTTPS 与 ICP 备案要求；[备案说明](https://help.aliyun.com/zh/icp-filing/basic-icp-service/support/web-site-suddenly-appeared-for-the-record-to-block-or-hang-and-so-on-and-so-forth)区分网站与小程序备案。
2. **证书联系邮箱**，填写服务器 `/opt/warmpaw/.env` 的 `ACME_EMAIL`。Caddy 已提供自动证书申请和续期，不必另外购买证书。
3. **微信小程序主体与发布资料**：个人/企业主体、所需认证、小程序备案、实际功能对应的服务类目、名称/简介/图标和管理员确认。类目和资质按该账号后台实际可选要求办理；不能仅凭代码保证审核通过。用户已确认名称“灵宠小珠”，代码与工程展示名称已统一；公众平台发布资料按该名称填写。
4. **用户隐私保护指引**：按实际登录标识、账号收藏及服务端保存情况填写运营者、联系方式、用途、保存与删除规则。当前不读取微信手机号、头像或昵称，不启用定位、相册或麦克风权限；不要为未使用的权限添加声明或申请接口。
5. **开发设置 → 服务器域名**：request 合法域名填写 `https://正式域名`，不带 `/api/v1`。资源目前为相对路径，发布包会从同一 HTTPS 域名加载；若改用 OSS/CDN，另按实际来源核对图片下载与预览。当前没有 web-view、上传和 WebSocket 功能，无需为旧短信桥接添加业务域名。微信后台菜单名称以当前界面为准。
6. **公网可达性**：手机与审核网络必须能访问正式域名的 TCP 443，证书申请与 HTTP 跳转使用 TCP 80。现有仅放行本机来源的演示白名单需要调整；SSH 保持管理来源限制，数据库、Redis、Nacos 和业务端口不对外开放。

AppSecret、数据库密码和 SSH 私钥仅保存在本地或服务器私有配置，不发送到聊天，也不打进小程序代码包。微信小程序备案从微信公众平台办理，不能用阿里云网站备案代替。

## 后端 HTTPS 切换

在服务器根目录 `.env` 设置 `PAW_DOMAIN=正式域名`（无协议和路径）、`ACME_EMAIL=你的邮箱`，已有微信、数据库和 Redis 配置保持原值。先完成 DNS、备案及 80/443 可达性，再执行切换。

现有 2 GiB 云端部署必须保留低内存覆盖，**依次叠加三个文件**，让生产覆盖最终取消 web 的直接 80 端口，由 Caddy 统一提供 HTTPS：

```sh
cd /opt/warmpaw
# 只读配置检查；退出码非零时先修正配置。
docker compose -f compose.yaml -f compose.cloud-demo.yaml -f compose.production.yaml config --quiet
docker compose -f compose.yaml -f compose.cloud-demo.yaml -f compose.production.yaml --profile tools run --rm readiness --mini-program --config-only

# 已有数据库更新前备份；脚本会短暂停写并恢复原来运行的服务。
python3 scripts/backup-compose.py
# 确认新备份 manifest.json 的 complete 为 true，再进行需要的更新。
# 若旧库缺少收藏表，使用 backend/deploy/pet-favorites-migration.sql，禁止重跑完整 schema.sql。

docker compose -f compose.yaml -f compose.cloud-demo.yaml -f compose.production.yaml up -d --no-build --wait --wait-timeout 900
docker compose -f compose.yaml -f compose.cloud-demo.yaml -f compose.production.yaml --profile tools run --rm readiness --mini-program --public
```

readiness 镜像或 Caddy 镜像首次使用时需要服务器能访问镜像源。上述命令需要同步本轮更新的检查脚本；新版 admin-server 已部署，当前尚未切换云端 HTTPS 入口。服务 healthy 和只读检查不证明真实微信授权、收藏写入或容量达标。

## 生成与检查小程序发布包

在本机项目根目录执行，替换为实际已配置的域名：

```sh
npm --prefix mini-program test
npm --prefix mini-program run release -- https://正式域名
# 上条命令返回全新 output/mini-program-release-* 目录；用返回的实际目录替换下面的路径。
node mini-program/scripts/check.js /绝对路径/output/mini-program-release-实际后缀
```

发布包固定 `demo: false`、`apiBase: https://正式域名/api/v1`、`assetOrigin: https://正式域名`。开发目录仍可保留演示配置；**上传时导入生成的发布目录**。包中只含注册的七页及其组件、图片、工具，不含旧预约/AI/门店页面、测试、脚本和 `.env`；`demo.js` 作为静态依赖保留，但真实配置不会启用它，请求失败也不会回退演示。合法域名校验保持开启。

## 真机、体验版与发布验收

1. 在公众平台配置合法域名、隐私保护指引及备案等资料，刷新开发者工具域名信息，保持合法域名与 HTTPS 证书校验开启。
2. 真机打开发布包，验证首页、搜索、分类、分页、动物详情、图片和成长档案。
3. 使用真实微信登录，收藏一只指定动物；退出并重新登录确认还在。另一设备登录同一微信账号应看到同一收藏；另一账号不能看到或取消前一账号的收藏。
4. 验证取消收藏、过期重新登录、网络失败提示、下架动物占位与取消。真实请求失败不得显示演示登录成功或演示收藏。
5. 上传为开发版本，设置体验版并验证；随后在公众平台提交审核。审核通过并完成管理员发布后，才能称为已上线。平台审核或扫码等管理员交互由账号负责人完成。
6. 验证网站生成小程序码 → 手机微信登录 → 核对码一致 → 主动确认 → 电脑自动登录及返回原入口；另外验证手机取消、过期刷新、旧码和另一浏览器不能领取。开发/体验版联调时将服务器 `PAW_WECHAT_MINI_ENV_VERSION` 切为对应版本；正式使用前恢复 `release` 并确认该页已发布。

本轮已通过 19 项小程序自动测试（包括隔离发布包生成与编译）、7 项上线检查脚本测试，以及 10 项独立 H2 微信登录/收藏测试。开发者工具真实换码及运行 MySQL 验证另见 [真实联调记录](../output/mini-program-real-login-2026-10-16/验证记录.md)；公网 HTTPS、备案状态、手机直连和双设备同步仍待上述配置后验收。
