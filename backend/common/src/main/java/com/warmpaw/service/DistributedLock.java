package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 业务分布式锁：{@code SET key token NX EX ttl} 加锁，Lua "值相等才删" 释放。
 *
 * <p><b>用在哪</b>：预约下单。锁按"用户"和"宠物"两个维度加，粒度到单条资源，
 * 不同用户预约不同宠物互不影响（对比全局写锁的完全串行）。
 *
 * <p><b>为什么锁要包在事务外面</b>：调用方必须在<b>事务提交之后</b>才释放锁。
 * 如果在 {@code @Transactional} 方法里加锁、finally 里解锁，解锁时事务还没提交，
 * 下一个线程拿到锁后读到的仍是旧数据，锁就形同虚设。
 *
 * <p><b>拿不到锁直接失败，不等待</b>：同一只宠物只有一只，等到锁时大概率已被占用，
 * 快速失败比让请求挂着占线程更合理。
 *
 * <p><b>没有看门狗续期</b>：TTL 取 {@link #TTL_SECONDS}，远大于一次下单事务的耗时。
 * 万一事务比 TTL 还长导致锁提前过期，数据库层还有两道防线：宠物表的乐观锁（版本号条件更新）
 * 和 {@code pet_occupancy} 主键约束。所以锁失效最坏只会让请求多走到数据库，不会超卖。
 *
 * <p>底层用 {@link TemporaryStore}：生产走 Redis，本地/测试未开 Redis 时走进程内实现。
 */
@Component
public class DistributedLock {

  /** 锁的存活秒数；下单事务通常在百毫秒级。 */
  static final int TTL_SECONDS = 15;

  private final TemporaryStore temp;

  public DistributedLock(TemporaryStore temp) {
    this.temp = temp;
  }

  /**
   * 一把锁的描述：锁名，以及拿不到时返回给前端的错误码和提示。
   *
   * @param key 锁名，如 {@code reserve:pet:<petId>}
   * @param busyCode 拿不到锁时的错误码（HTTP 409）
   * @param busyMessage 拿不到锁时的提示
   */
  public record Spec(String key, String busyCode, String busyMessage) {}

  /**
   * 按顺序拿齐全部锁后执行 {@code action}，结束后倒序释放。
   *
   * <p>所有调用方要用<b>相同的加锁顺序</b>（例如先用户后宠物）。这里不等待锁，
   * 不会真正死锁，但顺序一致能避免两个请求各拿一半、互相把对方拒掉。
   */
  public <T> T withLocks(List<Spec> locks, Supplier<T> action) {
    // token 区分持有者：释放时只删自己的锁
    String token = UUID.randomUUID().toString();
    List<String> held = new ArrayList<>();
    try {
      for (Spec lock : locks) {
        require(
            temp.putIfAbsent("lock:" + lock.key(), token, TTL_SECONDS),
            409,
            lock.busyCode(),
            lock.busyMessage());
        held.add(lock.key());
      }
      return action.get();
    } finally {
      // 倒序释放；锁若已过期并被别人拿走，removeIfEquals 不会误删
      for (int i = held.size() - 1; i >= 0; i--) temp.removeIfEquals("lock:" + held.get(i), token);
    }
  }
}
