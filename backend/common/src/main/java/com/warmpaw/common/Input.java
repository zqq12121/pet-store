package com.warmpaw.common;

import static com.warmpaw.common.ApiException.require;

import java.util.*;

/**
 * 请求字段白名单 + 类型/范围校验器。
 *
 * <p><b>这是全项目最主要的 400 来源，也是最重要的安全边界。</b>所有 Controller 传入的请求体
 * 都会先经过这里，构造时就检查"有没有多余字段"：只要出现白名单之外的键，直接 400
 * {@code 存在不允许提交的字段}。
 *
 * <p>为什么用白名单而不是逐个校验：状态、归属、金额这类字段一旦允许客户端提交，
 * 就等于把业务规则交给了前端。比如 {@code status}、{@code ownerId}、{@code priceAmount}、
 * {@code refundedAmount} 都必须由服务端计算，绝不能出现在任何请求体里。
 * 白名单是"默认拒绝"，新增字段必须显式声明——这个方向比"默认允许再逐条拦"安全得多。
 *
 * <p>典型用法：
 *
 * <pre>
 *   Input in = new Input(body, "petId,contactName,agreementAccepted");
 *   in.yes("agreementAccepted");                       // 必须是 true
 *   String id = in.str("petId", 1, 64);                // 必填，长度 1~64
 *   String note = in.optional("remark", 500);          // 可选，可为 null
 * </pre>
 *
 * <p><b>语义约定</b>：
 *
 * <ul>
 *   <li>{@code str}/{@code integer}/{@code decimal}/{@code bool}/... 都是<b>必填</b>，
 *       缺失或类型不符直接抛 {@link ApiException}；
 *   <li>{@code optional} 在字段为 {@code null} 时返回 null，与"不传"等价；
 *   <li>字符串统一 {@code trim()} 后再校验长度，避免用空格绕过非空检查；
 *   <li>{@code has(key)} 只判断"键是否存在"，用于区分"显式传 null"与"未传"。
 * </ul>
 */
public final class Input {
  private final Map<String, Object> data;

  /**
   * 构造即校验字段白名单。
   *
   * <p>{@code fields} 是逗号分隔的允许字段名。传空串表示"不接受任何请求体字段"
   * （用于没有任何参数的 POST 接口）。{@code data} 为 null 时按空对象处理，
   * 这样调用方不必为 {@code null} 请求体单独分支。
   *
   * <p>注意：这个检查是<b>严格</b>的，多传一个字段就失败。前端如果习惯把整个表单对象
   * 连同 UI 状态一起 POST，会在接口层直接被拒——这是刻意设计，迫使前后端明确契约。
   */
  public Input(Map<String, Object> data, String fields) {
    this.data = data == null ? new LinkedHashMap<>() : data;
    Set<String> allowed = new HashSet<>(Arrays.asList(fields.split(",")));
    require(
        this.data.keySet().stream().allMatch(allowed::contains),
        400,
        "VALIDATION_ERROR",
        "存在不允许提交的字段");
  }

  /**
   * 必填字符串，长度闭区间 {@code [min, max]}。
   *
   * <p>先要求 {@code instanceof String}——不接受数字自动转字符串，
   * 因为 {@code 123} 和 {@code "123"} 在业务上往往含义不同（比如编号 vs 名称），
   * 静默转换会掩盖前端类型错误。
   *
   * <p>{@code trim()} 在长度校验之前执行，防止 {@code "   "} 这种全空格绕过 min=1 的检查。
   */
  public String str(String key, int min, int max) {
    Object v = data.get(key);
    require(v instanceof String, 400, "VALIDATION_ERROR", key + "必须为字符串");
    String s = ((String) v).trim();
    require(s.length() >= min && s.length() <= max, 400, "VALIDATION_ERROR", key + "长度不正确");
    return s;
  }

  /** 可选字符串：字段为 null（或缺失）时返回 null，否则按 {@code str(key, 0, max)} 校验。 */
  public String optional(String key, int max) {
    return data.get(key) == null ? null : str(key, 0, max);
  }

