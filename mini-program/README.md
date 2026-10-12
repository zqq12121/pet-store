# 茸茸星球 · 原生微信小程序

按上午保存的 Figma 页面、节点报告与原始照片开发，采用 WXML / WXSS / JavaScript，无 npm 运行依赖。Figma 在线 MCP 额度耗尽后，经用户确认按本地设计交付继续。

## 导入与演示

微信开发者工具 → 小程序 → 导入 → 选择 `mini-program/`。此工作区已由用户填写 AppID，保留原配置；复制到其他工作区时使用自己的 AppID。

`config.js` 默认 `demo: true`。登录页标注“演示微信登录”，同意隐私说明后可登录，不要求手机号或验证码。演示使用稳定的本机模拟身份，不调用 `wx.login`、微信服务端、短信、支付或主数据库。旧短信会话不会复用；旧短信演示账号下的记录不自动迁入新身份。切换演示／真实模式后需重新登录。

流程：选宠 → 详情 → 到店预约 → 微信登录 → 手填联系人和联系号码 → 选择未来 7 天内营业时间 → 阅读须知 → 提交 → 我的预约 → 详情／取消。预约号码用于到店联系，不用于登录或绑定微信账号。每个账号限一个有效预约；预约归属由账号身份决定。同一微信身份再次登录可查看自己的记录。

当前共 11 个业务页面。已移除小程序旧短信登录专用图形认证桥接页面和网页；原网站的短信、密码和微信网页 OAuth 登录保持原逻辑。

## 真实微信登录接入

实现遵循[官方小程序登录流程](https://developers.weixin.qq.com/miniprogram/dev/framework/open-ability/login.html)：

1. 用户点击登录后，小程序调用 `wx.login` 获取一次性 `code`。
2. 向后端 `POST /api/v1/auth/mini-wechat/login` 提交 `{ "code": "..." }`。
3. 后端调用微信固定 `jscode2session` 端点验证身份，按 AppID + OpenID 查找或创建无手机号的账号，签发本项目可撤销会话。
4. 客户端仅保存本项目会话；AppSecret 和 session_key 不返回小程序。失败不会回退到演示数据。此流程不读取微信手机号、头像或昵称，默认显示“微信用户”。

真实接入需完成以下配置：

- 服务端 `.env` 填写 `PAW_WECHAT_MINI_APP_ID`、`PAW_WECHAT_MINI_APP_SECRET`，使用与 `project.config.json` 相同的小程序 AppID。两项与网页 OAuth 配置独立。**不要把 AppSecret 写进小程序、提交 Git 或发送到聊天中。**
- 部署更新后的 Java 后端；本次没有部署、重启容器或修改运行数据库结构。
- 提供可访问的 HTTPS 后端，在微信后台配置 request 合法域名及图片所需 downloadFile 合法域名；不再需要短信认证 web-view 业务域名。微信后台如要求服务端调用 IP 白名单，按实际出口 IP 配置。
- 在小程序中设置：

```js
module.exports = {
  demo: false,
  apiBase: 'https://你的域名/api/v1',
  assetOrigin: 'https://你的域名'
}
```

仅上传小程序代码不会部署 Java 后端。缺少上述接入配置时，真实登录返回“尚未配置”，不会以模拟身份冒充成功。

无手机号的微信账号可以手填预约联系号码；号码仍需符合格式。普通历史订单和原网站手机号账号的本人手机号校验保持原样。手填号码未经短信核验，因此不会自动向它发送预约短信；预约进度在小程序内查看。未实现微信订阅消息。

| 功能 | 接口（相对于 `/api/v1`） |
| --- | --- |
| 首页、选宠、详情、门店 | `GET /home`、`/pets`、`/pets/{id}`、`/shop` |
| 微信登录 | `POST /auth/mini-wechat/login` |
| 预约 | `POST /orders/preview`、`GET /agreements/current`、`POST /orders` |
| 记录与取消 | `GET /orders`、`/orders/{id}`、`POST /orders/{id}/cancel` |
| 我的 | `GET /me`、`POST /auth/logout` |
| 养宠助手 | `POST /guest-sessions`、`POST /ai/sessions`、`POST /ai/sessions/{id}/messages` |

AI 首版接收完整 SSE 后展示正文、来源和卡片。门店电话、微信和坐标为空时隐藏操作；线上只预约、不收款；虚构地址、模拟资料与 DEMO 标识保留。

## 验证

从项目根目录执行：

```sh
npm --prefix mini-program run check
npm --prefix mini-program test
mvn -f backend/pom.xml -pl integration-tests -am \
  -Dtest=WechatMiniLoginTest,AppointmentSmsNotifierTest,CommerceIntegrationTest,AccountSecurityTest,WechatCapabilityTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
git diff --check
```

前端测试覆盖微信登录触发时机、失败与重复点击、页面卸载、返回预约、稳定演示身份、手填号码、账号隔离、重复提交、401 与网络失败。后端使用隔离 H2 和模拟微信 HTTP 响应，验证同一微信身份、跨应用隔离、无手机号用户、会话撤销、伪造身份拒绝及预约兼容性；不调用真实微信或发送真实短信。

旧短信版本的历史截图与调试记录保留在 [原调试报告](../output/mini-program-debug-2026-10-11/调试报告.md)，不代表当前微信登录已通过真实微信接入。新版本验证结果见 [微信登录调试报告](../output/mini-program-wechat-login-2026-10-11/调试报告.md)。真实微信换码、公网域名和手机端仍需在接入配置完成后验收。

设计参照：[本地设计交付](../Figma设计交付/微信小程序/README.md)。四张照片保留原始资产。
