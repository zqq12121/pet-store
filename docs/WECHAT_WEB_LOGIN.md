# 网站微信扫码登录

买家 `/login`、`/password-login` 沿用暖爪的色板、字体和按钮，仅将内容改成居中的表单与扫码双栏；手机端上下排列。扫码使用微信官方 `WxLogin` SDK 与 `snsapi_login`，不是公众号的 `snsapi_base`，也不是小程序 `wx.login`。

## 平台与部署配置

申请前先确认以下事项，不要把小程序账号或 AppID 当作网站应用权限：

- **平台账号和主体**：在微信开放平台注册开发者账号，按个人、个体工商户或组织的实际主体选择类目。官方规范区分个人与非个人类目，部分需先完成资质认证；能否获得网站微信登录权限，以该账号后台审核为准。
- **网站应用与登录权限**：创建网站应用，填写名称、图标、网站和用途，完成应用审核，并确认已获得微信登录权限。注册账号、创建应用与获得登录权限是不同步骤。
- **官网和域名**：准备可公开访问的官网，展示实际功能、用户协议、隐私政策、联系方式和备案信息；不能只给本机 localhost、登录表单或小程序页面供审核。项目中的练习资料应如实标识，不以虚构商家或经营资质申报。
- **登记材料**：按后台要求提供《微信开放平台网站信息登记表》等资料，主体、网站、备案与签章信息保持一致。所需主体材料、可选类目和认证费用按提交时官方页面核对，不依据旧教程付款。

官方材料规范：[网站应用审核规范](https://developers.weixin.qq.com/doc/oplatform/Website_App/operation.html)。

1. 在微信开放平台准备已获微信登录权限的网站应用，登记实际网站的授权回调域名。小程序 AppID/AppSecret 不能代用。
2. 在根目录 `.env` 本机填写以下配置，不提交密钥：

```dotenv
PAW_WECHAT_WEB_APP_ID=网站应用的AppID
PAW_WECHAT_WEB_APP_SECRET=网站应用的AppSecret
PAW_WECHAT_WEB_REDIRECT=https://你的实际网站域名/login
```

3. 回调与用户访问的网站必须同源，统一 HTTPS；开发预览、IP、localhost 和另一子域名不能代替正式回调。`/login` 必须由买家前端接管，保留微信带回的 `code`、`state` 查询参数。
4. 更新 `admin-server` 配置和镜像、买家 `web` 镜像。Compose 已声明以上变量；只改 `.env` 不会更新运行中的容器环境。
5. 允许浏览器访问官方 `https://res.wx.qq.com` SDK 和 `https://open.weixin.qq.com` iframe；后端需要访问 `https://api.weixin.qq.com`。网站 `/wechat-login.css` 供微信 iframe 加载外观样式，不包含凭据。

官方参考：[网站应用微信登录](https://developers.weixin.qq.com/doc/oplatform/Website_App/WeChat_Login/Wechat_Login.html)、[微信登录 SDK](https://res.wx.qq.com/connect/zh_CN/htmledition/js/wxLogin.js)。平台权限以实际后台审核结果为准。

## 账号与登录流程

- 默认不勾选协议，确认后主动获取二维码；图形验证通过后才创建微信授权 state、加载 SDK。未配置、加载失败、取消授权或二维码过期时保留短信和密码登录，不模拟扫码成功。
- `GET /api/v1/auth/capabilities` 的 `wechatQrLogin` 仅表示配置完整，不代表微信资质与真实扫码已经验收。旧 `wechatLogin` 继续表示公众号网页授权配置。
- `POST /api/v1/auth/wechat/authorize-url` 接收 `mode: "web"`、`scene: "login"`、站内 `returnPath` 与图形验证凭据。state 有效期五分钟，绑定 Secure、HttpOnly、SameSite=Lax 的浏览器 Cookie。
- 扫码确认后在原窗口回到 `/login`，前端移除地址栏中的一次性凭据，再调用 `/auth/wechat/login`。服务端一次性消费 state，以固定的官方接口换取微信身份，再签发本站会话。
- 首次网站扫码直接创建无手机号账号；同一网站 AppID 与 OpenID 复用账号，不按昵称匹配、合并其他账号。网站应用、小程序和公众号的 OpenID 各自隔离，未实现跨应用自动合并。
- 用户取消、错误浏览器、过期或重放 state、应用配置中途改变以及微信响应失败都不产生成功登录。AppSecret、微信 access_token 和 OpenID 不返回前端。

## 验收边界

自动测试使用隔离 H2、模拟微信响应和浏览器网络拦截。正式使用仍需用真实网站应用与 HTTPS 域名完成：首次扫码、退出后再次扫码、原入口跳转、拒绝授权、过期刷新及不同账号隔离。没有真实凭据时不宣称真实扫码通过。
