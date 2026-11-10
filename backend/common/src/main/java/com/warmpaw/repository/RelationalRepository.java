package com.warmpaw.repository;

import static com.warmpaw.common.Json.*;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 业务 Map 与显式关系表的适配层；字段/表名只取打包的白名单，业务值全部参数绑定。
 *
 * <p><b>模型驱动</b>：映射关系不在代码里，而在打包进 jar 的 {@code relational-model.json}。
 * 每个模型声明 {@code kind}（业务类型，如 {@code pet}）、{@code table}、{@code fields}
 * （业务字段名 ↔ 数据库列名 ↔ SQL 类型 ↔ 是否 JSON 列）以及 {@code children}
 * （一对多的子表，含 {@code list}/{@code object}/{@code embedded} 三种形态）。
 * 启动时读一次，之后所有读写都按模型拼 SQL。
 *
 * <p><b>为什么不用 JPA</b>：全项目的实体都是 {@code Map<String,Object>}（见 {@code Json}），
 * 字段随业务演进而"半结构化"（订单快照、支付参数、售后时间线都是 JSON 列，没有固定 schema）。
 * 用 JPA 意味着一实体一 class、一个 JSON 列一个 AttributeConverter、外键关系一堆 {@code @OneToMany}，
 * 而 {@code byKey}/{@code kindOf} 这类"按业务键查任意类型"的入口照样得手写 SQL。
 * 这里选择"一个小 JSON 白名单 + 反射式的 Map 拼装"，代价是没有编译期校验，收益是加字段只改 JSON。
 *
 * <p><b>安全边界</b>：拼进 SQL 字符串的只有<b>表名、列名、排序方向</b>这类来自白名单的标识符；
 * 所有业务值一律走 {@code ?} 占位符参数绑定。因此不存在用户输入拼进 SQL 的路径——
 * 这也是 {@link #validate} 必须存在的理由：没有字段映射的属性一律拒绝，
 * 否则它会静默地写不进库（丢数据）或需要把字段名拼进 SQL（注入面）。
 *
 * <p><b>不带版本条件</b>：{@link #update} 的 WHERE 只有主键，乐观锁由业务层用
 * {@code version} 字段自行比对（见 {@code BusinessRepository.version}）。
 * 也就是说并发写是"后写覆盖先写"，只有显式做过 version 校验的接口才安全。
 */
@Repository
public class RelationalRepository {
  private final JdbcTemplate jdbc;
  /** 启动时一次性加载，之后只读；不随请求变化，所以无需同步。 */
  private final List<Map<String, Object>> models;

  /**
   * 从 classpath 读模型并解析成 List。
   *
   * <p>声明 {@code throws Exception} 是刻意的：模型缺失或 JSON 损坏属于<b>打包错误</b>，
   * 必须在启动时让容器起不来，而不是运行到第一个查询才 500。
   *
   * <p>用 try-with-resources 显式关闭 jar 内的 {@code InputStream}——
   * 从 jar 读资源时未关闭的流会持有 JarFile 句柄。
   */
  public RelationalRepository(JdbcTemplate jdbc) throws Exception {
    this.jdbc = jdbc;
    try (var in = new ClassPathResource("relational-model.json").getInputStream()) {
      models = objects(read(new String(in.readAllBytes(), StandardCharsets.UTF_8)), "models");
    }
  }

  /** 暴露模型定义给迁移工具/自检脚本；返回的是内部列表本身，调用方不得修改。 */
  public List<Map<String, Object>> models() {
    return models;
  }

  /**
   * 按 {@code kind} 找模型，找不到直接抛 {@link IllegalArgumentException}。
   *
   * <p>这里不转成 4xx：{@code kind} 全部来自服务端代码常量，出现未知值只能是编码错误，
   * 应该以 500 + 堆栈暴露出来，而不是被当成"用户传错了"。
   */
  private Map<String, Object> model(String kind) {
    return models.stream()
        .filter(m -> kind.equals(m.get("kind")))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown entity kind: " + kind));
  }

  /**
   * 从 id 前缀反推实体类型（id 形如 {@code kind_<uuid>}）。
   *
   * <p><b>必须按 kind 长度倒序匹配</b>：模型里同时存在 {@code refund} 与 {@code refund_fact}、
   * {@code payment} 与 {@code payment_fact}，前缀互为包含关系。若按声明顺序匹配，
   * {@code refund_fact_xxx} 会被 {@code refund} 抢先命中，然后去 {@code refunds} 表里找一条不存在的记录。
   *
   * <p>匹配时要求 {@code kind + "_"}，避免 {@code refundx_...} 这类误判。
   */
  public String kindOf(String id) {
    return models.stream()
        .map(m -> text(m, "kind"))
        .sorted(Comparator.comparingInt(String::length).reversed())
        .filter(k -> id.startsWith(k + "_"))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown entity id prefix"));
  }

  /** 按主键查；不存在返回 null（"不存在"是常态，是否转 404 由调用方决定）。 */
  public Map<String, Object> find(String kind, String id) {
    return one(model(kind), "id", id);
  }

  /** 按业务唯一键（{@code business_key} 列）查。手机号登录、微信 openid 登录走这里。 */
  public Map<String, Object> byKey(String kind, String key) {
    return one(model(kind), "business_key", key);
  }

  /** 用户名有独立唯一索引；手机号仍保留原业务键，兼容短信及微信登录。 */
  public Map<String, Object> userByUsername(String username) {
    return one(model("user"), "username", username);
  }

  /**
   * 单行查询的公共实现。
   *
   * <p>{@code SELECT *} 是安全的：列名不会进入响应，{@link #hydrate} 只按模型里声明过的字段取值，
   * 数据库里多出来的列自然被忽略（也因此"加了列但忘了加模型"表现为字段永远为空）。
   *
   * <p>{@code column} 只接受调用方写死的字面量（{@code "id"} / {@code "business_key"} /
   * {@code "username"}），不是外部输入——这是本方法唯一一处列名拼接。
   */
  private Map<String, Object> one(Map<String, Object> m, String column, String value) {
    var rows =
        jdbc.queryForList("SELECT * FROM " + m.get("table") + " WHERE " + column + "=?", value);
    return rows.isEmpty() ? null : hydrateAll(m, List.of(rows.getFirst())).getFirst();
  }

  /**
   * 按 id 批量取实体，结果顺序与 {@code ids} 一致，不存在的 id 直接跳过。
   *
   * <p>给"先用 SQL 筛出一页 id、再装配完整实体"的场景用（宠物分页）：
   * 主表一次 {@code IN}，每张子表一次 {@code IN}，查询次数与 id 个数无关。
   */
  public List<Map<String, Object>> findAll(String kind, List<String> ids) {
    if (ids.isEmpty()) return List.of();
    var m = model(kind);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (var chunk : chunks(ids))
      rows.addAll(
          jdbc.queryForList(
              "SELECT * FROM " + m.get("table") + " WHERE id IN (" + marks(chunk) + ")",
              chunk.toArray()));
    Map<String, Map<String, Object>> byId = new HashMap<>();
    for (var e : hydrateAll(m, rows)) byId.put(text(e, "id"), e);
    return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
  }

  /**
   * 取某类型的全部记录，按创建时间倒序。
   *
   * <p><b>无分页、无过滤</b>：调用方拿到的是整张表（首页聚合、分类汇总、协议列表都在用）。
   * 只适合单店的千级数据；真正需要分页的查询（宠物搜索）走 {@code PetQueries} 的 SQL。
   *
   * <p>排序用文本比较：{@code created_at} 存的是 ISO-8601 字符串，字典序与时间序一致；
   * 加 {@code id DESC} 是给同一秒创建的多行一个稳定次序，避免翻页抖动。
   */
  public List<Map<String, Object>> list(String kind) {
    var m = model(kind);
    return hydrateAll(
        m, jdbc.queryForList("SELECT * FROM " + m.get("table") + " ORDER BY created_at DESC,id DESC"));
  }

  /**
   * 带条件、关键词与分页的列表查询：过滤与分页全部下推到 SQL。
   *
   * <p><b>本方法存在的理由</b>：无参的 {@link #list(String)} 会把整张表读进内存，
   * 调用方再 {@code filter}/{@code skip}/{@code limit}。数据量上千后，
   * 一次列表请求就是"全表扫描 + N×M 次子表查询"，且 {@code total} 用的是过滤后的内存集合大小，
   * 分页越深浪费越大。把条件下推后，一次只取一页（默认 20 行）并让数据库用索引。
   *
   * <p><b>排序固定</b>为 {@code created_at DESC, id DESC}：与无参版本保持一致，
   * 保证"翻页顺序"和旧接口可比；{@code id} 兜底避免同一秒创建的行在翻页时抖动。
   *
   * <p><b>分页语义</b>：{@code limit <= 0} 表示不分页（返回全部匹配行），
   * {@code offset} 为负数时按 0 处理。调用方传 {@code limit=20} 即得到标准分页。
   *
   * <p>子表装配走 {@link #hydrateAll}：每张子表一次 {@code IN} 查询，与页大小无关。
   *
   * @param filters 条件列表，之间是 AND；空列表表示不过滤
   * @param keyword 关键词，为 {@code null}/空白 时不参与过滤
   * @param keywordFields 关键词要匹配的字段，之间是 OR；仅在 {@code keyword} 非空时生效
   */
  public List<Map<String, Object>> list(
      String kind,
      List<Filter> filters,
      String keyword,
      List<String> keywordFields,
      int offset,
      int limit) {
    var m = model(kind);
    var sql = new StringBuilder("SELECT * FROM ").append(m.get("table"));
    List<Object> args = new ArrayList<>();
    appendWhere(m, sql, args, filters, keyword, keywordFields);
    sql.append(" ORDER BY created_at DESC,id DESC");
    if (limit > 0) {
      sql.append(" LIMIT ? OFFSET ?");
      args.add(limit);
      args.add(Math.max(0, offset));
    }
    return hydrateAll(m, jdbc.queryForList(sql.toString(), args.toArray()));
  }

  /**
   * 与 {@link #list(String, List, String, List, int, int)} 同条件的计数，用于分页的 {@code total}。
   *
   * <p>不装配子表，所以比列表查询便宜得多——这正是把 {@code total} 从
   * "内存集合的 size" 换成 {@code COUNT(*)} 的收益所在。
   */
  public long count(
      String kind, List<Filter> filters, String keyword, List<String> keywordFields) {
    var m = model(kind);
    var sql = new StringBuilder("SELECT COUNT(*) FROM ").append(m.get("table"));
    List<Object> args = new ArrayList<>();
    appendWhere(m, sql, args, filters, keyword, keywordFields);
    Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    return total == null ? 0 : total;
  }

  /**
   * 字段名 → 列名，同时是<b>白名单校验</b>：模型里没有的字段直接抛异常。
   *
   * <p>与 {@link #validate} 的立场一致——写错字段名属于编码错误，应当 500 暴露，
   * 而不是静默返回空结果（那样会被误读成"没有数据"）。
   */
  private String columnOf(Map<String, Object> m, String field) {
    return objects(m, "fields").stream()
        .filter(f -> field.equals(text(f, "field")))
        .map(f -> text(f, "column"))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Unknown field: " + m.get("table") + "." + field));
  }

  /**
   * 按条件拼 WHERE 子句。
   *
   * <p>SQL 里出现的标识符只有 {@link #columnOf} 返回的列名（来自模型白名单），
   * 业务值一律放进 {@code args} 走占位符。操作符来自枚举，不接受字符串。
   *
   * <p>关键词用 {@code (a LIKE ? OR b LIKE ?)} 包一层括号：
   * 不加括号时 {@code OR} 会与前面 {@code AND} 出来的条件错误结合，把过滤条件"或"掉。
   */
  private void appendWhere(
      Map<String, Object> m,
      StringBuilder sql,
      List<Object> args,
      List<Filter> filters,
      String keyword,
      List<String> keywordFields) {
    List<String> clauses = new ArrayList<>();
    for (Filter filter : filters == null ? List.<Filter>of() : filters) {
      String column = columnOf(m, filter.field());
      switch (filter.op()) {
        case EQ -> {
          clauses.add(column + "=?");
          args.add(filter.value());
        }
        case NE -> {
          clauses.add(column + "<>?");
          args.add(filter.value());
        }
        case GT -> {
          clauses.add(column + ">?");
          args.add(filter.value());
        }
        case GE -> {
          clauses.add(column + ">=?");
          args.add(filter.value());
        }
        case LT -> {
          clauses.add(column + "<?");
          args.add(filter.value());
        }
        case LE -> {
          clauses.add(column + "<=?");
          args.add(filter.value());
        }
        case LIKE -> {
          clauses.add(column + " LIKE ?");
          args.add(filter.value());
        }
        case NOT_NULL -> clauses.add(column + " IS NOT NULL");
        case IN -> {
          var values = (Collection<?>) filter.value();
          if (values.isEmpty()) {
            // 空集合语义是"没有匹配"，不是"不过滤"；用 1=0 而不是跳过条件。
            clauses.add("1=0");
          } else {
            clauses.add(
                column + " IN (" + String.join(",", Collections.nCopies(values.size(), "?")) + ")");
            args.addAll(values);
          }
        }
      }
    }
    if (keyword != null
        && !keyword.isBlank()
        && keywordFields != null
        && !keywordFields.isEmpty()) {
      // 两个细节决定了行为是否与"内存里 contains"等价：
      //
      // 1) 用 LOWER 显式转小写，不依赖数据库排序规则。MySQL 默认 collation 大小写不敏感，
      //    H2 默认敏感——同一段代码在两个环境会给出不同结果。
      // 2) 转义 % 和 _，否则用户搜"100%"会匹配到"100 元"这类无关内容。
      //    转义符特意选 '!' 而不是反斜杠：反斜杠在 MySQL 的字符串字面量里本身是转义符，
      //    写法要在 Java、SQL、驱动三层各转义一次，极易出错；'!' 在两边都是普通字符。
      List<String> likes = new ArrayList<>();
      for (String field : keywordFields)
        likes.add("LOWER(" + columnOf(m, field) + ") LIKE ? ESCAPE '!'");
      clauses.add("(" + String.join(" OR ", likes) + ")");
      String pattern = "%" + escapeLike(keyword.toLowerCase(Locale.ROOT)) + "%";
      for (int i = 0; i < keywordFields.size(); i++) args.add(pattern);
    }
    if (!clauses.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", clauses));
  }

  /**
   * 转义 LIKE 的通配符，把"用户输入的关键词"还原成字面子串匹配。
   *
   * <p>顺序很重要：必须先把转义符自身翻倍，否则后续替换产生的 {@code !} 会被二次转义。
   */
  private String escapeLike(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  /**
   * 写库前把业务值转成 JDBC 能接受的形态。
   *
   * <p>只有三种特例：
   *
   * <ul>
   *   <li>{@code json:true} 的字段（快照、支付参数、时间线等）→ 序列化成 JSON 文本存进 LONGTEXT；
   *   <li>{@code sql:DATE} 的字段（出生日期、检疫有效期）→ {@link Date}，
   *       通过 {@code valueOf(String)} 要求调用方传 {@code yyyy-MM-dd}，格式错会在此抛异常；
   *   <li>其余原样交给驱动（含 {@code BOOLEAN}：写方向不做归一化，由数据库 TINYINT 吸收）。
   * </ul>
   */
  private Object value(Map<String, Object> field, Object value) {
    if (value == null) return null;
    if (Boolean.TRUE.equals(field.get("json"))) return write(value);
    if ("DATE".equals(field.get("sql"))) return Date.valueOf(value.toString());
    return value;
  }

  /**
   * 实体 → 列名/值映射（只含模型声明过的字段）。
   *
   * <p>缺失的字段会得到 {@code null} 值并<b>照常出现在 INSERT/UPDATE 里</b>——
   * 即"没给"等于"置空"，而不是"保留原值"。这是全量覆盖语义，也是本仓储层不支持 PATCH 的原因。
   *
   * <p>注意 {@code business_key} 不在字段列表里，所以它只由 {@link #insert} 写入一次，
   * 后续 UPDATE 永远不会改到它——业务键天然不可变。
   */
  private Map<String, Object> columns(Map<String, Object> m, Map<String, Object> entity) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (var f : objects(m, "fields"))
      result.put(text(f, "column"), value(f, entity.get(text(f, "field"))));
    return result;
  }

  /**
   * 读库方向的值还原，与 {@link #value} 对称。
   *
   * <p>JSON 列的还原方式值得注意：<b>不是</b>直接把文本当字符串返回，而是包一层
   * {@code {"value": <原文>}} 再交给 Jackson 解析。这样数字仍是数字、布尔仍是布尔、
   * 嵌套对象仍是 Map、数组仍是 List——保持调用方 {@code Json.number}/{@code object}/{@code objects}
   * 的类型预期。若直接返回字符串，所有 JSON 列的下游读取都会退化成"字符串再解析一次"。
   *
   * <p>BOOLEAN 列需要归一化：MySQL 可能回 {@code Boolean}，H2 有时回 {@code Integer/TINYINT}，
   * 统一转成 {@code Boolean}，否则 {@code Boolean.TRUE.equals(...)} 这类判断会静默失效。
   *
   * <p>DATE 列转回 {@code yyyy-MM-dd} 字符串：实体全程用字符串表达日期，
   * 避免 {@code java.sql.Date} 泄漏到业务层（它带时区语义，序列化时容易差一天）。
   */
  @SuppressWarnings("unchecked")
  private Object decode(Map<String, Object> f, Object value) {
    if (value == null) return null;
    if (Boolean.TRUE.equals(f.get("json"))) return read("{\"value\":" + value + "}").get("value");
    if ("DATE".equals(f.get("sql"))) return value.toString();
    if ("BOOLEAN".equals(f.get("sql")))
      return value instanceof Boolean ? value : ((Number) value).intValue() != 0;
    return value;
  }

  /**
   * 行 → 业务 Map，并递归装配子表。
   *
   * <p>三个必须知道的语义：
   *
   * <ul>
   *   <li><b>null 不出现在结果里</b>：为 null 的字段直接跳过，所以下游 {@code m.get(k)} 可能是
   *       null，而 {@code m.containsKey(k)} 为 false。这正好保住了"可选外部支付参数没配"和
   *       "配了空值"的区别，也是 {@code Json.object()}/{@code strings()} 能在缺字段时返回
   *       空集合的前提。反过来说，任何依赖 {@code containsKey} 的业务判断都会被这个行为影响。
   *   <li><b>子表按 {@code parent_id} 关联并保持声明顺序</b>：{@code list} 形态按 {@code position}
   *       排序（图片顺序、时间线顺序），{@code object} 形态没有顺序概念；
   *   <li><b>嵌套子表复用根实体 id</b>：递归时传下去的仍是根 id，所以二三级子表的
   *       {@code parent_id} 指向根记录而不是中间记录。这是"所有子表平铺挂在聚合根上"的设计，
   *       简化了写入（见 {@link #deleteChildren} 的递归删除）。空结果不出现在 Map 里（对象形态）
   *       或表现为空列表（列表形态）。
   * </ul>
   *
   * <p>{@code embedded} 形态会把子行字段<b>并进父 Map</b>（订单快照、交付凭证就是这种），
   * 在业务层看来它们和普通字段没有区别。
   *
   * <p>子表行来自 {@link Children} 的批量缓存，不在这里逐条查询。
   */
  private Map<String, Object> hydrate(
      Map<String, Object> m, Map<String, Object> row, String id, Children children) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (var f : objects(m, "fields")) {
      Object v = decode(f, row.get(text(f, "column")));
      // 不把缺失的可选外部支付参数扩展成 null，保留 containsKey 的业务语义。
      if (v != null) result.put(text(f, "field"), v);
    }
    for (var c : objects(m, "children")) {
      boolean list = "list".equals(c.get("mode"));
      var rows = children.rows(c, id);
      if (list) {
        List<Object> values = new ArrayList<>();
        for (var child : rows) {
          var item = hydrate(c, child, id, children);
          values.add(c.get("scalar") == null ? item : item.get(text(c, "scalar")));
        }
        result.put(text(c, "path"), values);
      } else if (!rows.isEmpty()) {
        var item = hydrate(c, rows.getFirst(), id, children);
        if ("embedded".equals(c.get("mode"))) result.putAll(item);
        else result.put(text(c, "path"), item);
      }
    }
    return result;
  }

  /**
   * 批量装配一组主表行：先收集根 id，每张子表（含嵌套子表）只查一次 {@code IN}。
   *
   * <p>原来每条记录的每张子表各查一次（N+1）：一页 20 只宠物 × 5 张子表 = 100 次查询；
   * 现在固定为"子表数"次，与行数无关。
   */
  private List<Map<String, Object>> hydrateAll(
      Map<String, Object> m, List<Map<String, Object>> rows) {
    var children = new Children(rows.stream().map(r -> text(r, "id")).toList());
    return rows.stream().map(row -> hydrate(m, row, text(row, "id"), children)).toList();
  }

  /** 单次 IN 的参数上限；超过时分批，避免超长 SQL。 */
  private static final int IN_BATCH = 500;

  private static List<List<String>> chunks(List<String> ids) {
    List<List<String>> result = new ArrayList<>();
    for (int i = 0; i < ids.size(); i += IN_BATCH)
      result.add(ids.subList(i, Math.min(ids.size(), i + IN_BATCH)));
    return result;
  }

  private static String marks(List<String> ids) {
    return String.join(",", Collections.nCopies(ids.size(), "?"));
  }

  /**
   * 一组根记录的子表行缓存：每张子表第一次用到时查一次 {@code IN}，按 {@code parent_id} 分组。
   *
   * <p>嵌套子表的 {@code parent_id} 也指向根记录（见 {@link #hydrate}），所以同一组根 id
   * 对所有层级都适用；列表形态按 {@code position} 排序，分组时保持该顺序。
   */
  private final class Children {
    private final List<String> ids;
    private final Map<String, Map<String, List<Map<String, Object>>>> byTable = new HashMap<>();

    Children(List<String> ids) {
      this.ids = ids;
    }

    List<Map<String, Object>> rows(Map<String, Object> c, String parentId) {
      boolean list = "list".equals(c.get("mode"));
      return byTable
          .computeIfAbsent(text(c, "table") + (list ? ":list" : ""), k -> load(c, list))
          .getOrDefault(parentId, List.of());
    }

    private Map<String, List<Map<String, Object>>> load(Map<String, Object> c, boolean list) {
      Map<String, List<Map<String, Object>>> grouped = new HashMap<>();
      for (var chunk : chunks(ids))
        for (var row :
            jdbc.queryForList(
                "SELECT * FROM "
                    + c.get("table")
                    + " WHERE parent_id IN ("
                    + marks(chunk)
                    + ")"
                    + (list ? " ORDER BY position" : ""),
                chunk.toArray()))
          grouped.computeIfAbsent(text(row, "parent_id"), k -> new ArrayList<>()).add(row);
      return grouped;
    }
  }

  /**
   * 拒绝没有字段映射的非空属性，防止迁移或业务扩展时悄悄丢失数据。
   *
   * <p>为什么"宁可抛异常"：{@link #columns} 只遍历模型字段，多出来的键会被<b>静默忽略</b>。
   * 如果某个接口新写了一个字段却忘了加进 {@code relational-model.json}，用户会看到"保存成功"
   * 但再次打开发现值没了——这种 bug 极难排查。这里提前以 500 失败，逼迫改 JSON。
   *
   * <p>值是 null 的键<b>放行</b>：业务层大量使用 {@code map("avatarUrl", null)} 这类写法，
   * 空值本来就写不出东西，不算丢失。
   *
   * <p>递归规则：
   *
   * <ul>
   *   <li>{@code embedded} 子表的字段提升到父级白名单（因为在业务 Map 里它们就是父级字段）；
   *   <li>{@code list} 形态把 path 本身加入白名单，仅当没有 {@code scalar} 时才逐个校验元素
   *       （标量数组如 {@code imageFileIds} 的元素是字符串，不需要字段映射）；
   *   <li>{@code object} 形态只有值非空时才下钻。
   * </ul>
   */
  private void validate(Map<String, Object> m, Map<String, Object> entity) {
    Set<String> allowed =
        objects(m, "fields").stream().map(f -> text(f, "field")).collect(Collectors.toSet());
    for (var c : objects(m, "children")) {
      if ("embedded".equals(c.get("mode"))) {
        var embedded = new LinkedHashMap<String, Object>();
        for (var f : objects(c, "fields")) {
          String key = text(f, "field");
          allowed.add(key);
          if (entity.containsKey(key)) embedded.put(key, entity.get(key));
        }
        validate(c, embedded);
      } else {
        String path = text(c, "path");
        allowed.add(path);
        if ("list".equals(c.get("mode"))) {
          if (c.get("scalar") == null) for (var item : objects(entity, path)) validate(c, item);
        } else if (entity.get(path) != null) validate(c, object(entity, path));
      }
    }
    for (String key : entity.keySet())
      if (!allowed.contains(key) && entity.get(key) != null)
        throw new IllegalArgumentException("Unmapped field: " + m.get("table") + "." + key);
  }

  /**
   * 拼一条 INSERT 并执行。
   *
   * <p>列名与占位符数量都由 {@code row} 的键动态生成，所以必须保证 <b>keySet 迭代顺序与
   * values 顺序一致</b>——传入的 {@code row} 是 {@code LinkedHashMap}（见 {@link #columns}），
   * 这一点成立；若换成 {@code HashMap}，列与值会错位，是隐性数据损坏。
   */
  private void insertRow(String table, Map<String, Object> row) {
    jdbc.update(
        "INSERT INTO "
            + table
            + " ("
            + String.join(",", row.keySet())
            + ") VALUES ("
            + String.join(",", Collections.nCopies(row.size(), "?"))
            + ")",
        row.values().toArray());
  }

  /**
   * 插入一行主表 + 全部子表。
   *
   * <p>{@code key} 写进 {@code business_key} 列（可为 null）；该列有唯一索引，
   * 重复插入会以 {@code DuplicateKeyException} 抛到 HTTP 层变成 409——
   * "手机号已被注册""用户名已占用"这类并发冲突靠数据库约束兜底，不靠先查后写。
   *
   * <p>整个方法一个事务：主表插入成功但子表失败时不会留下半条记录。
   */
  @Transactional
  public void insert(String kind, String key, Map<String, Object> entity) {
    var m = model(kind);
    validate(m, entity);
    var row = columns(m, entity);
    row.put("business_key", key);
    insertRow(text(m, "table"), row);
    writeChildren(m, entity, text(entity, "id"));
  }

  /**
   * 全量更新一行主表 + 用"先删后插"替换全部子表。
   *
   * <p><b>版本号不参与 SQL</b>：{@code WHERE id=?} 里没有 {@code version} 条件，
   * 所以并发写是"最后写入者胜"。乐观锁只在业务层显式做——{@code BusinessRepository.save}
   * 会把 {@code version} 加一并写回，调用方在改之前用 {@code version(entity, expected)} 比对
   * 客户端带上来的值。也就是说：<b>没先校验 version 就直接 save 的接口是覆盖写</b>，
   * 这在同店单人操作的场景下被接受，但任何"两人同时编辑"的入口都必须自己校验。
   *
   * <p>类型由 id 前缀推断（见 {@link #kindOf}），调用方不需要再传 kind——
   * 好处是泛型接口 {@code save(Map)} 能用，代价是 id 一旦被伪造/写错就会落到别的表。
   *
   * <p>{@code n != 1} 直接抛 {@link IllegalStateException}（→ 500）：说明目标行在读取之后
   * 被删掉了。此处不做"upsert"，因为静默插入一条残行比报错危险得多。
   * 注意 MySQL 对"值完全没变"的 UPDATE 可能返回 0 行受影响；
   * 正常路径上 {@code updated_at}/{@code version} 每次都会变，所以不受影响。
   *
   * <p>子表采用替换语义：{@code deleteChildren} 递归删干净再由 {@code writeChildren} 重建。
   * 优点是业务层只需给"当前完整状态"，不用算增删差集；代价是子行的自增列/创建时间会被重置，
   * 所以子表里不要放需要长期稳定的独立标识。
   */
  @Transactional
  public void update(Map<String, Object> entity) {
    if (update(entity, null) != 1)
      throw new IllegalStateException("Entity update did not match one row");
  }

  /**
   * 乐观锁更新：{@code WHERE id=? AND version=?}，返回受影响行数。
   *
   * <p>返回 0 说明这行在读取之后已经被别人改过（版本号变了），调用方据此报冲突。
   * 只有主表更新成功才替换子表，失败时什么都不写。
   *
   * @param expectedVersion 读取时的版本号；实体里的 {@code version} 应已由调用方加一
   */
  public int updateIfVersion(Map<String, Object> entity, long expectedVersion) {
    return update(entity, expectedVersion);
  }

  /** {@code expectedVersion} 为 null 时只按主键更新，否则额外带版本条件。 */
  private int update(Map<String, Object> entity, Long expectedVersion) {
    var m = model(kindOf(text(entity, "id")));
    validate(m, entity);
    var row = columns(m, entity);
    row.remove("id");
    List<Object> values = new ArrayList<>(row.values());
    values.add(entity.get("id"));
    if (expectedVersion != null) values.add(expectedVersion);
    int n =
        jdbc.update(
            "UPDATE "
                + m.get("table")
                + " SET "
                + row.keySet().stream().map(k -> k + "=?").collect(Collectors.joining(","))
                + " WHERE id=?"
                + (expectedVersion != null ? " AND version=?" : ""),
            values.toArray());
    if (n != 1) return n;
    // 主表与一对多关系在同一事务替换，任何附件约束失败都会回滚。
    deleteChildren(m, text(entity, "id"));
    writeChildren(m, entity, text(entity, "id"));
    return n;
  }

  /**
   * 递归删除全部子表行（不依赖外键级联）。
   *
   * <p>先删孙子再删儿子：虽然仓库层本身没建级联约束，但 {@code schema.sql} 里部分子表有外键，
   * 深度优先删除才能保证任何关系图下都不会撞外键。
   *
   * <p>所有层级都用同一个 {@code id}（根实体 id）作为 {@code parent_id} 条件——
   * 与 {@link #hydrate} 的约定一致。
   */
  private void deleteChildren(Map<String, Object> m, String id) {
    for (var c : objects(m, "children")) {
      deleteChildren(c, id);
      jdbc.update("DELETE FROM " + c.get("table") + " WHERE parent_id=?", id);
    }
  }

  /**
   * 按模型重建子表行。
   *
   * <p>三种形态的写入差异：
   *
   * <ul>
   *   <li>{@code list} + {@code scalar}（如 {@code imageFileIds}）——把"字符串列表"包成
   *       {@code {fileId: "..."}} 再写；{@code position} 取下标，顺序即业务顺序；
   *   <li>{@code list} + 对象（如售后时间线）——元素本身是 Map；实体里缺失或类型不对时
   *       按空列表处理，等于"清空该子表"；
   *   <li>{@code embedded} —— 字段在父 Map 上，所以直接把父 Map 当子行；即便是全 null 也会插一行，
   *       因为快照/交付凭证这类表的"存在与否"本身表达业务状态；
   *   <li>{@code object} —— 只有 path 非空才写，未给就是没有这条子记录。
   * </ul>
   *
   * <p>对象形态的子表还会递归写入它自己的子表（与读取时复用根 id 的约定保持一致）。
   */
  private void writeChildren(Map<String, Object> m, Map<String, Object> entity, String id) {
    for (var c : objects(m, "children")) {
      String mode = text(c, "mode"), path = text(c, "path");
      if ("list".equals(mode)) {
        List<?> list = entity.get(path) instanceof List<?> l ? l : List.of();
        for (int i = 0; i < list.size(); i++) {
          @SuppressWarnings("unchecked")
          Map<String, Object> item =
              c.get("scalar") == null
                  ? (Map<String, Object>) list.get(i)
                  : map(text(c, "scalar"), list.get(i));
          var row = columns(c, item);
          row.put("parent_id", id);
          row.put("position", i);
          insertRow(text(c, "table"), row);
        }
      } else if ("embedded".equals(mode) || entity.get(path) != null) {
        var item = "embedded".equals(mode) ? entity : object(entity, path);
        var row = columns(c, item);
        row.put("parent_id", id);
        insertRow(text(c, "table"), row);
        writeChildren(c, item, id);
      }
    }
  }
}
