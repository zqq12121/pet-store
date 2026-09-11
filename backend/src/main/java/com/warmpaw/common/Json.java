package com.warmpaw.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 数据库 JSON 与快照使用同一序列化方式；不把内部实体直接返回给前端。 */
public final class Json {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Json() {}

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static Map<String, Object> read(String value) {
    try {
      return MAPPER.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static Map<String, Object> copy(Map<String, Object> value) {
    return read(write(value));
  }

  public static Map<String, Object> map(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String canonical(Object value) {
    if (value instanceof Map<?, ?> m) {
      Map<String, Object> sorted = new TreeMap<>();
      m.forEach((k, v) -> sorted.put(k.toString(), canonicalValue(v)));
      return write(sorted);
    }
    return write(value);
  }

  private static Object canonicalValue(Object value) {
    if (value instanceof Map<?, ?> m) {
      Map<String, Object> sorted = new TreeMap<>();
      m.forEach((k, v) -> sorted.put(k.toString(), canonicalValue(v)));
      return sorted;
    }
    if (value instanceof List<?> l) return l.stream().map(Json::canonicalValue).toList();
    return value;
  }

  public static String id(String prefix) {
    return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
  }

  public static String text(Map<String, Object> m, String key) {
    Object value = m.get(key);
    return value == null ? null : value.toString();
  }

  public static long number(Map<String, Object> m, String key) {
    return ((Number) m.getOrDefault(key, 0)).longValue();
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> object(Map<String, Object> m, String key) {
    return m.get(key) instanceof Map<?, ?>
        ? (Map<String, Object>) m.get(key)
        : new LinkedHashMap<>();
  }

  @SuppressWarnings("unchecked")
  public static List<String> strings(Map<String, Object> m, String key) {
    return m.get(key) instanceof List<?> ? (List<String>) m.get(key) : List.of();
  }

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> objects(Map<String, Object> m, String key) {
    return m.get(key) instanceof List<?> ? (List<Map<String, Object>>) m.get(key) : List.of();
  }
}
