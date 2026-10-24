package com.warmpaw.common;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.text;

import java.time.*;
import java.util.Map;

/**
 * 列表查询的时间范围校验与过滤，统一使用同一套边界规则。
 *
 * <p>"同一套规则"指：<b>两端都闭区间</b>（{@code from <= t <= to}），且允许只给一端
 * （开区间查询是常态：只看"最近三天"或"某天之后"）。
 *
 * <p>两个方法分工：{@link #validateRange} 在接口入口做<b>一次性校验</b>
 * （顺序、跨度上限），{@link #inRange} 在内存过滤时<b>逐行判断</b>。
 * 之所以分开，是因为查询走的是内存过滤（{@code store.list} + stream），
 * 校验只需要做一次，不需要每行都重复校验参数合法性。
 *
 * <p>时间一律要求带时区的 ISO-8601：字符串比较对时间无效（{@code +08:00} 与 {@code Z}
 * 的字典序不等于时间序），必须先解析成 {@link Instant}。
 */
public final class TimeRange {
  /** 工具类，禁止实例化。 */
  private TimeRange() {}

  /**
   * 校验查询区间：{@code from} 不晚于 {@code to}，且跨度不超过 366 天。
   *
   * <p>两端都可缺省；<b>只要缺一端就不做任何检查</b>——单边查询没有"顺序"问题，
   * 也不限制跨度（"某天之后的全部"是合法需求）。
   *
   * <p>366 天而不是 365：留一天余量，避免"按自然年查询"在闰年时被判超限。
   * 上限的作用是防止一次拉取过长时间区间的数据把内存打满。
   *
   * <p>注意 {@code isAfter} 用的是"不晚于"（相等合法）：同一天的 00:00~00:00 会通过校验，
   * 结果为单点区间，由过滤逻辑自然处理。
   */
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

  /**
   * 判断单行记录是否落在查询区间内（两端闭区间）。
   *
   * <p>前置条件：{@code row.field} 必须是带时区的 ISO-8601 字符串，否则 {@link Instant#parse}
   * 会抛 {@code DateTimeParseException}（未包装，会成为 500）。这条依赖由数据写入侧保证
   * （所有时间都由 `Instant.now().toString()` 生成）。
   *
   * <p>参数合法性交给 {@link #validateRange} 预先校验，这里<b>不再重复解析成本之外的保护</b>：
   * 每行都会重新解析 {@code from}/{@code to}，行数多时是纯浪费——目前只在千级以内使用。
   */
  public static boolean inRange(
      Map<String, Object> row, String field, Map<String, Object> q, String from, String to) {
    Instant value = Instant.parse(text(row, field));
    return (q.get(from) == null || !value.isBefore(parseInstant(text(q, from))))
        && (q.get(to) == null || !value.isAfter(parseInstant(text(q, to))));
  }

  /**
   * 解析带时区的 ISO-8601 时间串；格式不对统一 400。
   *
   * <p>用 {@code OffsetDateTime} 而不是 {@code Instant.parse}：后者只接受 {@code Z} 结尾，
   * 而浏览器/小程序传来的往往是 {@code +08:00}。这里放宽到任意偏移量，
   * 同时仍然拒绝不带偏移量的 {@code 2026-05-01T10:00:00}（那才是真正危险的模糊值）。
   * 与 {@code AppointmentHours.validateVisit} 使用同一种解析方式，保持一致。
   */
  public static Instant parseInstant(String value) {
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (Exception e) {
      throw new ApiException(400, "VALIDATION_ERROR", "时间须为带时区的 ISO 8601 格式");
    }
  }
}
