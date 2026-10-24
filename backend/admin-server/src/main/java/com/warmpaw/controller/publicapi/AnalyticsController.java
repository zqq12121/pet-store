package com.warmpaw.controller.publicapi;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.AnalyticsApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 访问事件上报接口：仅适配 HTTP，具体业务在 AnalyticsApplicationService（调用方：买家/游客）。
 *
 * <p>路由前缀 {@code /api/v1}，路径 {@code /analytics/events}。
 *
 * <p><b>权限模型：{@link AccessLevel#VISITOR}</b>，语义比 PUBLIC 严格：
 * 必须能识别出"买家（Bearer）或游客（X-Guest-Token）"之一，两者都没有时 401
 * {@code UNAUTHORIZED}；同时提供两种身份则 400（{@code ApiRequestExecutor} 判定）。
 * 这样统计既覆盖登录用户，也覆盖未登录访客，但不会收下无法归属的匿名数据。
 *
 * <p>委托 {@link AnalyticsApplicationService}：事件去重、路径脱敏与时间窗校验都在那里。
 */
@RestController
@RequestMapping("/api/v1")
public class AnalyticsController {
  private final ApiRequestExecutor requests;
  private final AnalyticsApplicationService service;

  public AnalyticsController(ApiRequestExecutor requests, AnalyticsApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /**
   * POST /analytics/events；访问级别：VISITOR（买家或游客身份，二者其一）。
   *
   * <p>批量上报访问事件，<b>成功返回 202</b> 而非 200——服务端只是受理，不做同步聚合，
   * body 为 {@code acceptedCount/duplicateCount}。请求体只有 {@code events} 一个字段，
   * 必须是 1~20 条的数组且序列化后 ≤32KB，否则 400。
   *
   * <p>每条事件：{@code eventId} 必须是 UUID（同时作为幂等键，按"身份+eventId"去重，
   * 重复上报计入 {@code duplicateCount} 而不报错）；{@code eventType} 限
   * page_view/pet_detail_view/search；{@code pagePath} ≤200 且不得含 {@code ?}/{@code #}
   * 或 "orders"（防止把订单号等敏感参数带进统计）；{@code occurredAt} 与服务器时间相差
   * 不得超过 24 小时。{@code properties} 的字段随类型而定。
   *
   * <p>幂等：同一批事件重复提交不会重复计数（eventId 去重），可安全重试。
   */
  @PostMapping("/analytics/events")
  public ResponseEntity<Object> recordEvents(
      @RequestBody(required = false) Map<String, Object> requestBody, HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.VISITOR,
        false,
        context -> service.recordEvents(body, context.actor()));
  }
}
