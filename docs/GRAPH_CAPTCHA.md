# 登录人机验证

使用阿里云号码认证控制台的「图形认证方案」，对应官方 SDK
`https://static.alicaptcha.com/v4/ct4.js` 与二次校验接口
`https://captcha.alicaptcha.com/validate`。这组接口使用 `appId`、`appKey`，不是短信 AccessKey
或其他版本验证码的 SceneId、Prefix。

## 配置与更新

在认证方案控制台创建方案，按控制台要求配置站点域名。在根目录私有 `.env` 中添加：

```dotenv
PAW_CAPTCHA_APP_ID=你的认证方案appId
PAW_CAPTCHA_APP_KEY=你的认证方案appKey
```

不要提交 `.env`，也不要把 appKey 填入任何 `VITE_` 变量。宿主机开发时将同名配置放入
`backend/.env`；Compose 会将根目录配置传入 Java 容器。

已有站点在配置完整后构建并更新管理服务和网页：

```sh
docker compose config --quiet
docker compose build admin-server web
docker compose up -d --no-deps --wait --wait-timeout 600 admin-server web
```

此功能不修改数据库结构，无需执行数据库迁移。配置未完成时不要更新运行网站，
新代码会返回“图形验证尚未配置”，不会自动退回旧验证码或跳过验证。

## 触发流程与接口

- 短信登录及自动注册、微信绑定手机、首次设置／找回密码：填写手机号 → 点击获取短信验证码
  → 弹出图形验证 → 服务端二次校验成功 → 发送短信。短信登录提交时不再重复弹窗。
- 用户密码登录：填写账号密码 → 点击登录或按 Enter → 弹出图形验证 → 二次校验成功 → 校验账号密码。
- 微信快捷登录：点击微信按钮 → 图形验证 → 二次校验成功 → 微信授权跳转。
  回调仍必须通过一次性 state 和 HttpOnly Cookie 验证；微信入口仍仅在微信配置完整时展示。
- 管理员登录：填写账号密码 → 点击登录或按 Enter → 阿里云人机验证 → 二次校验成功 → 校验账号密码。
  不再显示文字图片验证码；取消验证不提交登录，失败后可重新验证。

`GET /api/v1/auth/graph-captcha` 仅返回公开 `captchaId`。SDK 成功输出的 `lot_number`、
`captcha_output`、`pass_token`、`gen_time` 随业务请求提交到以下接口：

- `POST /api/v1/auth/sms-codes`
- `POST /api/v1/auth/password-login`
- `POST /api/v1/admin/auth/login`，请求同时包含 `username`、`password`，不再接受旧 `captchaId`、`captchaCode`
- `POST /api/v1/auth/wechat/authorize-url`，请求同时包含 `scene: login`、`returnPath`

后端使用 appKey 对 lot_number 做 HMAC-SHA256 签名，以 `application/x-www-form-urlencoded`
上传二次校验参数。只有 HTTP 200 且 `status`、`result` 均为 `success` 才放行。
关闭弹窗不发起业务请求；加载失败、超时、校验失败保留表单并允许重新验证。凭据只用于一次提交，
重复点击及跨入口重放被拦截，失败后也要重新验证。

## 验证边界

```sh
mvn -f backend/pom.xml test
npm --prefix frontend test
npm --prefix frontend run build
```

协议测试截获 HTTP 请求，核实签名、表单编码、重放拦截和异常关闭，并验证失败时不会调用短信平台、
签发密码会话或生成微信授权状态。商业及账号安全测试使用隔离的图形认证替代对象。
Node 演示后端不提供这次阿里云认证，联调应关闭演示模式并连接 Java 网关。

真实验收需要自己的认证方案：在站点点击弹窗并人工完成验证，确认云端二次校验与真实短信登录。
官方 Demo 弹窗、SDK 替代对象、自动测试或构建成功均不能证明自有方案已经可用或短信已经送达。

## 2026-10-06 实测记录

- Java 全量 69 项测试通过，其中 5 项图形认证专项；前端 2 项测试、类型检查与双端生产构建通过。
- Chrome 隔离交互验收：短信、密码 Enter 提交、微信授权、绑定手机、密码重置；
  取消／SDK 失败不请求业务接口、校验失败保留表单、重试与重复提交防护均通过。
- 官方 Demo 公开 ID 的真实 SDK 拼图弹窗在 1440×900 和 390×844 正常展示，无脚本异常和横向溢出。
  未自动操作或完成图形挑战。
- 首轮自有方案配置的真实 SDK 返回 `-50103 / not captcha`，说明该认证 ID 尚未被这个服务识别；
  需在号码认证「图形认证方案」控制台核对配套 appId、appKey。
- 首轮 admin-server、web 镜像构建完成时，自有方案尚未通过，因此当时未更新运行网站。未发送真实短信，
  未执行数据库迁移或改动现有业务数据。官方 Demo 与隔离交互结果不替代自有方案的真实二次校验。


### 配置完成后的复测与本机更新

- 阿里云真实加载接口返回 HTTP 200、`status=success`，方案类型为 `icon`；原 `-50103` 错误已消失。
- 重新构建并更新 admin-server、web，八个容器均 healthy，Nginx 配置校验通过。
  运行容器的 appId、appKey 与本机 `.env` 匹配，公开配置接口只返回 captchaId。
- Chrome 在真实 `http://localhost:8090/` 使用自有方案：短信获取、密码登录 Enter、
  找回密码均弹出真实图标点选验证；关闭后恢复可操作，取消没有业务提交。1440×900 和
  390×844 无运行异常、控制台错误或横向溢出；管理员仍显示原文字图片验证码。
- 首页、后台、首页 API、图形配置接口返回 200；匿名后台宠物／用户订单接口仍返回 401。
  无图形凭据的短信发送请求返回 400；伪造图形凭据的密码登录经真实二次校验返回
  `400 CAPTCHA_INVALID`，没有签发登录会话。
- 未自动完成图形挑战或发送真实短信；有效挑战通过后的二次校验成功及手机送达仍需人工验收。
  本轮未迁移数据库或修改已有业务记录。

## 2026-10-07 管理端统一验证

- 管理员登录已移除文字图片验证码，点击登录与 Enter 共用现有阿里云图标点选弹窗；
  服务端使用相同的云端二次校验，并拒绝旧 `captchaId`、`captchaCode` 登录参数。
- Java 69 项测试、前端 2 项测试、类型检查、双端生产构建和 `git diff --check` 通过。
  Chrome 隔离交互验证了单次 Enter 提交、重复提交拦截、取消／SDK 错误不提交、
  失败后保留表单、新凭据重试、账号错误提示及成功跳转。
- 使用项目现有代理完成 admin-server、web 镜像构建并更新本机站点，八个容器均 healthy。
  页面及公开 API 返回 200，匿名后台宠物接口返回 401；管理员缺少验证凭据、使用旧图片参数
  或伪造阿里云凭据均返回 400，没有签发会话。
- 真实 `http://localhost:8090/admin/login` 的阿里云弹窗在 1440×900、390×844 展示正常，
  无脚本错误或横向溢出；关闭后恢复可操作且没有提交登录。
  未自动完成真实挑战，真实有效挑战通过后的管理员登录仍需人工验收。
