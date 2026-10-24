# 数据库迁移说明

## 迁移脚本已交给 Flyway 管理

原先放在本目录的 7 个手工 SQL 已迁移到：

```
backend/common/src/main/resources/db/migration/
├── V1__account_security.sql          账号安全：password_changed_at 等可空列
├── V2__employee_management.sql       员工管理：admins 表扩展
├── V3__appointment.sql               到店预约：order_appointments
├── V4__appointment_sms_outbox.sql    预约短信 outbox
├── V5__community.sql                 宠友社区：内容、评论、点赞、举报、敏感词
├── V6__pet_favorites.sql             宠物收藏
└── V7__practice_shop.sql              练习项目门店基础资料
```

## 为什么改成 Flyway

原来的做法是「备份后手工执行一次，已经执行过的不要重复执行」。问题有三点：

1. **谁执行过哪些，只存在于人的记忆里**。换台机器、换个环境，无法自动判断。
2. **失败没有记录**。脚本执行到一半出错，回滚与否全靠手动确认。
3. **顺序没有约束**。「先执行 appointment 再执行 sms-outbox」这类依赖写在注释里，不写在系统里。

## 版本号为什么从 7 开始

V1–V7 是**历史上已经执行过**的脚本。两类环境都已经是最新结构：

- **已有库**：当年手工执行过这些 SQL；
- **新建库**：`compose.yaml` 把 `schema.sql` 挂进 `docker-entrypoint-initdb.d`，空数据卷首次启动时一次建全 60 张表。

所以配置里用了 `baseline-on-migrate=true` 与 `baseline-version=7`：Flyway 首次连上库时，把 V1–V7 记为「已应用」，**不会重复执行**。如果强行重跑，`ALTER TABLE ... ADD COLUMN` 会在已有列上报错。

## 今后怎么加迁移

1. 在 `db/migration/` 下新建 `V8__描述.sql`（版本号连续、双下划线、描述用下划线分隔）；
2. 脚本必须能在**已有数据的库**上安全执行：优先 `CREATE TABLE IF NOT EXISTS`、`ADD COLUMN ... NULL`，不要写 `DROP`；
3. 正常启动服务即可，Flyway 会执行未应用的版本并记入 `flyway_schema_history`。

查看当前状态：

```sh
docker compose exec mysql mysql -uwarmpaw -p warmpaw \
  -e "SELECT installed_rank, version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank"
```

## 与旧表 `schema_migrations` 的关系

`schema.sql:816` 里还有一个 `schema_migrations` 表（`version` / `applied_at` / `source_count`），
那是历史手工迁移留下的记录，`compose.yaml` 的健康检查也在查它。两者并存、互不干扰：

- `schema_migrations` —— 历史存档，只读，不再写入；
- `flyway_schema_history` —— Flyway 自己的版本表，今后所有迁移以它为准。

## 本目录剩下的文件

| 文件 | 用途 |
| --- | --- |
| `APPOINTMENTS.md` | 预约功能的部署与验收步骤 |
| `compose-microservices.yml` | 宿主机起 Java 微服务时的 Nacos 编排 |
| `nginx-local.conf` | 本地 Nginx 反向代理配置样例 |
