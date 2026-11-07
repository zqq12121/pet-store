package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;

import java.time.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 生产使用 Redis 原子消费和限流；本地模式使用独立内存空间，不要求启动 Redis。
 *
 * <p><b>它不是"内存存储"，而是双实现</b>，由 {@code app.redis-enabled} 决定：
 *
 * <ul>
 *   <li><b>启用</b>（生产）—— 数据在 Redis，key 统一加 {@code paw:} 前缀；
 *       {@link #take}/{@link #limit} 走 Redis 原子命令或 Lua 脚本，保证"读-改-写"在 Redis 侧原子，
 *       多实例天然共享同一份状态；
 *   <li><b>关闭</b>（本地/测试默认）—— 数据在进程内的 {@link ConcurrentHashMap}，
 *       重启即全部丢失，且<b>多实例之间互不可见</b>。
 * </ul>
 *
 * <p><b>内存模式的局限必须说清楚</b>：短信验证码、图形验证 challenge、游客 token、
 * 失败计数、接口限流全都在这个 Map 里。因此部署两个实例且未开 Redis 时会出现：
 * A 实例发的验证码在 B 实例校验不过（表现为"验证码不正确"）、限流额度按实例数翻倍、
 * 一次性凭证可能在两个实例上各被消费一次。这些都是静默的错误而非崩溃，
 * 所以多实例部署<b>必须</b>开启 Redis。
 *
 * <p><b>过期实现</b>有两种：
 *
 * <ul>
 *   <li>Redis —— 原生 TTL，服务器负责清理；
 *   <li>内存 —— 每条记录自带 {@code expires}，读取时惰性判断；另外 {@link #put} 会顺手
 *       全表扫一遍删掉过期项。所以内存模式下如果长时间只读不写，过期数据会占着内存，
 *       但不会"复活"（读路径始终校验 expires）。
 * </ul>
 *
 * <p>所有方法都 {@code synchronized}：这只解决了<b>单进程内</b>的并发。
 * 跨实例的原子性完全依赖 Redis 脚本，不要因为看到 synchronized 就以为多实例安全。
 */
@Component
public class TemporaryStore {
  /** 内存条目：值 + 绝对过期时刻（不是 TTL，避免读取时反复算）。 */
  private record Entry(String value, Instant expires) {}

  /** 内存实现的数据区。键没有 {@code paw:} 前缀——那是 Redis 的全局命名空间，进程内不需要。 */
  private final ConcurrentHashMap<String, Entry> local = new ConcurrentHashMap<>();
  private final StringRedisTemplate redis;
  /** 是否走 Redis。关掉时 {@code redis} 仍然被注入（自动配置总会提供），只是从不使用。 */
  private final boolean enabled;

  public TemporaryStore(StringRedisTemplate redis, @Value("${app.redis-enabled}") boolean enabled) {
    this.redis = redis;
    this.enabled = enabled;
  }

  /**
   * 写入并设置过期秒数（覆盖同 key）。
   *
   * <p>内存分支里的 {@code removeIf} 是"顺手清理"：每次写都全表扫一遍过期项。
   * 数据量在千级时可忽略；如果某个场景把这里当缓存高频写，这个扫描会变成 O(n) 热点。
   *
   * <p>注意 TTL 由传入的 {@code seconds} 决定，没有默认值——所有调用点都必须明确
   * "这条数据活多久"，因为验证码（300s）、挑战（120s）、游客身份（86400s）的时效语义完全不同。
   */
  public synchronized void put(String key, String value, int seconds) {
    if (enabled) redis.opsForValue().set("paw:" + key, value, Duration.ofSeconds(seconds));
    else {
      local.entrySet().removeIf(e -> e.getValue().expires().isBefore(Instant.now()));
      local.put(key, new Entry(value, Instant.now().plusSeconds(seconds)));
    }
  }

  /**
   * 读取；不存在或已过期一律返回 null（不抛异常、不区分"从未存在"与"已过期"）。
   *
   * <p>内存分支在读到过期项时顺手删除，避免过期数据被反复读到；Redis 分支由服务端 TTL 保证。
   *
   * <p>调用方必须把 null 当成"不可用"处理——这就是验证码过期、游客身份失效的统一出口。
   */
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

  /**
   * 取值并<b>立即删除</b>，实现"一次性凭证"。
   *
   * <p>Redis 分支用 {@code GETDEL}（单条命令原子）；内存分支靠 {@code synchronized} 保证
   * "读后删"之间不被打断。返回值是删除前的值：并发时只有一个调用方拿到非 null，
   * 其余拿到 null 并因此判定"已被使用"。
   *
   * <p>用途：短信验证码核销、失败计数清零。
   */
  public synchronized String take(String key) {
    if (enabled) return redis.opsForValue().getAndDelete("paw:" + key);
    String value = get(key);
    local.remove(key);
    return value;
  }

  /**
   * 失败计数闸门：已连续失败 {@code max} 次则直接 429 拒绝本次尝试。
   *
   * <p>调用位置一律在<b>校验之前</b>（密码比对、验证码比对），所以 {@code max=5} 的实际语义是
   * "允许 5 次失败尝试，第 6 次开始连试都不让试"。
   *
   * <p>它只读不写：递增由 {@link #failed} 在判定失败后完成。分成两个方法是为了让
   * "先检查、再校验、失败才计数"的顺序在代码里显式可见——若合成一个方法，
   * 就无法表达"校验成功了不该计一次失败"。
   *
   * <p>计数器的存活时间由最后一次 {@link #failed} 传入的秒数决定，见那里的说明。
   */
  public synchronized void checkFailures(String key, int max) {
    String value = get("fail:" + key);
    require(value == null || Long.parseLong(value) < max, 429, "RATE_LIMITED", "连续验证失败，请稍后重试");
  }

  /**
   * 记一次失败：计数 +1，并把过期时间重置为 {@code seconds}。
   *
   * <p><b>每次失败都续期</b>，所以这是一个滑动窗口而不是固定窗口：连续攻击时锁定时间
   * 一直被推后（每失败一次再锁 10 分钟）。安全上的收益是攻击者无法"打满 5 次后等 10 分钟再打 5 次"
   * 之外还有额外空间；副作用是<b>攻击者可以用别人的账号名持续失败来把真实用户锁在门外</b>。
   * 这是已知的取舍：登录入口的频率限制（IP 维度）承担了另一层防护。
   *
   * <p>另外，失败计数不区分"账号不存在"与"密码错误"（调用方共用同一个 key），
   * 否则计数行为本身就会泄露账号是否存在。
   */
  public synchronized void failed(String key, int seconds) {
    String old = get("fail:" + key);
    put("fail:" + key, Long.toString(old == null ? 1 : Long.parseLong(old) + 1), seconds);
  }

  /**
   * 清零失败计数（登录成功后调用）。
   *
   * <p>用 {@link #take} 而不是写入 0：键消失后 {@link #checkFailures} 与 {@link #failed}
   * 的"首次失败"分支才会重新生效，不会留下一个还带 TTL 的零值计数器。
   */
  public synchronized void resetFailures(String key) {
    take("fail:" + key);
  }

  /**
   * 固定窗口限流：{@code seconds} 秒内最多 {@code max} 次，超限 429。
   *
   * <p><b>先自增再判断</b>，所以 {@code max=1} 表示"一个窗口内只允许一次"，被拒绝的请求
   * 也照样占用了计数（这正是 {@code sms-minute} 这类"60 秒 1 条"所需的语义）。
   *
   * <p><b>窗口是固定的，不是滑动的</b>：计数从窗口内第一次请求开始计时，之后无论再请求多少次，
   * 过期时间都不变。代价是相邻两个窗口的交界处可能瞬时放过 2 倍流量；
   * 对本项目的用途（防刷短信、防枚举）这个精度足够，换成滑动窗口要多存一份时间戳列表。
   *
   * <p>Redis 分支用 Lua 把 {@code INCR} 与首次的 {@code EXPIRE} 合成一个原子操作——
   * 分开执行时如果进程在两步之间挂掉，会留下一个永不过期的计数器，
   * 那个 key 从此被永久封禁（这是限流实现里最常见的事故）。
   *
   * <p>脚本返回 null（Redis 异常）时按"已超限"处理（{@code max + 1}）：<b>失败关闭</b>。
   * 限流器坏掉时不能顺便变成无限放行，否则一次 Redis 抖动就等于关掉了短信防轰炸。
   */
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
