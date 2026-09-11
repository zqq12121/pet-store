package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;

import java.time.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 生产使用 Redis 原子消费和限流；本地模式使用独立内存空间，不要求启动 Redis。 */
@Component
public class TemporaryStore {
  private record Entry(String value, Instant expires) {}

  private final ConcurrentHashMap<String, Entry> local = new ConcurrentHashMap<>();
  private final StringRedisTemplate redis;
  private final boolean enabled;

  public TemporaryStore(StringRedisTemplate redis, @Value("${app.redis-enabled}") boolean enabled) {
    this.redis = redis;
    this.enabled = enabled;
  }

  public synchronized void put(String key, String value, int seconds) {
    if (enabled) redis.opsForValue().set("paw:" + key, value, Duration.ofSeconds(seconds));
    else {
      local.entrySet().removeIf(e -> e.getValue().expires().isBefore(Instant.now()));
      local.put(key, new Entry(value, Instant.now().plusSeconds(seconds)));
    }
  }

  public synchronized String get(String key) {
    if (enabled) return redis.opsForValue().get("paw:" + key);
    Entry e = local.get(key);
    if (e == null) return null;
    if (e.expires().isBefore(Instant.now())) {
      local.remove(key);
      return null;
    }
    return e.value();
  }

  public synchronized String take(String key) {
    if (enabled) return redis.opsForValue().getAndDelete("paw:" + key);
    String value = get(key);
    local.remove(key);
    return value;
  }

  public synchronized void checkFailures(String key, int max) {
    String value = get("fail:" + key);
    require(value == null || Long.parseLong(value) < max, 429, "RATE_LIMITED", "连续验证失败，请稍后重试");
  }

  public synchronized void failed(String key, int seconds) {
    String old = get("fail:" + key);
    put("fail:" + key, Long.toString(old == null ? 1 : Long.parseLong(old) + 1), seconds);
  }

  public synchronized void resetFailures(String key) {
    take("fail:" + key);
  }

  public synchronized void limit(String key, int max, int seconds) {
    long n;
    if (enabled) {
      Long count =
          redis.execute(
              new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                  "local n=redis.call('INCR',KEYS[1]); if n==1 then"
                      + " redis.call('EXPIRE',KEYS[1],ARGV[1]); end; return n",
                  Long.class),
              java.util.List.of("paw:limit:" + key),
              Integer.toString(seconds));
      n = count == null ? max + 1 : count;
    } else {
      String k = "limit:" + key;
      String old = get(k);
      n = old == null ? 1 : Long.parseLong(old) + 1;
      Entry e = local.get(k);
      local.put(
          k,
          new Entry(
              Long.toString(n), e == null ? Instant.now().plusSeconds(seconds) : e.expires()));
    }
    require(n <= max, 429, "RATE_LIMITED", "操作过于频繁，请稍后重试");
  }
}
