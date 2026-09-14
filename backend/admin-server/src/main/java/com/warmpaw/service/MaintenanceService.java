package com.warmpaw.service;

import static com.warmpaw.common.Json.*;

import com.warmpaw.repository.BusinessRepository;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 每日只清理未被业务引用的临时文件；历史订单、退回证据和快照优先保留。 */
@Service
public class MaintenanceService {
  private final BusinessRepository store;
  private final Path root;
  private final OssStorage oss;

  public MaintenanceService(BusinessRepository store, OssStorage oss, @Value("${app.storage}") String storage) {
    this.store = store;
    this.root = Path.of(storage).toAbsolutePath().normalize();
    this.oss = oss;
  }

  @Scheduled(fixedDelay = 86400000, initialDelay = 86400000)
  @Transactional
  public void cleanTemporaryFiles() {
    store.lock();
    Instant cutoff = Instant.now().minusSeconds(86400);
    Set<String> references = new HashSet<>();
    for (String kind : List.of("pet", "order", "after_sale", "shop", "confirmation", "user"))
      for (Map<String, Object> entity : store.list(kind)) collect(entity, references);
    for (Map<String, Object> file : store.list("file")) {
      String id = text(file, "id");
      if (!"ready".equals(text(file, "status"))
          || Instant.parse(text(file, "createdAt")).isAfter(cutoff)
          || references.contains(id)
          || file.get("orderId") != null) continue;
      Path target = root.resolve(id).normalize();
      if (!target.getParent().equals(root)) continue;
      try {
        oss.delete(id);
        Files.deleteIfExists(target);
        file.put("status", "deleted");
        store.save(file);
      } catch (Exception ignored) {
        /* 文件系统失败保留元数据，下次任务重试，不误报清理成功。 */
      }
    }
  }

  private void collect(Object value, Set<String> result) {
    if (value instanceof String s) {
      if (s.startsWith("file_")) result.add(s);
      int start = s.indexOf("/api/v1/media/file_");
      if (start >= 0) result.add(s.substring(start + "/api/v1/media/".length()));
    } else if (value instanceof Map<?, ?> m) m.values().forEach(v -> collect(v, result));
    else if (value instanceof List<?> l) l.forEach(v -> collect(v, result));
  }
}
