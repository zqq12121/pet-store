package com.warmpaw.tools;

import static com.warmpaw.common.Json.*;

import com.warmpaw.repository.RelationalRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** 显式离线迁移入口：不启动HTTP/定时任务，不删除旧业务数据，不在日常启动时自动迁移。 */
public final class MigrateLegacy {
  public static void main(String[] args) throws Exception {
    var ds =
        new DriverManagerDataSource(
            System.getenv()
                .getOrDefault(
                    "PAW_DB_URL",
                    "jdbc:mysql://127.0.0.1:3306/warmpaw?serverTimezone=Asia/Shanghai"),
            System.getenv("PAW_DB_USERNAME"),
            System.getenv("PAW_DB_PASSWORD"));
    var jdbc = new JdbcTemplate(ds);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
    var repository = new RelationalRepository(jdbc);
    migrate(jdbc, repository, new TransactionTemplate(new DataSourceTransactionManager(ds)));
    retargetOccupancy(jdbc);
    // 完成后只改名保留旧表，防止误认为新代码仍在读写它。
    if (hasTable(jdbc, "resources")) jdbc.execute("RENAME TABLE resources TO resources_legacy");
    System.out.println("Relational migration verified; legacy data retained.");
  }

  private static boolean hasTable(JdbcTemplate jdbc, String table) {
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND"
                + " table_name=?",
            Integer.class,
            table)
        > 0;
  }

  public static void migrate(
      JdbcTemplate jdbc, RelationalRepository repository, TransactionTemplate tx) {
    tx.executeWithoutResult(
        s -> {
          jdbc.queryForObject("SELECT id FROM business_lock WHERE id=1 FOR UPDATE", Integer.class);
          if (jdbc.queryForObject(
                  "SELECT COUNT(*) FROM schema_migrations WHERE version='relational_v2'",
                  Integer.class)
              > 0) return;
          var source =
              jdbc.queryForList(
                  "SELECT kind,business_key,payload FROM resources ORDER BY created_at,id");
          Set<String> kinds = new HashSet<>();
          for (var m : repository.models()) kinds.add(text(m, "kind"));
          for (var row : source)
            if (!kinds.contains(row.get("kind")))
              throw new IllegalStateException("Unknown legacy kind: " + row.get("kind"));
          // 防止与已经投入使用的新表混合；失败不会清空或覆盖任一已有记录。
          for (var m : repository.models())
            if (jdbc.queryForObject("SELECT COUNT(*) FROM " + m.get("table"), Integer.class) != 0)
              throw new IllegalStateException("Target table is not empty: " + m.get("table"));
          for (var m : repository.models()) {
            String kind = text(m, "kind");
            int count = 0;
            for (var row : source)
              if (kind.equals(row.get("kind"))) {
                var entity = read(text(row, "payload"));
                repository.insert(kind, text(row, "business_key"), entity);
                assertPreserved(entity, repository.find(kind, text(entity, "id")), kind);
                count++;
              }
            System.out.println(m.get("table") + ": migrated " + count);
          }
          jdbc.update(
              "INSERT INTO schema_migrations(version,applied_at,source_count)"
                  + " VALUES('relational_v2',?,?)",
              Instant.now().toString(),
              source.size());
        });
  }

  /** 检查源中的每个字段、数组顺序与数值；只允许缺失值与NULL等价。 */
  public static void assertPreserved(Object source, Object target, String path) {
    if (source == null) {
      if (target != null) throw new IllegalStateException("Migration mismatch: " + path);
      return;
    }
    if (source instanceof Map<?, ?> a && target instanceof Map<?, ?> b) {
      for (var key : a.keySet()) assertPreserved(a.get(key), b.get(key), path + "." + key);
      return;
    }
    if (source instanceof List<?> a && target instanceof List<?> b) {
      if (a.size() != b.size()) throw new IllegalStateException("Migration list mismatch: " + path);
      for (int i = 0; i < a.size(); i++) assertPreserved(a.get(i), b.get(i), path + "[" + i + "]");
      return;
    }
    if (source instanceof Number a
        && target instanceof Number b
        && new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0) return;
    if (!Objects.equals(source, target))
      throw new IllegalStateException("Migration mismatch: " + path);
  }

  private static void retargetOccupancy(JdbcTemplate jdbc) {
    for (var row :
        jdbc.queryForList(
            "SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE"
                + " TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pet_occupancy' AND"
                + " REFERENCED_TABLE_NAME IN ('resources','resources_legacy')")) {
      String name = text(row, "CONSTRAINT_NAME");
      if (!name.matches("[a-zA-Z0-9_]+"))
        throw new IllegalStateException("Unexpected constraint name");
      jdbc.execute("ALTER TABLE pet_occupancy DROP FOREIGN KEY `" + name + "`");
    }
    // 可重复执行：只补缺少的外键；旧库数据保留，孤立记录会使迁移明确失败。
    for (String[] relation :
        List.of(new String[] {"pet_id", "pets"}, new String[] {"order_id", "orders"})) {
      if (jdbc.queryForObject(
              "SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE"
                  + " TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pet_occupancy' AND COLUMN_NAME=? AND"
                  + " REFERENCED_TABLE_NAME=?",
              Integer.class,
              relation[0],
              relation[1])
          == 0)
        jdbc.execute(
            "ALTER TABLE pet_occupancy ADD FOREIGN KEY ("
                + relation[0]
                + ") REFERENCES "
                + relation[1]
                + "(id)");
    }
  }
}
