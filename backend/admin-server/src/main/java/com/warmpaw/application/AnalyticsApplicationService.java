package com.warmpaw.application;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.ApiException;
import com.warmpaw.common.Input;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.AuthService;
import com.warmpaw.service.CatalogService;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 埋点上报用例：接收前端批量上报的访问事件。
 *
 * <p><b>这是一个"不可信输入"接口</b>：数据来自浏览器，任何字段都可能是伪造或污染的。
 * 因此校验做得比常规接口更严，且全部是"拒绝"而非"修正"：
 *
 * <ul>
 *   <li>每批 1~20 条，整包 UTF-8 不超过 32KB（限制体积，防大包拖慢写库）；
 *   <li>{@code eventId} 必须是 36 位 UUID——它同时是幂等键，格式不合法就无法去重；
 *   <li>{@code pagePath} 不能带 {@code ?}、{@code #}，也不能包含 {@code "orders"}
 *       ——订单路径里含订单号与手机号等隐私，绝不能进埋点表；
 *   <li>{@code occurredAt} 与服务器时间偏差不得超过 24 小时，防止伪造历史时间污染统计；
 *   <li>{@code properties} 按事件类型使用三套不同白名单，不允许自由扩展字段。
 * </ul>
 *
 * <p><b>去重键是 {@code userId:eventId}</b>：同一用户重复上报同一条事件只算一次（计入
 * {@code duplicateCount} 但不报错）。因此客户端重试是安全的。
 *
 * <p><b>返回 202 而不是 200</b>：语义是"已接受"，前端不需要等待处理结果，
 * 也不该因为一次失败而阻塞页面交互。
 *
 * <p>上报 {@code pet_detail_view} 时会调 {@code catalog.publicPet(petId)} 校验宠物真实存在，
 * 避免伪造浏览量为不存在的商品刷榜。
 */
@Service
@org.springframework.transaction.annotation.Transactional(
    propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
public class AnalyticsApplicationService {
  private final BusinessRepository store;
  private final CatalogService catalog;

  public AnalyticsApplicationService(BusinessRepository store, CatalogService catalog) {
    this.store = store;
    this.catalog = catalog;
  }

  /**
   * 批量写入访问事件。
   *
   * <p>三条事件类型与各自允许的 {@code properties}：
   *
   * <ul>
   *   <li>{@code page_view} —— {@code page}（home/list/detail/other）+ {@code pathDepth}；
   *   <li>{@code pet_detail_view} —— {@code pathDepth}，且 {@code petId} 必须存在；
   *   <li>{@code search} —— 仅 {@code keyword}（1~50 字），不记录结果集，避免泄露商品供给情况。
   * </ul>
   *
   * <p>逐条处理而非整批失败：遇到重复事件跳过并计数，其它非法事件仍然抛错（因为白名单
   * 校验失败说明客户端实现有 bug，静默丢弃会让数据缺口无从发现）。
   */
  public OperationResult recordEvents(Map<String, Object> b, AuthService.Actor a) {
    Input in = new Input(b, "events");
    require(
        b.get("events") instanceof List<?>
            && !objects(b, "events").isEmpty()
            && objects(b, "events").size() <= 20
            && com.warmpaw.common.Json.write(b)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    .length
                <= 32768,
        400,
        "VALIDATION_ERROR",
        "事件批次不正确");
    int accepted = 0, duplicate = 0;
    for (Map<String, Object> e : objects(b, "events")) {
      Input ei = new Input(e, "eventId,eventType,occurredAt,pagePath,petId,properties");
      String event = ei.str("eventId", 36, 36);
      try {
        UUID.fromString(event);
      } catch (Exception ex) {
        throw new ApiException(400, "VALIDATION_ERROR", "eventId必须为UUID");
      }
      String type = ei.choice("eventType", "page_view,pet_detail_view,search"),
          path = ei.str("pagePath", 1, 200);
      com.warmpaw.common.ProviderSupport.safePath(path);
      // 查询串与订单路径可能带手机号/订单号，一律拒收，从源头杜绝隐私进埋点表。
      require(
          !path.contains("?") && !path.contains("#") && !path.contains("orders"),
          400,
          "VALIDATION_ERROR",
          "页面路径不能包含敏感参数");
      Instant at = com.warmpaw.common.TimeRange.parseInstant(ei.str("occurredAt", 1, 40));
      require(
          Math.abs(Duration.between(at, Instant.now()).toSeconds()) <= 86400,
          400,
          "VALIDATION_ERROR",
          "事件时间超出范围");
      Map<String, Object> props = object(e, "properties");
      Input pi =
          new Input(
              props,
              type.equals("search")
                  ? "keyword"
                  : type.equals("page_view") ? "page,pathDepth" : "pathDepth");
      if (type.equals("search")) pi.str("keyword", 1, 50);
      else {
        pi.integer("pathDepth", 0, 20);
        if (type.equals("page_view")) pi.choice("page", "home,list,detail,other");
      }
      if (type.equals("pet_detail_view")) catalog.publicPet(ei.str("petId", 1, 64));
      if (store.byKey("event", a.id() + ":" + event) != null) {
        duplicate++;
        continue;
      }
      store.create("event", a.id(), a.id() + ":" + event, copy(e));
      accepted++;
    }
    return new OperationResult(202, map("acceptedCount", accepted, "duplicateCount", duplicate));
  }

}
