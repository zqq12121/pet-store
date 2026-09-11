package com.warmpaw.common;

import static com.warmpaw.common.ApiException.require;

import java.util.*;

/** 请求白名单校验：状态、归属与交易金额不能通过额外字段写入。 */
public final class Input {
  private final Map<String, Object> data;

  public Input(Map<String, Object> data, String fields) {
    this.data = data == null ? new LinkedHashMap<>() : data;
    Set<String> allowed = new HashSet<>(Arrays.asList(fields.split(",")));
    require(
        this.data.keySet().stream().allMatch(allowed::contains),
        400,
        "VALIDATION_ERROR",
        "存在不允许提交的字段");
  }

  public String str(String key, int min, int max) {
    Object v = data.get(key);
    require(v instanceof String, 400, "VALIDATION_ERROR", key + "必须为字符串");
    String s = ((String) v).trim();
    require(s.length() >= min && s.length() <= max, 400, "VALIDATION_ERROR", key + "长度不正确");
    return s;
  }

  public String optional(String key, int max) {
    return data.get(key) == null ? null : str(key, 0, max);
  }

  public long integer(String key, long min, long max) {
    Object v = data.get(key);
    require(
        v instanceof Number
            && Double.isFinite(((Number) v).doubleValue())
            && ((Number) v).doubleValue() == ((Number) v).longValue(),
        400,
        "VALIDATION_ERROR",
        key + "必须为整数");
    long n = ((Number) v).longValue();
    require(n >= min && n <= max, 400, "VALIDATION_ERROR", key + "超出范围");
    return n;
  }

  public double decimal(String key, double min, double max) {
    Object v = data.get(key);
    require(v instanceof Number, 400, "VALIDATION_ERROR", key + "必须为数字");
    double n = ((Number) v).doubleValue();
    require(Double.isFinite(n) && n >= min && n <= max, 400, "VALIDATION_ERROR", key + "超出范围");
    return n;
  }

  public String choice(String key, String options) {
    String s = str(key, 1, 100);
    require(Arrays.asList(options.split(",")).contains(s), 400, "VALIDATION_ERROR", key + "取值不正确");
    return s;
  }

  public boolean bool(String key) {
    require(data.get(key) instanceof Boolean, 400, "VALIDATION_ERROR", key + "必须为布尔值");
    return Boolean.TRUE.equals(data.get(key));
  }

  public void yes(String key) {
    require(bool(key), 400, "VALIDATION_ERROR", key + "必须确认");
  }

  public List<String> ids(String key, int min, int max) {
    Object v = data.get(key);
    require(v instanceof List<?>, 400, "VALIDATION_ERROR", key + "必须为数组");
    List<?> list = (List<?>) v;
    require(
        list.size() >= min
            && list.size() <= max
            && list.stream()
                .allMatch(
                    x ->
                        x instanceof String
                            && !((String) x).isBlank()
                            && ((String) x).length() <= 64),
        400,
        "VALIDATION_ERROR",
        key + "内容不正确");
    return list.stream().map(Object::toString).toList();
  }

  public String phone(String key) {
    String s = str(key, 11, 11);
    require(s.matches("^1[3-9][0-9]{9}$"), 400, "VALIDATION_ERROR", "手机号格式不正确");
    return s;
  }

  public boolean has(String key) {
    return data.containsKey(key);
  }

  public Map<String, Object> data() {
    return data;
  }
}
