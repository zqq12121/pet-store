package com.warmpaw.common;

import static com.warmpaw.common.ApiException.require;
import java.time.*;
import java.util.regex.Pattern;

/** 门店当前采用每天相同的营业时段；拒绝模糊文本，避免默许闭店时间预约。 */
public final class AppointmentHours {
  public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final Pattern HOURS = Pattern.compile(
      "^(?:(?:每天|每日|周一至周日|周一到周日)\\s*)?([0-2][0-9]:[0-5][0-9])\\s*[-–—至]\\s*([0-2][0-9]:[0-5][0-9])$");
  private AppointmentHours() {}

  public static LocalTime[] parse(String value) {
    var match = HOURS.matcher(value == null ? "" : value.trim());
    require(match.matches(), 422, "SHOP_HOURS_REQUIRED", "请店长将营业时间设置为每天 HH:mm-HH:mm，例如每天 10:00-20:00");
    try {
      LocalTime start = LocalTime.parse(match.group(1)), end = LocalTime.parse(match.group(2));
      require(start.isBefore(end), 422, "SHOP_HOURS_REQUIRED", "营业结束时间必须晚于开始时间");
      return new LocalTime[] {start, end};
    } catch (DateTimeException e) {
      throw new ApiException(422, "SHOP_HOURS_REQUIRED", "营业时间格式无效");
    }
  }

  public static Instant validateVisit(String value, String hours, Instant now) {
    Instant visit;
    try { visit = OffsetDateTime.parse(value).toInstant(); }
    catch (DateTimeException e) { throw new ApiException(400, "VALIDATION_ERROR", "到店时间须包含时区"); }
    require(visit.isAfter(now) && !visit.isAfter(now.plus(Duration.ofDays(7))),
        400, "VALIDATION_ERROR", "请选择未来7天内的到店时间");
    LocalTime[] range = parse(hours);
    LocalTime local = visit.atZone(ZONE).toLocalTime();
    require(!local.isBefore(range[0]) && local.isBefore(range[1]),
        400, "VALIDATION_ERROR", "请选择门店营业时间内的到店时间（北京时间）");
    return visit;
  }
}
