package com.warmpaw.remote;
import com.warmpaw.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
/**
 * 管理入口只做鉴权与远程调用；不持数据库写锁，不重复执行订单业务。
 *
 * <p><b>方向</b>：这是"后台转发入站"角色——浏览器把管理端请求打到 admin-server，
 * 本类校验身份后原样转发给 order-server；与之相反的 {@link OrderAdminClient} 是 Feign
 * <b>出站</b>声明（本类正是通过它发起调用）。两者路径相同、方向相反，不要混淆。
 *
 * <p>路由前缀 {@code /api/v1}，全部在 {@code /admin/**} 下。真正的事务、库存锁与状态机都在
 * order-server，本服务不碰订单表，因此不存在"两处各写一半"的一致性问题。
 *
 * <p><b>权限模型</b>：两档，由 {@code headers(...)} 决定，且<b>只校验本服务能看到的身份</b>：
 *
 * <ul>
 *   <li>接待岗：{@code AuthService.staff(...)}，用于订单查询、自提核销、预约"完成交付"；
 *   <li>店长：{@code AuthService.role(actor, "admin")}，用于售后、退款、看板与预约确认/取消。
 * </ul>
 *
 * <p><b>双重校验是刻意的</b>：订单服务收到转发后会再按操作复核一次权限，
 * 所以本层是"提前拦截 + 减少无效远程调用"，不是唯一防线。
 *
 * <p>透传约定：原 {@code Authorization} 与 {@code Idempotency-Key} 原样带过去，
 * 幂等与重试语义由订单服务决定；响应状态码、业务错误体与 {@code Retry-After} 也原样回传
 * （见 {@code relay}），不把下游失败包装成成功。
 */
