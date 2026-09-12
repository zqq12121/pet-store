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

/** 统计用例：访问事件去重、字段校验与后台经营汇总。 */
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