  /**
   * 必填整数（long），范围闭区间。
   *
   * <p>三重校验：必须是 {@link Number}、必须有限（排除 NaN/Infinity）、
   * 必须没有小数部分（{@code 1.0} 通过，{@code 1.5} 拒绝）。
   *
   * <p>为什么接受 {@code 1.0}：JSON 解析库常把整数值解析成 double，
   * 若严格要求 {@code Integer} 类型会让合法请求被误拒。但小数一律拒绝，
   * 避免金额/数量出现精度问题。
   */
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

  /** 必填小数，要求有限且在范围内（用于体重、经纬度这类确实需要小数的字段）。 */
  public double decimal(String key, double min, double max) {
    Object v = data.get(key);
    require(v instanceof Number, 400, "VALIDATION_ERROR", key + "必须为数字");
    double n = ((Number) v).doubleValue();
    require(Double.isFinite(n) && n >= min && n <= max, 400, "VALIDATION_ERROR", key + "超出范围");
    return n;
  }

  /**
   * 枚举值校验：取值必须在逗号分隔的 {@code options} 中。
   *
   * <p>用于协议类型、售后方案、支付场景这类"值域固定"的字段。
   * 错误信息只回"取值不正确"而不列出所有可选项，避免把内部枚举暴露给爬虫。
   */
  public String choice(String key, String options) {
    String s = str(key, 1, 100);
    require(Arrays.asList(options.split(",")).contains(s), 400, "VALIDATION_ERROR", key + "取值不正确");
    return s;
  }

  /** 必填布尔值。不接受字符串 {@code "true"}——前端传错类型应当立刻暴露，而不是被悄悄纠正。 */
  public boolean bool(String key) {
    require(data.get(key) instanceof Boolean, 400, "VALIDATION_ERROR", key + "必须为布尔值");
    return Boolean.TRUE.equals(data.get(key));
  }

  /**
   * "必须勾选确认"语义：值为 true 才通过。
   *
   * <p>用于协议同意、交付确认、检疫核验这类<b>不可默认为真</b>的开关。
   * 与 {@link #bool} 的区别是错误信息不同（"必须确认"vs"必须为布尔值"），
   * 前端可以据此区分"没勾"和"传了非法类型"。
   */
  public void yes(String key) {
    require(bool(key), 400, "VALIDATION_ERROR", key + "必须确认");
  }

  /**
   * 字符串 id 数组，元素数量与每个元素长度都受限。
   *
   * <p>三个限制：数组长度在 {@code [min, max]}、每个元素非空且不超过 64 字符、
   * 元素必须是字符串。用于图片/视频/材料的文件 id 列表。
   *
   * <p>为什么逐个校验而不是只校验整体：文件 id 会直接参与后续的归属查询，
   * 一个空串或超长串进到 SQL 参数里会造成无意义的全表扫描。
   */
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

  /**
   * 中国大陆手机号：11 位、{@code 1[3-9]} 开头。
   *
   * <p>只做格式校验，不做归属地或号段真实性判断（那需要运营商接口）。
   * 真正的"这个号是不是本人的"由短信验证码回答。
   */
  public String phone(String key) {
    String s = str(key, 11, 11);
    require(s.matches("^1[3-9][0-9]{9}$"), 400, "VALIDATION_ERROR", "手机号格式不正确");
    return s;
  }

  /**
   * 字段是否存在（含显式传 null）。
   *
   * <p>这个区别在多个地方影响业务：{@code avatarFileId: null} 表示"清空头像"，
   * 而完全不传表示"不改头像"；售后审核时"驳回不能携带 resolution"也要靠它判断。
   */
  public boolean has(String key) {
    return data.containsKey(key);
  }

  /** 返回底层 Map（只读用途）；调用方不应通过它写入未在白名单中的字段。 */
  public Map<String, Object> data() {
    return data;
  }
}
