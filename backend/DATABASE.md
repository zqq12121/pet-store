# 数据库设计与迁移

依据：PRD v1.2 第六章，以及根目录接口文档 v0.9 第3～11章。接口文档明确的订单/售后状态划分优先于PRD概要中的混合状态枚举。

## 当前设计

核心业务按领域分表。`pets` 的品种、价格、状态等直接存为字段，`users` 保存买家，`orders` 保存单宠订单；不存在继续将业务对象整体塞入 `payload` 的写入路径。

新结构共50张表，包含22张领域主表、23张关系/证据表，以及单宠占用等5张基础表。MySQL运行库另外保留一张只读用途的 `resources_legacy`，因此迁移后实际可见51张表。AI与知识库仅具备结构，不代表接口或模型调用已实现。

| 文档业务 | 主表和关联表 | 数据用途 |
|---|---|---|
| 账号 | `users`、`admins`、`auth_sessions` | 用户、密码哈希、可撤销Token摘要、期限与角色 |
| 微信身份 | `wechat_accounts`、`wechat_openid_claims` | appId/openid与用户绑定、唯一归属 |
| 文件 | `file_assets` | 文件用途、大小、MIME、公开/私有权限及所属用户；文件内容仍在磁盘 |
| 门店 | `shops`、`shop_banners` | 门店联系方式、自提规则和有序轮播图 |
| 宠物档案 | `pets`、`pet_images`、`pet_personality_tags` | 个体属性、售价、在售状态、展示图片及标签 |
| 检疫证明 | `pet_quarantine_certificates`、`pet_quarantine_public_files`、`pet_quarantine_original_files` | 证明编号、有效日期、公开副本与私有原件分离 |
| 协议版本 | `agreement_versions` | 类型、版本号、正文、哈希、发布状态；类型+版本唯一 |
| 订单 | `orders`、`order_snapshots`、`order_payment_state` | 单宠订单、金额、联系人、截止时间、下单快照及支付展示状态 |
| 单宠占用 | `pet_occupancy` | pet_id主键，外键分别指向pets和orders，阻止一宠多单占用 |
| 自提交付 | `pickup_credentials`、`pickup_confirmations`、`pickup_health_checks`、`order_delivery_evidence`、`order_delivery_original_files` | 自提码、短信确认、健康检查、交付时的证据快照与材料引用 |
| 支付 | `payments`、`payment_transactions` | 支付请求/对账进度，与已验真的成功流水分开保存 |
| 售后 | `after_sales`、`after_sale_timeline`、`after_sale_diagnosis_files`、`after_sale_evidence_files`、`after_sale_consent_files` | 申请、审核、时间线与诊断/处理/同意材料 |
| 退回 | `after_sale_returns`、`after_sale_return_files` | 实物退回验收及证据 |
| 换宠 | `exchange_plans`、`exchange_original_files` | 替换宠、方案状态、确认/交付时间和证据；功能开关仍默认关闭 |
| 退款 | `refunds`、`refund_transactions` | 退款请求与已核实的退款事实，关联订单/售后 |
| 访问统计 | `analytics_events`、`analytics_event_properties` | 身份范围内事件去重、页面/宠物访问和搜索属性 |
| AI（结构就绪） | `ai_sessions`、`ai_messages`、`ai_timings` | 会话、消息、引用快照和耗时；未接入模型，不填充虚构数据 |
| 知识库（结构就绪） | `knowledge_entries`、`knowledge_breeds`、`knowledge_jobs`、`knowledge_job_errors` | 知识正文、适用品种、导入/索引任务及错误；向量检索尚未实现 |
| 基础设施 | `business_lock`、`idempotency`、`audit_log`、`schema_migrations` | 业务写锁、请求幂等快照、审计和迁移版本 |

### 字段与关系约定

- 金额为BIGINT，单位分；体重DECIMAL(12,2)，坐标DECIMAL(12,8)，出生/检疫有效日期为DATE。
- 时间戳沿用现有接口的ISO-8601文本（VARCHAR(40)）以保留纳秒和时区表示；系统生成时间为UTC。若后续改DATETIME，应单独制定精度/时区迁移，不能直接截断原签署时间。
- 领域主表的 `owner_id` 指所属用户/管理员；订单、售后等用户归属有users外键。会话允许用户/管理员两种角色，由后端鉴权验证主体。
- `business_key` 是领域内唯一查找键，例如手机号、订单号、支付流水号、Token摘要；不会存放明文Token。正常业务字段另有明确列。
- 关系表的 `parent_id` 指所属主记录，有外键；列表以 `(parent_id,position)` 为主键，保持图片、标签和时间线顺序。
- 订单与宠物、支付与订单、售后与订单、退款与售后、文件引用等使用外键。金额/宠物状态等使用CHECK和唯一约束；完整业务校验仍由Service负责。
- JSON仅用于购买时商品/价格/门店/协议快照、交付证据、外部预支付参数以及AI引用结果等需要按原样保存的结构；不承担宠物/用户/订单主数据存储。
- 疫苗和驱虫按当前接口各自定义的展示文本存为 `pets.vaccine_status` / `deworm_status`。当前文档没有逐次接种记录CRUD，不凭空生成健康记录历史。

