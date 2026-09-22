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

/** 业务 Map 与显式关系表的适配层；字段/表名只取打包的白名单，业务值全部参数绑定。 */
@Repository
public class RelationalRepository {
  private final JdbcTemplate jdbc;
  private final List<Map<String, Object>> models;

  public RelationalRepository(JdbcTemplate jdbc) throws Exception {
    this.jdbc = jdbc;
    try (var in = new ClassPathResource("relational-model.json").getInputStream()) {
      models = objects(read(new String(in.readAllBytes(), StandardCharsets.UTF_8)), "models");
    }
  }

  public List<Map<String, Object>> models() {
    return models;
  }

  private Map<String, Object> model(String kind) {
    return models.stream()
        .filter(m -> kind.equals(m.get("kind")))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown entity kind: " + kind));
  }

  public String kindOf(String id) {
    return models.stream()
        .map(m -> text(m, "kind"))
        .sorted(Comparator.comparingInt(String::length).reversed())
        .filter(k -> id.startsWith(k + "_"))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown entity id prefix"));
  }

  public Map<String, Object> find(String kind, String id) {
    return one(model(kind), "id", id);
  }

  public Map<String, Object> byKey(String kind, String key) {
    return one(model(kind), "business_key", key);
  }

  /** 用户名有独立唯一索引；手机号仍保留原业务键，兼容短信及微信登录。 */
  public Map<String, Object> userByUsername(String username) {
    return one(model("user"), "username", username);
  }

  public void revokeSessions(String owner, String role) {
    jdbc.update("UPDATE auth_sessions SET status='revoked' WHERE owner_id=? AND role=?", owner, role);
  }

  private Map<String, Object> one(Map<String, Object> m, String column, String value) {
    var rows =
        jdbc.queryForList("SELECT * FROM " + m.get("table") + " WHERE " + column + "=?", value);
    return rows.isEmpty() ? null : hydrate(m, rows.getFirst(), text(rows.getFirst(), "id"));
  }

  public List<Map<String, Object>> list(String kind) {
    var m = model(kind);
    return jdbc
        .queryForList("SELECT * FROM " + m.get("table") + " ORDER BY created_at DESC,id DESC")
        .stream()
        .map(row -> hydrate(m, row, text(row, "id")))
        .toList();
  }

  private Object value(Map<String, Object> field, Object value) {
    if (value == null) return null;
    if (Boolean.TRUE.equals(field.get("json"))) return write(value);
    if ("DATE".equals(field.get("sql"))) return Date.valueOf(value.toString());
    return value;
  }

  private Map<String, Object> columns(Map<String, Object> m, Map<String, Object> entity) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (var f : objects(m, "fields"))
      result.put(text(f, "column"), value(f, entity.get(text(f, "field"))));
    return result;
  }

  @SuppressWarnings("unchecked")
  private Object decode(Map<String, Object> f, Object value) {
    if (value == null) return null;
    if (Boolean.TRUE.equals(f.get("json"))) return read("{\"value\":" + value + "}").get("value");
    if ("DATE".equals(f.get("sql"))) return value.toString();
    if ("BOOLEAN".equals(f.get("sql")))
      return value instanceof Boolean ? value : ((Number) value).intValue() != 0;
    return value;
  }

  private Map<String, Object> hydrate(Map<String, Object> m, Map<String, Object> row, String id) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (var f : objects(m, "fields")) {
      Object v = decode(f, row.get(text(f, "column")));
      // 不把缺失的可选外部支付参数扩展成 null，保留 containsKey 的业务语义。
      if (v != null) result.put(text(f, "field"), v);
    }
    for (var c : objects(m, "children")) {
      boolean list = "list".equals(c.get("mode"));
      var rows =
          jdbc.queryForList(
              "SELECT * FROM "
                  + c.get("table")
                  + " WHERE parent_id=?"
                  + (list ? " ORDER BY position" : ""),
              id);
      if (list) {
        List<Object> values = new ArrayList<>();
        for (var child : rows) {
          var item = hydrate(c, child, id);
          values.add(c.get("scalar") == null ? item : item.get(text(c, "scalar")));
        }
        result.put(text(c, "path"), values);
      } else if (!rows.isEmpty()) {
        var item = hydrate(c, rows.getFirst(), id);
        if ("embedded".equals(c.get("mode"))) result.putAll(item);
        else result.put(text(c, "path"), item);
      }
    }
    return result;
  }

  /** 拒绝没有字段映射的非空属性，防止迁移或业务扩展时悄悄丢失数据。 */
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

  @Transactional
  public void insert(String kind, String key, Map<String, Object> entity) {
    var m = model(kind);
    validate(m, entity);
    var row = columns(m, entity);
    row.put("business_key", key);
    insertRow(text(m, "table"), row);
    writeChildren(m, entity, text(entity, "id"));
  }

  @Transactional
  public void update(Map<String, Object> entity) {
    var m = model(kindOf(text(entity, "id")));
    validate(m, entity);
    var row = columns(m, entity);
    row.remove("id");
    List<Object> values = new ArrayList<>(row.values());
    values.add(entity.get("id"));
    int n =
        jdbc.update(
            "UPDATE "
                + m.get("table")
                + " SET "
                + row.keySet().stream().map(k -> k + "=?").collect(Collectors.joining(","))
                + " WHERE id=?",
            values.toArray());
    if (n != 1) throw new IllegalStateException("Entity update did not match one row");
    // 主表与一对多关系在同一事务替换，任何附件约束失败都会回滚。
    deleteChildren(m, text(entity, "id"));
    writeChildren(m, entity, text(entity, "id"));
  }

  private void deleteChildren(Map<String, Object> m, String id) {
    for (var c : objects(m, "children")) {
      deleteChildren(c, id);
      jdbc.update("DELETE FROM " + c.get("table") + " WHERE parent_id=?", id);
    }
  }

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
