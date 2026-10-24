package com.warmpaw.common;

import static com.warmpaw.common.ApiException.require;
import java.time.*;
import java.util.regex.Pattern;

/**
 * 门店当前采用每天相同的营业时段；拒绝模糊文本，避免默许闭店时间预约。
 *
 * <p><b>为什么只支持"每天同一时段"</b>：真正的排班表要处理节假日、午休、临时闭店，
 * 数据结构和前端都要复杂一个量级。当前门店是单店夫妻店形态，用一条
 * {@code 每天 10:00-20:00} 足以覆盖，且能给出明确的校验错误——比支持一堆格式但都不校验更安全。
 *
 * <p><b>关键约束：不支持跨天</b>。{@link #parse} 要求开始时间<b>严格早于</b>结束时间，
 * 所以 {@code 22:00-02:00} 这类夜班营业会被 422 拒绝，而不是被当成"到次日凌晨"。
 * 若将来要支持，必须先在 {@link #validateVisit} 里改掉"同一天内比较"的假设
 * （现在的实现只取到店时刻的 {@code LocalTime}，丢掉日期，无法判断跨天）。
 *
 * <p>时区固定为 {@code Asia/Shanghai} 而不是系统默认：营业时间语义是"北京时间几点"，
 * 服务器跑在 UTC 容器里也必须按北京判断。
 */
public final class AppointmentHours {
  /** 全项目判断"今天""几点"的统一时区，避免各处用 {@code ZoneId.systemDefault()} 导致不一致。 */
  public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  /**
   * 营业时间文本格式：可选前缀（每天/每日/周一至周日/周一到周日）+ {@code HH:mm} +
   * 分隔符（{@code - – — 至}）+ {@code HH:mm}。
   *
   * <p>小时用 {@code [0-2][0-9]} 是宽松匹配，会放过 {@code 24:00}~{@code 29:59}；
   * 这些值随后在 {@code LocalTime.parse} 处失败并转成 422。写严正则不如让解析器当第二道闸。
   *
   * <p>前缀是可选的：{@code 10:00-20:00} 也合法，等价于"每天"。
   */
  private static final Pattern HOURS = Pattern.compile(
      "^(?:(?:每天|每日|周一至周日|周一到周日)\\s*)?([0-2][0-9]:[0-5][0-9])\\s*[-–—至]\\s*([0-2][0-9]:[0-5][0-9])$");
  private AppointmentHours() {}

  /**
   * 解析营业时间文本，返回 {@code [开始, 结束]} 两个 {@link LocalTime}。
   *
   * <p>输入 {@code null} 按空串处理并直接 422——{@code null} 表示"门店还没配置营业时间"，
   * 此时不允许任何预约（而不是默认"全天营业"，那会放出闭店时段的到店请求）。
   *
   * <p>用 422 而不是 400：这是"门店配置不满足业务前提"，不是请求字段格式错。
   * 文案直接告诉店长该怎么填，因为这个方法的错误最终是给后台配置页看的。
   *
   * <p>失败分两级：不匹配正则 → "请设置为每天 HH:mm-HH:mm"；
   * 匹配但解析失败（如 {@code 24:00}）→ "营业时间格式无效"；开始不早于结束 → 单独的提示。
   */
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

  /**
   * 校验"到店时间"并原样返回其 {@link Instant}。
   *
   * <p>四步，任何一步不过都 400：
   *
   * <ol>
   *   <li><b>必须带时区</b>——用 {@code OffsetDateTime.parse}，所以 {@code 2026-05-01T10:00:00}
   *       这种没有偏移量的写法会被拒。要求带时区是为了消除"客户端按哪个时区理解"的歧义；
   *   <li><b>必须是未来</b>（严格大于 now，等于当前时刻不允许）；
   *   <li><b>不超过 7 天</b>——上限防止用户提前几个月锁定时段，也让门店排班可控；
   *   <li><b>落在营业时段内</b>——先换算成北京时间再取 {@code LocalTime}，
   *       所以只要绝对时刻对得上，客户端用什么时区表达都行。
   * </ol>
   *
   * <p><b>区间是左闭右开</b>：{@code start <= t < end}。开始时刻可以到店，结束时刻不行
   * （{@code 20:00} 关门，20:00 到店已经太晚）。这与 {@code parse} 不允许 start==end 一起，
   * 保证了区间非空。
   *
   * <p><b>只看时刻不看日期</b>：只要落在营业时段内，任何一天都放行——包括今天已过去的时段
   * 由第 2 步的未来校验兜住；也没有排除法定节假日（门店自行在公告里说明）。
   *
   * <p>返回的是解析后的 {@link Instant}，让调用方能直接用同一个值落库，避免二次解析出偏差。
   */
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
