package com.warmpaw.service;

import static com.warmpaw.common.Json.*;

import com.warmpaw.repository.BusinessRepository;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 初始词条只补缺失记录，管理员停用或改词后不会在重启时被覆盖。
 *
 * <p><b>执行时机</b>：实现 {@link ApplicationRunner}，<b>每次应用启动</b>都会在容器就绪后跑一次，
 * 且不受 {@code app.mock-providers} 影响——线上同样执行。因此它必须是幂等的、
 * 只能"补"不能"改"或"删"。
 *
 * <p><b>幂等键是词条原文</b>：用 {@code byKey} 查 {@code business_key}（= 原始 term），
 * 存在就跳过。所以店长把种子词的 {@code enabled} 关掉后重启不会被改回 true；
 * 改过 {@code term} 也不会新增第二条（业务键仍是旧值，仍然查得到）。
 *
 * <p><b>为什么种子词写死在代码里</b>：这是敏感词库的最小可用集，避免空白部署时出现
 * "完全没有过滤"的窗口。真正的运营词表由店长在后台维护，这里只负责第一次不至于裸奔。
 *
 * <p>写入时 {@code normalized} 直接取原文、没有走 {@code CommunityService.normalize}：
 * 这三个词不含空白、标点与全角字符，NFKC 后与原值相同。<b>新增种子词时若含全角字符或空格，
 * 必须改成先归一化</b>，否则记下的 matching 形式与运行时归一化结果对不上，匹配会失效。
 */
@Component
public class CommunityInitializer implements ApplicationRunner {
  private final BusinessRepository store;

  public CommunityInitializer(BusinessRepository store) {
    this.store = store;
  }

  /**
   * 启动时补齐种子词条。
   *
   * <p>先 {@code store.lock()} 再查再写：多实例同时启动时，两个进程可能同时判定"不存在"而各写一条，
   * 锁 + 事务把这段变成串行，业务键上的唯一约束是最后一道兜底。
   *
   * <p>id 固定为 {@code community_word_seed_<序号>}（{@code create} 用的是 {@code putIfAbsent}，
   * 不会覆盖显式传入的 id），便于运维一眼认出哪些是内置词、哪些是运营加的。
   *
   * <p>任一词条插入失败会让整批回滚：宁可一条不写，也不要留下"部分种子"的中间状态。
   */
  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    store.lock();
    var terms = List.of("傻逼", "操你妈", "他妈的");
    for (int i = 0; i < terms.size(); i++) {
      String term = terms.get(i);
      if (store.byKey("community_word", term) == null)
        store.create("community_word", null, term, map("id", "community_word_seed_" + i,
            "term", term, "normalized", term, "enabled", true));
    }
  }
}