### 代码入口

- `scripts/generate_schema.py`：显式字段、表、关系与约束定义。
- `common/src/main/resources/schema.sql`：生成的新库建表SQL，包含字段注释。只建表，不迁移旧数据。
- `common/src/main/resources/relational-model.json`：与SQL一起生成的字段白名单；不是业务数据。
- `repository/RelationalRepository`：将现有业务Map映射到关系列与关联表，使用参数绑定；未知非空字段明确拒绝，不悄悄丢弃。
- `repository/BusinessRepository`：保持原业务接口，实际通过新持久层读写，不再查询resources。
- `repository/PetQueries`：宠物条件筛选、价格/年龄区间、排序和分页在SQL中完成。其他业务列表仍有内存筛选，未宣称已达到生产压测指标。
- `tools/MigrateLegacy`：显式离线迁移，不启动HTTP服务或定时任务。

调整字段应修改生成脚本后执行 `python3 scripts/generate_schema.py`，再同步迁移方案和测试；不要单独改SQL而忘记字段映射。

## 本次迁移

2026-09-11，运行库 `warmpaw` 迁移前共26条资源记录：管理员1、用户2、会话5、文件4、门店1、宠物2、协议3、订单2、支付请求2、成功支付事实2、现场确认2。

先将运行库备份，再恢复到隔离MySQL副本演练。演练与正式迁移均执行逐字段/数组顺序/数值校验。正式迁移保留旧ID及已有会话摘要，不改协议发布状态、不迁移或删除磁盘文件；原2笔订单仍为completed。

备份：`data/backups/warmpaw-20260911-091353.sql`，权限600；旧JAR：`data/backups/pre-relational-backend.jar`。这些文件在Git忽略目录中。运行库旧表保留为 `resources_legacy`，日常业务不再读写它。旧H2文件未迁移，不能当作已切换的新库启动；启动检查会拒绝静默初始化空业务数据。

### 后续操作与回退边界

当前运行库已迁移，日常启动继续使用：

```bash
sh scripts/start-docker-local.sh
```

再次执行迁移会先检查 `schema_migrations` 标记；若未完成导入但目标领域表已有数据，会明确拒绝混合写入。迁移前必须停止本项目所有写入实例，并再次备份。DDL在MySQL会隐式提交，失败可能留下空表；业务导入在一个事务中完成，可修复原因后重试。

完整流程为：备份 → 在副本演练 → 停止旧后端 → 建立新表 → 事务导入并逐字段校验 → 切换库存外键 → 将resources改名保留 → 启动新版并验收。迁移工具只用于已有resources的MySQL库，不适用于直接初始化新库。

需要回退时，先停止新版并备份迁移后新增数据，再把迁移前SQL恢复到另一个专用库，用旧JAR连接该副本核验。不要直接覆盖当前运行库，也不要只把resources_legacy改名就宣称完成回退；迁移后新订单并不在旧表里。

## 验证状态

- 新结构H2：22项原业务测试 + 4项关系存储/迁移测试通过。
- 临时MySQL 8.4 + Redis：22项原业务测试通过；覆盖下单竞态、事务、幂等、越权、交付及退款。
- 关系存储专项证明：直接SQL改价后读取新列、附件外键失败回滚主表和子表、迁移保留字段/数组顺序、未知属性不被静默丢弃。
- 实际运行库：26条旧记录迁移并验证、库存外键重新关联pets/orders。
- Nginx完整HTTP冒烟通过：随机登录、上传、上架、下单、模拟付款、短信确认和核销完成；新增数据写入新表，resources_legacy仍为26条。
- 最终JAR重启后，经Nginx核对3笔completed订单、3只sold宠物及图片媒体访问通过，管理员登录/注销正常。临时MySQL测试容器已清理。
- 真实微信支付、短信、AI/RAG、向量索引和生产性能不在上述通过结论内。

### 直接查看数据

```sql
SELECT id, name, category, breed, price_amount, status FROM pets;
SELECT parent_id AS pet_id, file_id, position FROM pet_images ORDER BY parent_id, position;
SELECT id, order_no, pet_id, amount, status FROM orders;
SELECT o.order_no, p.name, o.amount, o.status
FROM orders o JOIN pets p ON p.id = o.pet_id;
```
