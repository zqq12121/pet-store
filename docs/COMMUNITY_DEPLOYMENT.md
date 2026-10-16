# 宠友社区开发与升级说明

验证日期：2026-10-11。需求来源：[COMMUNITY_REQUIREMENTS.md](COMMUNITY_REQUIREMENTS.md)。

## 已实现的入口

| 页面 | 入口与用途 |
| --- | --- |
| 宠友社区 | 买家端 `/community`，主导航与手机底部导航均可进入 |
| 店铺评价 | `/community?type=review`，到店指南也提供入口 |
| 发布与编辑 | `/community/new`、`/community/:id/edit` |
| 评价订单 | 已完成订单详情的“评价店铺”，服务端校验本人订单与评价次数 |
| 我的内容与收藏 | 社区内“我的帖子 / 我的评价 / 我的收藏”，个人页也提供入口 |
| 社区消息 | `/community/notices`，评论、回复通知与未读标记 |
| 社区管理 | 后台 `/admin/community`，内容管理、举报处理、恶意词库 |

帖子支持三个分类、标题与正文搜索、最新排序、分页、点赞、收藏和讨论。帖子及评价支持最多 9 张 JPG / PNG / WebP 图片，每张不超过 5 MiB。

评论采用两层展示；删除主评论隐藏整个讨论串，删除单条回复保留其他对话及“原回复已删除”提示。店铺回复显示独立标识。作者可以编辑或删除帖子、评价，留言只能删除。

词库检查覆盖发布、编辑、评论和店铺回复。管理员隐藏的内容不会因作者修改而恢复公开。未发布、已隐藏或已删除内容的图片不能匿名读取；本人和管理员可以通过授权链接预览。

每个已完成交付的订单只允许评价一次。数据库唯一约束与事务校验共同保护并发提交，删除评价保留已评价记录，不恢复评价资格。公开接口不返回手机号及他人的订单详情。

## 数据与服务

社区接口由现有 `admin-server` 提供，复用已有身份校验、事务和幂等执行器。订单资格从现有共享业务库读取，不修改订单流程。

新增表：`community_contents`、`community_images`、`community_comments`、`community_reactions`、`community_notices`、`community_reports`、`community_words`。

- 全新数据库：现有 `schema.sql` 已包含社区表，服务初始化会补齐默认词条。
- 已有数据库：先备份，再执行 [community-migration.sql](../backend/deploy/community-migration.sql)。只新增社区表和初始化词条，写入 `community-v1` 标记。
- 升级脚本可重复执行，保留管理员修改或停用的初始词条。
- 社区正文、评价、评论按 Unicode 字符计数，前后端采用相同规则。

不要向已有数据库重跑完整 `schema.sql`，也不要删除现有数据卷。

## 实际验证结果

| 检查 | 结果 |
| --- | --- |
| 后端全量测试与打包 | 85 项通过，0 失败、0 错误；其中社区集成测试 15 项 |
| 前端测试 | 现有 2 项 SSE 测试通过 |
| 双端类型检查与构建 | 买家端、后台均通过 |
| 浏览器业务验收 | 21 项通过，覆盖公开浏览、分类搜索、图片上传、编辑、互动、通知、回复删除、词库拦截、举报隐藏、订单评价及店铺回复 |
| 响应式检查 | 买家端 390 / 1024 / 1360 px；后台 1024 px 操作列可见且可点击；80 字符连续标题在手机上换行且无横向溢出 |
| MySQL 8.4 增量升级 | 7 项通过：新增 7 表、旧数据保留、初始化词库与标记、重复执行、保留词库修改、80 个 emoji 标题、订单唯一约束 |
| 模型一致性 | 社区生成模型、运行字段映射、全库 DDL 与增量 DDL 一致 |

浏览器验收使用项目已有 Playwright 和本机 Google Chrome。测试后端为独立 H2，账户、订单和帖子均为模拟资料；通过隔离夹具会话验证身份权限，未执行真实短信或图形验证码登录。MySQL 升级检查在一次性容器中运行，没有挂载已有数据卷或发布宿主机端口。

## 本机运行环境升级结果

2026-10-11 已完成现有 Compose 环境升级，入口为 `http://localhost:8090/community`，未部署到公网。

| 检查 | 结果 |
| --- | --- |
| 完整备份 | `backups/20261011-202130/`，完成标记、数据库、上传文件及 AI 数据均已核验 |
| 数据库升级 | `community-v1` 标记及 7 张社区表已存在 |
| 原有业务数据 | 升级前后 52 张旧表的行数和完整数据摘要一致；保留 4 个用户、1 个管理员、23 只宠物、3 个订单及 54 个文件记录 |
| 服务与镜像 | 8 个服务全部 healthy；admin-server、order-server、gateway-server、web 均运行本次构建镜像，旧镜像已保留回退标签 |
| 实际运行入口验收 | 11 项只读浏览器检查通过，社区公开接口 HTTP 200，私有及后台接口保持登录保护 |
| 页面与交互 | 页面身份、有效内容、错误遮罩、控制台、截图和交互检查通过；分类搜索、板块切换、到店指南入口和访客发布跳转均正常 |
| 响应式 | 1360×900 桌面及 390×844 手机页面正常，无横向溢出 |

本次运行入口验收使用项目 Playwright 与本机 Google Chrome（会话未提供 Browser 插件）。未向运行库写入模拟帖子、评价或订单；登录后写入行为已在上面的独立 H2 验收覆盖，本次没有重复执行真实短信或验证码登录。

构建时曾遇到 Docker Hub 直连超时，经已有本机代理完成后端镜像构建。没有修改数据库密码、Compose 配置或 Dockerfile。

社区当前没有帖子或评价，页面显示空内容提示；登录后即可开始发布。下列命令保留为后续更新参考，本次已经执行完毕。

## 已有 Compose 环境的更新步骤

在项目根目录执行。备份脚本会短暂停写并恢复原先运行的服务，确认输出“完整备份”后继续；构建先于停写，减少升级期间的等待。

```bash
cd "/Users/zhangquan/Documents/ChatGPT/宠物门店电商网站"
python3 scripts/backup-compose.py
docker compose build admin-server order-server gateway-server web
```

构建成功后，停止业务写入，升级数据库并启动服务：

```bash
docker compose stop --timeout 60 web gateway-server admin-server order-server ai-service
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 "$MYSQL_DATABASE"' < backend/deploy/community-migration.sql
docker compose up -d --no-build --wait --wait-timeout 600
```

数据库密码在容器内读取，不需要复制到命令参数中。若升级或启动失败，先检查日志与备份；不要删除表、数据卷或重置数据库。

运行后查看 `/community`、`/community?type=review` 及 `/admin/community`。使用自己的测试账号验证发帖与通知；评价需要本人已完成交付且尚未评价的订单，不要为了验收修改正常订单状态。

## 本地复查命令

```bash
cd "/Users/zhangquan/Documents/ChatGPT/宠物门店电商网站"
mvn -f backend/pom.xml package
npm --prefix frontend test
npm --prefix frontend run build
git diff --check
```

社区集成测试使用独立 H2，不连接 Compose 数据库。需 JDK 25、Maven 及已安装的前端依赖。
