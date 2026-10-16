# 灵宠小珠中转网站登录

买家网站显示微信小程序码，微信扫码打开灵宠小珠，用户核对六位核对码并主动确认后，发起扫码的电脑网页自动登录并返回原入口。沿用 Java、MySQL、Redis，不增加云数据库；首次微信登录自动建号，网站使用同一小程序微信账号。

## 配置

服务器根目录 `.env` 使用已有 `PAW_WECHAT_MINI_APP_ID`、`PAW_WECHAT_MINI_APP_SECRET`，AppSecret 不进入网站或小程序包。新增 `PAW_WECHAT_MINI_ENV_VERSION=release`：正式版为 `release`，仅在对应开发版或体验版已经上传时使用 `develop` / `trial` 联调。修改后重建 admin-server 容器环境；只修改文件不会改变运行配置。

Java 使用官方 `cgi-bin/stable_token` 获取并缓存接口 token，再调用 `wxa/getwxacodeunlimit` 生成小程序码。扫码页面固定为 `pages/web-login/index`，正式版检查页面存在；开发/体验版允许页面尚未发布，但仍需上传该版本且扫码者拥有相应权限。若微信提示 IP 白名单，须在公众平台添加服务器实际出口 IP，不使用手机或电脑 IP 代替。

网站 `GET /api/v1/auth/capabilities` 新增 `wechatMiniQrLogin`，已有小程序凭据时优先展示中转入口。未配置小程序时保留原开放平台网站应用入口。两条接入方式分别调用微信官方接口，不混用 AppID；本方案不要求额外申请开放平台网站应用凭据。

小程序真实包需要 HTTPS API、request 合法域名、备案、隐私指引及发布审核；详见 [小程序上线步骤](MINI_PROGRAM_RELEASE.md)。当前开发目录保留演示模式，该模式明确拒绝批准网站登录。未取得域名前不能交付可真机使用的正式包。

## 接口与状态

所有路径均以 `/api/v1/auth/mini-wechat/qr` 开头，成功响应使用项目的 `code/data` 包装，禁止缓存。

| 方法与路径 | 请求 | 结果 |
| --- | --- | --- |
| `POST` 根路径 | `returnPath` 与图形认证凭据 | 小程序码图片、短期 ticket、独立 browserToken、核对码、300 秒有效期 |
| `GET /{ticket}` | 无 | 手机查看状态、核对码与到期时间，无账号或领取凭据 |
| `POST /{ticket}/confirm` | 买家会话、`approved: true/false` | 用户确认或取消；必须已绑定当前小程序微信身份 |
| `POST /{ticket}/poll` | `browserToken` | 等待状态；确认后一次性返回网站会话和原站内路径 |
| `POST /{ticket}/cancel` | `browserToken` | 刷新或撤回同意时撤销旧码 |

创建和轮询均使用 POST 请求体传递凭据；browserToken 不进入二维码、URL、本地持久化或日志。二维码只包含 32 字符随机场景号，Redis 保存领取凭据的 SHA-256 摘要。核对码只帮助用户核对电脑，不能替代领取凭据。

`pending → confirmed → consumed` 为正常流程；`pending → rejected` 为手机取消；网页刷新或撤回同意将旧码置为 `cancelled`。状态使用 Redis Lua 原子比较更新并保留原 TTL，只有一个领取者可以签发网站会话。过期、重复确认、错误浏览器、变更 AppID、无小程序绑定、服务失败均不会登录成功。网页断开或领取响应丢失时要求重新扫码，不重放会话。

网站必须先同意协议并通过现有图形认证才生成码。手机扫码或完成微信登录本身不批准网站登录；确认页面还需勾选“本人打开的网站”并点击按钮。不按昵称或手填手机号合并其他账号。

## 验证边界

自动回归覆盖浏览器凭据隔离、首次身份复用、拒绝/取消/过期、重复领取、并发状态推进、图形认证失败、微信错误响应、失效接口 token 重取，以及小程序登录返回确认页与主动批准。浏览器隔离夹具验证桌面和 390px 手机的生成、刷新、撤回同意、拒绝提示及登录返回；其图形认证、码图片和确认结果均为模拟响应。

上线还要用实际 HTTPS 域名、对应小程序版本和手机微信完成完整扫码；自动测试与开发者工具的真实 code2Session 验证不替代这项验收。
