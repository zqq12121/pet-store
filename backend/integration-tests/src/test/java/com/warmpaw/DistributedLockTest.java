package com.warmpaw;

import static org.junit.jupiter.api.Assertions.*;

import com.warmpaw.common.ApiException;
import com.warmpaw.service.DistributedLock;
import com.warmpaw.service.TemporaryStore;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 分布式锁与 SETNX 原语的单元测试（内存实现，不需要 Redis）。 */
class DistributedLockTest {

  private final TemporaryStore temp = new TemporaryStore(null, false);
  private final DistributedLock locks = new DistributedLock(temp);

  private static DistributedLock.Spec spec(String key) {
    return new DistributedLock.Spec(key, "BUSY_" + key, "busy");
  }

  @Test
  void putIfAbsentOnlySucceedsOnce() {
    assertTrue(temp.putIfAbsent("k", "a", 60));
    assertFalse(temp.putIfAbsent("k", "b", 60));
    assertEquals("a", temp.get("k"));
  }

  @Test
  void removeIfEqualsDoesNotDeleteOthersValue() {
    temp.put("k", "owner-a", 60);
    assertFalse(temp.removeIfEquals("k", "owner-b"));
    assertEquals("owner-a", temp.get("k"));
    assertTrue(temp.removeIfEquals("k", "owner-a"));
    assertNull(temp.get("k"));
  }

  @Test
  void heldLockRejectsSecondCallerWithItsOwnCode() {
    String result =
        locks.withLocks(
            List.of(spec("user"), spec("pet")),
            () -> {
              // 持锁期间，另一个请求拿同一把宠物锁会立即失败，并返回这把锁的错误码
              ApiException e =
                  assertThrows(
                      ApiException.class, () -> locks.withLocks(List.of(spec("pet")), () -> "x"));
              assertEquals(409, e.status);
              assertEquals("BUSY_pet", e.code);
              return "done";
            });
    assertEquals("done", result);
    // 执行结束后锁已释放，可以再次获取
    assertEquals("again", locks.withLocks(List.of(spec("user"), spec("pet")), () -> "again"));
  }

  @Test
  void failedSecondLockReleasesTheFirst() {
    temp.put("lock:pet", "someone-else", 60);
    assertThrows(
        ApiException.class, () -> locks.withLocks(List.of(spec("user"), spec("pet")), () -> "x"));
    // 用户锁已经拿到但宠物锁失败，用户锁必须被释放；别人持有的宠物锁不能被删
    assertNull(temp.get("lock:user"));
    assertEquals("someone-else", temp.get("lock:pet"));
  }

  @Test
  void lockIsReleasedWhenActionThrows() {
    assertThrows(
        IllegalStateException.class,
        () ->
            locks.withLocks(
                List.of(spec("pet")),
                () -> {
                  throw new IllegalStateException("boom");
                }));
    assertNull(temp.get("lock:pet"));
  }
}
