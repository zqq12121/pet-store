package com.warmpaw.service;

import static com.warmpaw.common.Json.*;

import com.warmpaw.repository.BusinessRepository;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 初始词条只补缺失记录，管理员停用或改词后不会在重启时被覆盖。 */
@Component
public class CommunityInitializer implements ApplicationRunner {
  private final BusinessRepository store;

  public CommunityInitializer(BusinessRepository store) {
    this.store = store;
  }

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
