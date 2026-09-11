# 后端结构与阅读指南

本次重构保持已有 MySQL 表、API 路径、成功响应字段和核心交易规则不变。原先的单一 ApiController 和基于字符串路由的 BusinessService 已移除，每个业务接口现在都有明确的 Controller 方法。

## 目录职责

以下路径均相对于 src/main/java/com/warmpaw：

| 目录 | 职责 | 主要入口 |
|---|---|---|
| controller/publicapi | 首页、宠物、门店、协议等公开查询；事件上报单独校验访客身份 | PetController、HomeController、ShopController |
| controller/buyer | 需要买家身份的个人资料、订单和售后 | AccountController、OrderController、AfterSaleController |
| controller/admin | 后台宠物、订单、自提、售后、协议、退款、统计 | AdminPetController、AdminOrderController |
| controller/dev | 受本地模拟服务开关限制的联调接口 | LocalPaymentController |
| controller | 登录、文件、支付通知等独立协议入口 | AuthController、FileController、PaymentCallbackController |
| controller/support | HTTP 身份适配、成功响应、全局异常处理 | ApiRequestExecutor、ApiResponses、GlobalExceptionHandler |
| application | 按业务模块组织的用例及公共事务边界，不做 URL 路由 | OrderApplicationService、BusinessOperationExecutor |
| service | 库存、状态机、支付验真、文件权限等业务规则 | OrderService、AfterSaleService、PaymentService |
| repository | 业务数据访问门面、关系表映射和宠物 SQL 查询 | BusinessRepository、RelationalRepository、PetQueries |
| mapper | MyBatis 的事务锁、库存占用、幂等记录及审计 SQL | ResourceMapper |
| dto | 明确的数据传输结构 | ApiResponse、FileDownload |
| common | 字段校验、时间范围、JSON、业务异常 | Input、TimeRange、ApiException |
| config | 应用启动校验和首次初始化 | Bootstrap |
| tools | 显式运行的离线迁移工具 | MigrateLegacy |

这里没有仅用于转发的 Service 接口与 Impl，也没有修改数据库来迎合目录命名。ResourceMapper 只负责基础设施 SQL；业务表读写实际由 RelationalRepository 完成。

## 推荐阅读顺序：查询一只宠物

1. controller/publicapi/PetController.detail：声明 GET /api/v1/pets/{petId}，传递路径参数，显式标注公开访问。
2. controller/support/ApiRequestExecutor.execute：解析 Authorization，创建 RequestContext；即使是公开接口，伪造的 Token 也不会被当成匿名请求放行。
3. application/BusinessOperationExecutor.execute：检查访问级别，建立事务，调用明确的业务方法。
4. application/CatalogApplicationService.pet：编排公开宠物查询。
5. service/CatalogService.publicPet：检查展示资格并生成公开字段。
6. repository/BusinessRepository → RelationalRepository：读取 MySQL 的 pets 及关联表。
7. controller/support/ApiResponses：包装 code、message、data、requestId、serverTime。

新增接口时，在对应 Controller 增加明确的映射，并调用对应 application 方法；不要重新增加接收任意路径的 dispatch 方法。

## 交易边界

- Controller 负责请求与响应适配，不执行 SQL、支付验签或入账。
- 普通业务通过 BusinessOperationExecutor 开启事务。ApplicationService 使用 MANDATORY，要求调用方已经建立事务，避免内部调用意外绕开事务。
- 写请求先鉴权，再获取单店事务锁。要求幂等键的接口显式传入 true，UUID 校验、请求内容比较和结果保存由公共执行器负责。
- 幂等作用域保留用户、请求方式、原始路径和键。读取原路径仅用于保持既有幂等记录兼容，不用于业务路由。
- 买家订单和售后操作继续检查资源归属；管理员接口明确使用 ADMIN 权限。
- 预支付意图先提交事务，再由 PaymentWorker 调用外部平台；网络失败不能导致已经创建的支付意图消失。
- 微信支付通知由 PaymentCallbackService 处理：原始报文验签、解密后，在事务中检查商户及支付/退款事实。通知响应保持平台要求，不套用买家成功响应。
- 文件下载由 FileService 先检查访问令牌，再返回 FileDownload 描述；Controller 不读取数据库，也不把磁盘路径发给客户端。
- 时间、金额、版本号、退款及库存的校验规则沿用原实现。

## 验证与维护

在 backend 目录执行：

    mvn clean test
    mvn package
    sh scripts/start-docker-local.sh

测试默认使用独立 H2 内存库，不连接或修改运行中的 warmpaw 数据库。CommerceIntegrationTest 的业务请求改为经过 Spring MVC、真实认证和事务执行器；RelationalPersistenceTest 继续验证关系存储与旧数据迁移。

本轮 31 项测试覆盖：公开查询、资料更新、管理员隔离、未知接口、幂等键、重复请求、并发下单、越权、付款事实、交付、退换货、文件和迁移。运行环境和第三方验证以 README 中的验收记录为准。

## 当前边界

此次完成后端结构重构，不代表所有生产能力已经实现：

- 业务请求和领域数据仍有 Map<String,Object>，沿用 Input 的未知字段及范围校验；统一响应与下载描述使用 record DTO。尚未将全部领域改成强类型 Entity/Request/Response。
- 关系表和动态映射白名单保持原样；宠物筛选已执行 SQL，部分其他列表仍在内存筛选。
- 单店事务锁继续保留，未把已有并发策略改成多租户或分布式架构。
- 微信支付、短信、AI/RAG 及生产压测不因代码重构而变为已验收。
