package com.warmpaw.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 数据库 JSON 与快照使用同一序列化方式；不把内部实体直接返回给前端。
 *
 * <p>全项目的"实体"就是 {@code Map<String,Object>}，本类是这些 Map 与 JSON 文本之间唯一的转换点：
 * 写库（{@code relational-model.json} 里标记 {@code json:true} 的列）、幂等快照、
 * 请求体规范化哈希，全部走这里。统一的好处是"同一种值在库里和内存里长得一样"。
 *
 * <p><b>失败策略</b>：{@link #write}/{@link #read} 只抛 {@link IllegalStateException}。
 * 序列化失败意味着代码里塞了不可序列化的对象，属于编程错误，应当 500 而不是被包装成 4xx。
 *
 * <p><b>取值方法的容忍度并不一致</b>（这直接影响业务代码健壮性）：
 *
 * <ul>
 *   <li>{@link #text} —— 键缺失或值为 null 都返回 null，其余一律 {@code toString()}；
 *   <li>{@link #number} —— 键缺失返回 0，但值若是字符串会抛 {@code ClassCastException}；
 *   <li>{@link #object}/{@link #strings}/{@link #objects} —— 类型不符时返回<b>空集合</b>，
 *       是三者里最宽容的，适合"可选结构"。
 * </ul>
 *
 * <p>配合 {@code RelationalRepository.hydrate} 的"null 字段不落 Map"，可以认为：
 * 缺字段 → {@code text} 得 null、{@code number} 得 0、集合类得空集合，业务代码无需判空。
 */
public final class Json {
  /** 无特殊配置的 ObjectMapper：不忽略 null、不排序键、数值按类型输出。 */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 工具类，禁止实例化。 */
  private Json() {}

  /**
   * 任意对象 → JSON 文本。null 变成字面量 {@code null}（不是空串）。
   *
   * <p>Map 的键顺序就是插入顺序（{@link LinkedHashMap} 语义）；这个顺序会影响
   * {@link #hash} 的结果，所以需要"内容相同即哈希相同"的场合必须先用 {@link #canonical}。
   */
  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * JSON 对象文本 → {@link LinkedHashMap}（<b>保留原键顺序</b>）。
   *
   * <p>契约限定"顶层必须是对象"：传数组、字符串、数字都会失败（Jackson 的
   * {@code TypeReference<LinkedHashMap>} 不接受其它顶层形态），并抛 {@code IllegalStateException}。
   * 需要解析"任意 JSON 值"的场景（比如 {@code RelationalRepository} 解 JSON 列）要自己包一层
   * {@code {"value": ...}}，见那里的说明。
   *
   * <p>返回的 Map 里 <b>null 值仍然存在</b>（{@code containsKey} 为 true），
   * 与 hydrate 的"跳过 null"不同——两者容易混淆。
   */
  public static Map<String, Object> read(String value) {
    try {
      return MAPPER.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 深拷贝（走一次序列化往返）。
   *
   * <p>用途是让"校验/补齐字段"不污染调用方传进来的请求体——例如
   * {@code CatalogReader.quarantine} 返回副本而不是入参。
   *
   * <p>两个副作用要知道：
   *
   * <ul>
   *   <li>只适用于 Map；其它类型（List、字符串）会在 {@link #read} 处失败；
   *   <li>数值类型会被 Jackson 重新推断（{@code Integer} 可能变 {@code Integer}、
   *       大数可能变 {@code Long}），引用相等性与具体包装类型不保证保持。
   * </ul>
   */
  public static Map<String, Object> copy(Map<String, Object> value) {
    return read(write(value));
  }

  /**
   * 用"键, 值, 键, 值..."的变参快速构造有序 Map（业务代码里到处都是）。
   *
   * <p>不做任何校验：参数个数为奇数会抛 {@code ArrayIndexOutOfBoundsException}，
   * 键不是字符串会抛 {@code ClassCastException}。这是有意的——这些错误都是写错的字面量，
   * 应该尽早以 500 暴露，而不是被吞掉。
   *
   * <p>值可以显式传 null，此时键<b>存在于 Map 中</b>。这个区别很关键：
   * {@code map("avatarUrl", null)} 用于"显式清空"，而"根本不放这个键"才表示"不改动/未提供"。
   */
  public static Map<String, Object> map(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }

  /**
   * SHA-256 十六进制摘要（小写，64 字符）。
   *
   * <p>全项目的"安全哈希"统一入口：会话 token 摘要、验证码摘要、游客 token 摘要、
   * 幂等作用域、请求体指纹都用它。
   *
   * <p>注意它<b>不是</b>密码哈希——密码走 PBKDF2（见 {@code AuthService.passwordHash}）。
   * 这里之所以够用：token/验证码本身是 {@code SecureRandom} 生成的高熵值，
   * 不存在字典攻击面；而密码是低熵的人类输入，必须加盐慢哈希。
   */
  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 规范化序列化：递归把 Map 的键排成字典序（{@link TreeMap}），再输出 JSON。
   *
   * <p><b>为什么需要它</b>：幂等键的 {@code body_hash} 必须"内容相同即哈希相同"。
   * 而 JSON 对象本身无序，同一个请求体经不同客户端/不同代码路径构造，键顺序可能不同，
   * 直接 {@link #write} 会得到两个不同的哈希——同一个幂等键重放时会被误判为
   * "请求内容不同"（409 IDEMPOTENCY_CONFLICT）。
   *
   * <p><b>只规范键序，不规范值</b>：数组顺序保留（顺序本身有业务含义），
   * 数值 {@code 1} 与 {@code 1.0} 仍输出不同（不做数值等价），
   * 所以"语义相同但写法不同"的请求仍可能被当成不同请求——这是可接受的严格。
   *
   * <p>顶层不是 Map 时原样 {@link #write}（幂等场景的请求体总是对象）。
   */
  public static String canonical(Object value) {
    if (value instanceof Map<?, ?> m) {
      Map<String, Object> sorted = new TreeMap<>();
      m.forEach((k, v) -> sorted.put(k.toString(), canonicalValue(v)));
      return write(sorted);
    }
    return write(value);
  }

  /** {@link #canonical} 的递归实现：Map 排序、List 逐项下钻（顺序不变）、标量原样。 */
  private static Object canonicalValue(Object value) {
    if (value instanceof Map<?, ?> m) {
      Map<String, Object> sorted = new TreeMap<>();
      m.forEach((k, v) -> sorted.put(k.toString(), canonicalValue(v)));
      return sorted;
    }
    if (value instanceof List<?> l) return l.stream().map(Json::canonicalValue).toList();
    return value;
  }

  /**
   * 生成 {@code <prefix>_<32位无横线UUID>} 形态的 id。
   *
   * <p>前缀不是装饰：{@code RelationalRepository.kindOf} 靠它反推实体类型，
   * 所以 <b>前缀必须与模型里的 kind 完全一致</b>，否则 update 会找错表。
   *
   * <p>用 UUIDv4 而不是自增：id 会出现在 URL 里，自增 id 等于把业务量暴露给外部，
   * 也让越权枚举变得容易（尽管权限校验才是根本防线）。
   */
  public static String id(String prefix) {
    return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
  }

  /**
   * 取字符串值：缺失或 null 都返回 null，其余转 {@code toString()}。
   *
   * <p>注意这个"宽容"是单向的：数字 {@code 123} 会得到 {@code "123"}，这通常正是想要的
   * （数据库里的 id 可能被驱动读成数字）；但<b>它不会把空串变成 null</b>，
   * 所以"是否为空"仍需调用方自己 {@code isBlank()} 判断。
   *
   * <p>这是全项目调用最多的取值方法，也是大量 {@code text(x, k) == null} 判断的基础。
   */
  public static String text(Map<String, Object> m, String key) {
    Object value = m.get(key);
    return value == null ? null : value.toString();
  }

  /**
   * 取 long 值；<b>键缺失返回 0</b>（不是抛异常，也不是 -1）。
   *
   * <p>这个默认值有三个直接后果，写业务代码时必须意识到：
   *
   * <ul>
   *   <li>{@code number(pet,"priceAmount") > 0} 这类判断在字段缺失时自然为 false，
   *       即"没有价格 = 不可购买"，正好是安全的默认值；
   *   <li>无法区分"真的 0"与"没这个字段"，需要区分时必须自己用 {@code get} + {@code containsKey}；
   *   <li>值若被存成字符串（例如 JSON 列里手工写错），会抛 {@code ClassCastException}
   *       （{@code String} 不是 {@code Number}）→ 500。它只容忍"缺失"，不容忍"类型错"。
   * </ul>
   */
  public static long number(Map<String, Object> m, String key) {
    return ((Number) m.getOrDefault(key, 0)).longValue();
  }

  /**
   * 取嵌套对象；键缺失<b>或类型不是 Map</b> 时返回一个<b>新的空</b> {@code LinkedHashMap}。
   *
   * <p>"永远返回非 null 且可写"是这里的关键约定：调用方可以直接 {@code object(pet,"quarantine")}
   * 后接着 {@code .isEmpty()} 判断或往里面塞值，不必判空。代价是<b>写进去不会被持久化</b>——
   * 这个空 Map 是临时的，不属于原实体。
   *
   * <p>类型不对（比如存成了字符串）也静默返回空 Map，不抛异常：恶心数据表现为"没有这个对象"，
   * 而不是让整个接口 500。
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> object(Map<String, Object> m, String key) {
    return m.get(key) instanceof Map<?, ?>
        ? (Map<String, Object>) m.get(key)
        : new LinkedHashMap<>();
  }

  /**
   * 取字符串列表；缺失或类型不是 List 时返回 {@link List#of()}（不可变空列表）。
   *
   * <p>转换是<b>未检查的强转</b>：元素不是 String 也不会在这里报错，而是在真正取用时
   * 由调用点的隐式转换抛出 {@code ClassCastException}。对"自己写进去的数据"这是合理的信任，
   * 但不要拿它去解析外部输入。
   *
   * <p>返回的空列表不可变，调用方若要修改请自己复制——这避免了"以为改了实体、其实改了个空壳"。
   */
  @SuppressWarnings("unchecked")
  public static List<String> strings(Map<String, Object> m, String key) {
    return m.get(key) instanceof List<?> ? (List<String>) m.get(key) : List.of();
  }

  /**
   * 取对象列表；缺失或类型不是 List 时返回空列表（不可变）。
   *
   * <p>与 {@link #strings} 一样是未检查强转：元素不是 Map 时会在调用点失败。
   * 常见用途是遍历时间线、banner、子表列表——这些数据都由本项目自己写入，
   * 结构由模型层保证。
   */
  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> objects(Map<String, Object> m, String key) {
    return m.get(key) instanceof List<?> ? (List<Map<String, Object>>) m.get(key) : List.of();
  }
}
