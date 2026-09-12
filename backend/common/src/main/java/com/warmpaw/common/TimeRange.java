package com.warmpaw.common;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.text;

import java.time.*;
import java.util.Map;

/** 列表查询的时间范围校验与过滤，统一使用同一套边界规则。 */
public final class TimeRange {
  private TimeRange() {}

  public static void validateRange(Map<String, Object> q, String from, String to) {
    Instant start = q.get(from) == null ? null : parseInstant(text(q, from)),
        end = q.get(to) == null ? null : parseInstant(text(q, to));
    if (start != null && end != null)
      require(
          !start.isAfter(end) && Duration.between(start, end).toDays() <= 366,
          400,
          "VALIDATION_ERROR",
          "时间范围须按先后顺序且不超过366天");
  }

  public static boolean inRange(
      Map<String, Object> row, String field, Map<String, Object> q, String from, String to) {
    Instant value = Instant.parse(text(row, field));
    return (q.get(from) == null || !value.isBefore(parseInstant(text(q, from))))
        && (q.get(to) == null || !value.isAfter(parseInstant(text(q, to))));
  }

  public static Instant parseInstant(String value) {
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (Exception e) {
      throw new ApiException(400, "VALIDATION_ERROR", "时间须为带时区的 ISO 8601 格式");
    }
  }
}
