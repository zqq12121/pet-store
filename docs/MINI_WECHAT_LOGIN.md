# 小程序微信登录与自动注册

小程序只承担前端交互，现有 Java 管理服务负责调用微信接口、查找或创建用户、签发项目登录令牌；沿用 MySQL，不引入云数据库。此阶段不包含电脑网页扫码中转。

## 调用流程

1. 用户在小程序登录页阅读说明并同意后，点击微信登录。
2. 小程序调用 `wx.login` 获取一次性 code，仅把 code 提交给 Java。
3. Java 使用服务端 `PAW_WECHAT_MINI_APP_ID`、`PAW_WECHAT_MINI_APP_SECRET` 调用 `https://api.weixin.qq.com/sns/jscode2session`，参数为 `appid`、`secret`、`js_code` 和 `grant_type=authorization_code`。
4. 微信校验通过后，按 `AppID + OpenID` 查找绑定。首次登录在同一事务中创建用户和绑定；再次登录复用原账号，不按昵称、手机号或一次性 code 合并账号。
5. Java 返回项目自身的会话令牌及用户资料。小程序校验响应完整后保存会话，提示“注册并登录成功”或“登录成功”，再返回原页面。

不把 AppSecret、openid、session_key 返回小程序。新账号默认昵称为“微信用户”，手机号与头像为空；头像昵称填写与微信身份登录是不同能力，当前不调用旧版个人资料授权接口，也不要求填写头像、昵称才能登录。登录失败重试时重新调用 `wx.login`，不重复使用旧 code。

## Java 接口

`POST /api/v1/auth/mini-wechat/login`

```json
{"code":"wx.login 返回的一次性 code"}
```

仅接受 code，不接受客户端提交的 openid、手机号或账号 ID。下列响应为结构示例，字段值均为占位内容：

```json
{
  "code": "OK",
  "data": {
    "accessToken": "项目签发的不透明会话令牌",
    "expiresIn": 7200,
    "isNewUser": true,
    "user": {
      "id": "服务端用户 ID",
      "nickname": "微信用户",
      "avatarUrl": null,
      "phoneMasked": null
    }
  }
}
```

`isNewUser` 仅在首次建号时为 true，老用户为 false；原有 accessToken、expiresIn、user 字段保持兼容。校验失败不会创建用户、微信绑定或会话。客户端不把缺失令牌、账号或有效期的响应当作登录成功。

| 情况 | Java HTTP 状态 |
| --- | --- |
| code 为空或包含不允许的身份字段 | 400 |
| 微信 code 无效或已使用、微信风险拒绝 | 401 |
| 微信登录频率超限 | 429 |
| 微信接口异常、服务繁忙、响应无法解析 | 502 |
| 服务端配置缺失或微信提示应用凭证异常 | 503 |

服务端不传播微信原始错误明细及含凭据的 URL，AppSecret 始终只保存在私有环境配置中。

## 配置和验收边界

云服务器与本机根目录 `.env` 已填写小程序凭据，运行云端 admin-server 的配置已与云端 `.env` 核对一致。真实小程序联调仍需有效 HTTPS API 地址、微信 request 合法域名，并将小程序切换为 `demo: false`。保留开发目录的演示模式，不能将演示账号当作真实微信登录结果；独立真实模式代码包生成步骤见 [上线指南](MINI_PROGRAM_RELEASE.md)。

2026-10-16 验证：

- Java：WechatMiniLoginTest 5 项及 PetFavoriteIntegrationTest 5 项通过，使用独立 H2 和模拟微信响应。验证首次自动注册、同身份不重复建号、不同应用和身份隔离、退出撤销、敏感信息不返回、微信错误分类以及收藏兼容。
- 小程序：19 项测试通过；WXML/WXSS 编译和 6 个注册页面检查通过。验证点击后获取 code、失败重试获取新 code、注册/登录提示、异常响应不保存会话及登录返回。
- 微信真实接口联通检查：在云服务器使用服务端凭据及固定无效 code 调用官方 code2Session，返回 HTTP 200、errcode 40029（无效 code）。未创建账号、未签发会话，未输出密钥。

后续已完成新版 Java 云端部署及开发者工具真实微信 code 联调：首次登录自动注册、再次登录复用同一账号，运行 MySQL 持久化、本人资料与收藏查询、退出撤销均通过。两条测试会话已撤销，账号保留。详见 [真实联调记录](../output/mini-program-real-login-2026-10-16/验证记录.md)。手机直连仍缺 HTTPS API 与合法域名；电脑网页扫码中转尚未接入。
