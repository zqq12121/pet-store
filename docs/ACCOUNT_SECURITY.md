# 账号密码与用户名

买家保留短信及微信登录，新增手机号或用户名密码登录。用户名为3至32位，以字母开头，
只允许字母、数字和下划线，统一保存为小写并有数据库唯一约束。昵称仍是独立展示字段。

买家个人中心可设置用户名和修改密码；已有短信账号通过绑定手机号的专用验证码首次设置密码。
忘记密码也通过此入口验证手机号。新用户需先短信登录创建账号。管理后台的“账号安全”允许管理员
验证原密码后修改自身密码，不提供未经身份验证的管理员重置入口。

新密码为12至128位，包含字母和数字；首尾空白沿用项目现有规则去除。数据库只存随机盐和
PBKDF2-HMAC-SHA256 哈希（210000次迭代），接口不返回密码或哈希。

## 修改时间限制

- 买家与管理员密码：设置、修改、重置共用滚动7天（168小时）限制。
- 买家用户名：滚动3天（72小时）限制，首次设置成功后开始计时。
- 旧账号新增的修改时间为空，允许第一次修改；不推测或改写旧密码。
- 服务端检查时间并通过数据库事务锁串行化写操作，客户端不能提交修改时间绕过限制。
- 密码更新成功撤销该角色账号的全部会话，需要重新登录。冷却期忘记密码仍可使用短信登录，
  密码重置须等待冷却期结束。

页面显示下次可修改时间。成功变更才更新时间，错误密码、重复用户名和验证失败不会启动新冷却期。
短信登录验证码、密码重置验证码、微信绑定验证码的用途相互隔离。
重置验证码沿用已有阿里云号码认证验证码通道，受理不等于手机送达；真实送达需用户自行验收。

## 旧数据库升级

先构建镜像和运行测试，再备份，最后执行一次增量迁移。禁止在旧库重跑 schema.sql。

```sh
python3 scripts/backup-compose.py
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE"' < backend/deploy/account-security-migration.sql
docker compose up -d --no-build --wait --wait-timeout 600
```

迁移记录为 `account-security-v1`。执行前检查记录和字段，已执行的数据库不要再次执行；
MySQL DDL 会自动提交，出现失败应核对已完成的字段再处理，不能直接重复整份脚本。
迁移只增加 users/admins 字段与唯一约束，保留手机号、旧密码哈希和全部业务数据。
新库由 schema.sql 直接初始化，无需再执行迁移。

## API

| 方法 | 路径（/api/v1） | 用途 |
| --- | --- | --- |
| POST | /auth/password-login | 手机号或用户名、密码和 password_login 图片验证码 |
| POST | /auth/sms-codes | purpose=password_reset，手机号及 sms 图片验证码 |
| POST | /auth/password-reset | 手机号、smsRequestId、smsCode、newPassword |
| PUT | /me/password | 买家会话、oldPassword、newPassword |
| PUT | /me/username | 买家会话、username |
| GET | /admin/auth/security | 管理员用户名和下次密码修改时间 |
| PUT | /admin/auth/password | 管理员会话、oldPassword、newPassword |

个人资料增加 username、hasPassword、passwordChangeAvailableAt、usernameChangeAvailableAt，
无密码账号不会得到默认密码。验证码及密码不应写入审计日志，审计仅记录密码修改事件。

## 本机验收记录（2026-09-22）

- 后端63项测试通过，包含7项账号安全持久化、并发、权限及会话撤销测试；
  补充密码重置短信协议断言后，5项短信专项测试再次通过。
- 前端类型检查、买家及管理端生产构建、2项现有测试通过。
- 独立 H2 和模拟短信的 Chrome 浏览器验证：短信登录、设置用户名、3天限制、
  首次密码设置、用户名密码登录、买家与管理员7天限制、管理员改密后重新登录。
  检查1440px桌面与390px买家手机布局，没有页面脚本错误或手机横向溢出。
- 完整备份：`backups/20260922-111903/`，manifest.json 的 complete 为 true。
- 本机数据库已执行 account-security-v1；4个买家、1个管理员、3笔订单数量不变。
- 已更新 admin-server、order-server、web 镜像，8个正式容器全部 healthy。
- 在 http://localhost:8090 验证登录与重置页面跳转、验证码加载、手机布局、
  管理端路由守卫及匿名安全接口401；此验收没有修改真实账号或发送真实短信。
- 真实手机接收重置验证码仍需使用者自行验收。现有管理端大包构建提示仍存在。
