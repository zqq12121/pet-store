package com.warmpaw.service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 定时任务的跨实例互斥锁，用 Redis 的 {@code SET NX EX} 实现。
 *
 * <p><b>解决什么问题</b>：{@code PaymentWorker.reconcile}、{@code AppointmentSmsWorker.retryDue}、
 * {@code MaintenanceService.cleanTemporaryFiles} 都挂在本机的 {@code @Scheduled} 上。
 * 单实例时没问题，一旦 order-server 起两个实例做扩容，三个任务会同时跑两份：
 * 支付对账重复推进、同一笔预约短信发两次、同一批临时文件被删两次（第二次报错）。
 *
 * <p><b>为什么用 Redis 而不是数据库锁</b>：项目已经有 Redis（会话存在这里），
 * 加锁不引入新的运维组件；而数据库锁要么产生行级争用、要么需要额外的锁表并处理心跳。
 * {@code SET key value NX EX ttl} 是单条命令，天然原子，不需要先查后写。
 *
 * <p><b>为什么 TTL 是 55 秒</b>：{@code reconcile} 的调度间隔是 60 秒。
 * TTL 必须大于单次执行耗时（通常几秒），又必须小于调度间隔，否则上一轮的锁会挡住下一轮。
 * 若任务异常卡死，锁会在 55 秒后自动过期，下一个实例接手——这比永久死锁安全，
 * 代价是极端情况下可能出现一次重复执行，而这三个任务本身都做了幂等（见各自方法注释）。
 *
 * <p><b>为什么释放要校验 owner</b>：如果任务执行超过 TTL，锁已经过期并被另一个实例持有，
 * 此时直接 {@code DEL} 会删掉别人的锁，导致第三个实例也能进来。
 * 所以释放用 Lua 脚本做"值相等才删"的原子操作。
 *
 * <p><b>降级策略</b>：Redis 未启用或连不上时，{@link #tryAcquire} 返回 {@code true}，
 * 即退化为原来的单实例行为。理由是这个项目当前就是单店单实例部署，
 * 拿不到锁不等于任务不该跑；而多实例场景下 Redis 本就不可用（会话也在 Redis），
 * 整个应用已经不能用，轮不到定时任务出问题。
 */
@Component
public class SchedulerLock {

  private static final Logger log = LoggerFactory.getLogger(SchedulerLock.class);

  /** 比较后再删除，防止误删其他实例的锁。返回 1 表示删掉，0 表示锁已不属于自己。 */
  private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
      new DefaultRedisScript<>(
          "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
          Long.class);

  /** 所有键统一前缀，便于在 Redis 里一眼区分调度锁与业务数据。 */
  private static final String KEY_PREFIX = "warmpaw:scheduler:";

  private final StringRedisTemplate redis;

  /** 本实例的持有者标识：同一进程的多次抢锁用同一个值，不同进程一定不同。 */
  private final String owner = UUID.randomUUID().toString();

  /** 是否启用 Redis 锁；与 {@code app.redis-enabled} 保持一致。 */
  private final boolean enabled;

  /** 只在第一次连不上时打 WARN，避免每 30 秒刷一条同样的告警。 */
  private final AtomicBoolean degradedWarned = new AtomicBoolean(false);

  public SchedulerLock(
      StringRedisTemplate redis, @Value("${app.redis-enabled:false}") boolean enabled) {
    this.redis = redis;
    this.enabled = enabled;
  }

  /**
   * 尝试获取任务锁。
   *
   * @param name 任务名，同一任务在所有实例上必须一致
   * @param ttl 锁的存活时间，应略小于调度间隔
   * @return {@code true} 表示本次由当前实例执行；{@code false} 表示别的实例正在跑，应直接返回
   */
  public boolean tryAcquire(String name, Duration ttl) {
    if (!enabled) return true;
    try {
      return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(name), owner, ttl));
    } catch (RuntimeException e) {
      if (degradedWarned.compareAndSet(false, true)) {
        log.warn("调度锁不可用，退化为单实例执行（后续同类告警不再重复打印）：task={}", name, e);
      }
      return true;
    }
  }

  /**
   * 释放任务锁。执行结束后必须调用，否则下一轮要等 TTL 自然过期。
   *
   * <p>失败只记 DEBUG：锁到期会自动释放，释放失败不影响正确性，不值得刷 WARN。
   */
  public void release(String name) {
    if (!enabled) return;
    try {
      redis.execute(RELEASE_SCRIPT, java.util.List.of(key(name)), owner);
    } catch (RuntimeException e) {
      log.debug("释放调度锁失败，等待 TTL 自然过期：task={}", name, e);
    }
  }

  private String key(String name) {
    return KEY_PREFIX + name;
  }
}
