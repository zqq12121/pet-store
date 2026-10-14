# 员工管理与后台权限

当前按单门店、固定店长与接待员工两种权限实现。统一后台登录入口 `/admin/login`，账号身份由服务端识别，登录仅需要账号密码。买家登录的人机验证保持现状。

| 功能 | 店长 | 接待员工 |
| --- | --- | --- |
| 首页 | 经营概览、收入、售后及 AI 统计 | 今日到店预约、待到店与待核销 |
| 预约与订单 | 查看、确认、取消、登记收款交付 | 查看、登记收款交付 |
| 历史自提订单 | 查询自提码、核验本人确认与检疫材料、交付核销 | 相同 |
| 宠物档案 | 查看、编辑、上下架 | 只读查看 |
| 员工账号 | 新增、编辑姓名及号码、启停、重置密码 | 无权限 |
| 退款售后、社区、知识库、AI、协议、经营设置 | 有权限 | 无权限 |
| 账号安全 | 修改自己的密码、退出登录 | 相同 |

员工账号只能由店长创建，联系号码选填且不用于登录。创建后的登录账号固定，员工角色固定为接待员工，员工管理接口不能修改店长账号。不提供排班、考勤、薪资或多门店功能。

后台沿用 `admins` 账号表与 PBKDF2 密码摘要，增加 `role`、`display_name`、`phone` 三个字段；旧账号继续作为店长，不重置现有密码。`auth_sessions.role` 区分 `admin` 与 `staff`，后台会话有效期为30分钟。停用、启用和重置密码都会撤销该员工旧会话；写请求取得业务锁后再次检查会话，防止等待期间被停用的员工继续交付。

员工只被允许调用订单/宠物读取、到店完成、核销及自身账号安全接口。管理服务转发和订单服务执行处均校验身份，其余管理接口继续要求 `admin`。交付沿用原有收款确认、检疫核验、库存占用及幂等检查，保存员工经办 ID 和审计记录。

取消后台人机验证后仍保留单账号连续5次错误锁定10分钟，以及来源 IP 每分钟30次请求限制。新密码须为12至128位，包含字母和数字；沿用密码每7天修改一次的规则，店长重置员工密码也受此限制。

## 已有数据库升级

先备份数据库，再执行一次 `backend/deploy/employee-management-migration.sql`。该文件仅扩展后台账号字段、为旧账号标记店长角色，并记录 `employee-management-v1`；不得用完整 `schema.sql` 覆盖已有库。

```sh
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE"' < backend/deploy/employee-management-migration.sql
docker compose build admin-server order-server web
docker compose up -d --no-deps --no-build admin-server order-server web
```

部署后由现有店长账号登录 `/admin/`，进入“员工管理”创建员工。隔离 H2 验证可运行：

```sh
cd backend
mvn -pl integration-tests -am test
```

真实短信、支付平台和公众生产环境不属于本次员工功能验收范围。接待工作台的“今日预约”按 `Asia/Shanghai` 到店日期统计，待到店和待核销显示所有尚未完成的待办数量。