@RestController
@RequestMapping("/api/v1")
public class AdminTransactionController {
  private final OrderAdminClient orders;
  private final AuthService auth;
  public AdminTransactionController(OrderAdminClient orders, AuthService auth) {
    this.orders = orders; this.auth = auth;
  }
  /** 店长级转发的默认入口：所有售后、退款与看板操作都走这里。 */
  private Map<String, String> headers(HttpServletRequest request) {
    return headers(request, false);
  }
  /**
   * 组装转发头：校验身份并保留原 token 与幂等键。
   *
   * <p>仅接待入口接受员工；订单服务收到转发后仍会再次检查具体操作权限。
   * {@code reception=true} 走 {@code staff(...)}（店长或员工），否则要求严格 {@code admin}。
   * 未登录 401、角色不符 403，异常在本服务内直接返回，不会发出远程调用。
   *
   * <p>只放行 {@code Authorization} 与 {@code Idempotency-Key} 两个头：其余头（Cookie、
   * 客户端 IP、内部标记）不向订单服务透传，避免伪造链路信息。
   */
  private Map<String, String> headers(HttpServletRequest request, boolean reception) {
    String token = request.getHeader("Authorization");
    if (reception) AuthService.staff(auth.identify(token));
    else AuthService.role(auth.identify(token), "admin");
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", token);
    if (request.getHeader("Idempotency-Key") != null)
      headers.put("Idempotency-Key", request.getHeader("Idempotency-Key"));
    return headers;
  }
  /**
   * 把订单服务的响应原样转回浏览器：状态码、允许的头与原始字节。
   *
   * <p>原样保留订单服务的 HTTP 状态、业务错误和重试提示，避免把错误包装成成功——
   * 前端据此区分"参数错 400/422"、"状态冲突 409"、"需等待 429 + Retry-After"。
   * 只复制 {@code content-type}/{@code cache-control}/{@code retry-after}，
   * 其余头（网关地址、内部追踪 id）不外泄；body 为空时回空字节而不是 null。
   *
   * <p>{@code try (response)} 确保 Feign 响应流关闭，避免连接泄漏。
   */
  private ResponseEntity<byte[]> relay(feign.Response response) throws java.io.IOException {
    try (response) {
      var result = ResponseEntity.status(response.status());
      response.headers().forEach((name, values) -> {
        if (List.of("content-type", "cache-control", "retry-after").contains(name.toLowerCase(Locale.ROOT)))
          result.header(name, values.toArray(String[]::new));
      });
      return result.body(response.body() == null ? new byte[0] : response.body().asInputStream().readAllBytes());
    }
  }
  /**
   * 预约操作转发到持有库存事务的订单服务。
   *
   * <p>POST {@code /admin/orders/{orderId}/appointment/{action}}，{@code action} 为
   * confirm/cancel/complete。权限刻意不对称：{@code complete}（到店交付）走 staff，
   * 店长与员工都可办理；confirm/cancel 走 admin——确认与取消会改变成交与库存占用，
   * 必须由店长拍板。未知 action 由订单服务返回 404。
   *
   * <p>转发时原样带上 {@code Idempotency-Key}（若客户端提供），重复提交由订单服务去重；
   * 库存占用校验也在那一侧完成，本层不持有任何锁。
   *
   * @return 订单服务的原始状态码与响应体（409 表示状态已变化，需刷新）
   */
  @PostMapping("/admin/orders/{orderId}/appointment/{action}")
  public ResponseEntity<byte[]> appointment(@PathVariable String orderId, @PathVariable String action,
      @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.appointment(orderId, action, body == null ? Map.of() : body, headers(request, "complete".equals(action))));
  }
  /**
   * GET /admin/orders：由订单服务执行并再次校验后台身份。
   *
   * <p>后台订单列表，权限 staff（店长+员工，接待岗日常查单）。查询参数经
   * {@code @SpringQueryMap} 原样透传（状态、关键词、分页等由订单服务定义），
   * 本服务不解析、不缓存，因此新增筛选项无需改这里。
   *
   * <p>只读，不消耗 {@code Idempotency-Key}；状态码与错误体原样回传。
   */
  @GetMapping("/admin/orders")
  public ResponseEntity<byte[]> orders(@RequestParam Map<String, Object> query, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.orders(query, headers(request, true)));
  }
  /**
   * GET /admin/orders/{orderId}：由订单服务执行并再次校验后台身份。
   *
   * <p>订单详情，权限 staff。返回订单快照、支付/退款记录、售后与交付留痕；
   * 若订单不属于任何记录则订单服务回 404。
   *
   * <p>只读、幂等。注意详情里可能含买家联系方式与地址，属于后台可见的敏感数据，
   * 本服务不做二次脱敏——脱敏责任在订单服务的投影层。
   */
  @GetMapping("/admin/orders/{orderId}")
  public ResponseEntity<byte[]> order(@PathVariable("orderId") String orderId, @RequestParam Map<String, Object> query, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.order(orderId, query, headers(request, true)));
  }
  /**
   * POST /admin/pickups/lookup：由订单服务执行并再次校验后台身份。
   *
   * <p>自提核销第一步：店员凭 {@code pickupCode} 或 {@code qrPayload}（二选一）查单。
   * 必须用 POST 而不是 GET，因为凭证不能进 URL（会留在访问日志与浏览器历史）。
   *
   * <p>权限 staff。虽然方法名是查询，但仍走远程写通道按原样转发；命中固定 200
   * （即便当前不可交付也返回 200 + 原因），凭证无效 404，同一员工连续查空会被 429 限流。
   *
   * <p>幂等：只读查询，重复调用无副作用，不要求 {@code Idempotency-Key}。
   */
  @PostMapping("/admin/pickups/lookup")
  public ResponseEntity<byte[]> lookup(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.lookup(body == null ? Map.of() : body, headers(request, true)));
  }
  /**
   * POST /admin/orders/{orderId}/pickup：由订单服务执行并再次校验后台身份。
   *
   * <p>自提核销第二步（终态动作）：权限 staff。请求体需带 {@code confirmationId}
   * （与买家现场确认单一致）、{@code pickupCode} 或 {@code qrPayload} 之一、
   * {@code quarantineVerified=true}；通过后订单转 {@code completed}、宠物转 {@code sold}、
   * 自提码立即清空。员工身份下订单服务会收紧字段白名单，禁止传检疫材料，
   * 防止绕过店长的检疫核验。
   *
   * <p><b>必须携带 {@code Idempotency-Key}</b>：交付是终态，网络重试只能回放首次结果，
   * 不能二次核销。冲突（已被核销/凭证不符）由订单服务返回 409/422。
   */
  @PostMapping("/admin/orders/{orderId}/pickup")
  public ResponseEntity<byte[]> pickup(@PathVariable("orderId") String orderId, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.pickup(orderId, body == null ? Map.of() : body, headers(request, true)));
  }
  /**
   * GET /admin/after-sales：由订单服务执行并再次校验管理员身份。
   *
   * <p>售后工单列表，权限 admin（员工 403）——退款与换宠涉及资金和库存，只允许店长查看决策。
   * 筛选参数原样透传（状态、类型、分页等）。
   *
   * <p>只读、幂等。注意这里的"再次校验"不是冗余：直接访问 order-server 绕过本服务时，
   * 它同样会拒绝员工。
   */
  @GetMapping("/admin/after-sales")
  public ResponseEntity<byte[]> afterSales(@RequestParam Map<String, Object> query, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.afterSales(query, headers(request)));
  }
  /**
   * GET /admin/after-sales/{saleId}：由订单服务执行并再次校验管理员身份。
   *
   * <p>单笔售后详情，权限 admin。含凭证材料、审核轨迹、退款与换宠子流程状态；
   * 不存在返回 404。只读、幂等。
   */
  @GetMapping("/admin/after-sales/{saleId}")
  public ResponseEntity<byte[]> afterSale(@PathVariable("saleId") String saleId, @RequestParam Map<String, Object> query, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.afterSale(saleId, query, headers(request)));
  }
  /**
   * PUT /admin/after-sales/{saleId}/exchange：由订单服务执行并再次校验管理员身份。
   *
   * <p>发起换宠（而非退款），权限 admin。订单服务会校验原宠物状态、新宠物可售性与差价，
   * 并把库存锁从旧宠物转移到新宠物；任一步不满足返回 409 且不产生部分写入。
   *
   * <p>写操作、非终态：失败后可由店长修正参数重试；携带的 {@code Idempotency-Key}
   * 会被透传，重复提交由订单服务去重。
   */
  @PutMapping("/admin/after-sales/{saleId}/exchange")
  public ResponseEntity<byte[]> exchange(@PathVariable("saleId") String saleId, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.exchange(saleId, body == null ? Map.of() : body, headers(request)));
  }
  /**
   * POST /admin/after-sales/{saleId}/review：由订单服务执行并再次校验管理员身份。
   *
   * <p>售后审核裁决（通过/驳回），权限 admin。审批会决定后续走退款、换宠还是关闭，
   * 因此订单服务要求该工单处于待审核状态，重复审核返回 409。
   *
   * <p>幂等性：审核是有状态的写操作，重复提交不产生第二笔退款；仍建议带
   * {@code Idempotency-Key} 以便网络重试时回放首次结果。
   */
  @PostMapping("/admin/after-sales/{saleId}/review")
  public ResponseEntity<byte[]> review(@PathVariable("saleId") String saleId, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.review(saleId, body == null ? Map.of() : body, headers(request)));
  }
  /**
   * POST /admin/after-sales/{saleId}/returns：由订单服务执行并再次校验管理员身份。
   *
   * <p>登记买家寄回/送回原宠物，权限 admin。这是退款前置条件：未收到退回的宠物不能执行退款，
   * 否则会出现"钱退了、宠物没回来"。订单服务会记录退回时间与验收结论，宠物可能因此
   * 转为不可售状态。
   *
   * <p>写操作，重复登记同一工单按状态冲突处理（409）；建议带 {@code Idempotency-Key}。
   */
  @PostMapping("/admin/after-sales/{saleId}/returns")
  public ResponseEntity<byte[]> receiveReturn(@PathVariable("saleId") String saleId, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.receiveReturn(saleId, body == null ? Map.of() : body, headers(request)));
  }
  /**
   * POST /admin/after-sales/{saleId}/exchange-delivery：由订单服务执行并再次校验管理员身份。
   *
   * <p>换宠交付完成，权限 admin。把新宠物交付给买家并释放/切换库存锁，
   * 同时记录检疫与交付留痕；要求换宠流程已到达"可交付"状态，否则 409。
   *
   * <p>终态动作，不可回滚；重复提交由状态校验挡住（不会二次交付）。
   */
  @PostMapping("/admin/after-sales/{saleId}/exchange-delivery")
  public ResponseEntity<byte[]> deliverExchange(@PathVariable("saleId") String saleId, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.deliverExchange(saleId, body == null ? Map.of() : body, headers(request)));
  }
  /**
   * POST /admin/after-sales/{saleId}/exchange-refund：由订单服务执行并再次校验管理员身份。
   *
   * <p>换宠失败转退款（例如新宠物检疫不合格），权限 admin。订单服务会按原支付渠道发起退款，
   * 因此要求订单处于已支付/已退款中状态，且金额不超过实付。
   *
   * <p>写操作，非幂等语义由订单服务以工单状态约束：已退款的工单再次调用返回 409，
   * 不会重复出款。建议携带 {@code Idempotency-Key} 以覆盖超时重试。
   */
  @PostMapping("/admin/after-sales/{saleId}/exchange-refund")
  public ResponseEntity<byte[]> refundExchange(@PathVariable("saleId") String saleId, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.refundExchange(saleId, body == null ? Map.of() : body, headers(request)));
  }
  /**
   * POST /admin/refunds/{refundId}/retry：由订单服务执行并再次校验管理员身份。
   *
   * <p>人工重试一笔失败的退款，权限 admin。仅当退款单处于可重试状态时有效，
   * 已成功或正在处理中的退款返回 409——避免重复出款。
   *
   * <p>写操作，涉及资金：本服务只转发，不与微信支付直接交互，也不吞掉上游错误码；
   * 上游限流时会带 {@code Retry-After}，前端应据此提示等待。
   */
  @PostMapping("/admin/refunds/{refundId}/retry")
  public ResponseEntity<byte[]> retryRefund(@PathVariable("refundId") String refundId, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.retryRefund(refundId, body == null ? Map.of() : body, headers(request)));
  }
  /**
   * GET /admin/dashboard：由订单服务执行并再次校验管理员身份。
   *
   * <p>经营看板（成交额、订单量、待处理售后等），权限 admin。统计口径与时间范围
   * 由订单服务的 {@code query} 参数决定，本服务原样透传。
   *
   * <p>只读、幂等；数据量大时由下游自行控制查询范围，本层不加缓存，
   * 避免 admin-server 与 order-server 出现两份不一致的统计。
   */
  @GetMapping("/admin/dashboard")
  public ResponseEntity<byte[]> dashboard(@RequestParam Map<String, Object> query, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.dashboard(query, headers(request)));
  }
}
