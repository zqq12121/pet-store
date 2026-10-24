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

/**
 * 离线数据迁移工具：把旧版单表（{@code resources}，kind + JSON payload）
 * 拆分到按领域分表的"关系模型 v2"。
 *
 * <p><b>这是一个人工执行的一次性工具，不是应用的一部分</b>。三条红线：
 *
 * <ul>
 *   <li>不启动 Spring 容器——没有 HTTP、没有定时任务，不存在"迁移跑着跑着被请求打断"；
 *   <li>不删除旧数据——成功后只把 {@code resources} 改名为 {@code resources_legacy}，
 *       既保留回溯能力，又能一眼看出新代码已不再读写它；
 *   <li>不在日常启动时自动执行——迁移涉及全表改写，必须由操作人显式触发。
 * </ul>
 *
 * <p><b>失败即停</b>：任何一步不符合预期都抛异常并回滚整个事务，
 * 不会出现"迁了一半、两边都有数据"的状态。所以重复执行是安全的——
 * 要么完整成功并写入 {@code schema_migrations} 标记，要么什么都没留下。
 *
 * <p>连接信息全部来自环境变量（{@code PAW_DB_URL/USERNAME/PASSWORD}），
 * 不读配置文件，避免误连到生产库。
 */
public final class MigrateLegacy {
  /**
   * 入口：连库 → 建表 → 迁移 → 修正外键 → 旧表改名。
   *
   * <p>先执行 {@code schema.sql} 保证目标表存在（{@code IF NOT EXISTS} 语义），
   * 再做数据搬迁。外键修正放在数据之后，因为此时 {@code pet_occupancy} 的旧外键仍指向
   * {@code resources}，必须先解绑再重新指向新表。
   */
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

  /** 检查表是否存在（限定当前库，避免同实例其它库的同名表造成误判）。 */
  private static boolean hasTable(JdbcTemplate jdbc, String table) {
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND"
                + " table_name=?",
            Integer.class,
            table)
        > 0;
  }

  /**
   * 核心迁移，整体在一个事务内完成。
   *
   * <p>五道保险，任何一道不通过就中止：
   *
   * <ol>
   *   <li>先对 {@code business_lock} 行加 {@code FOR UPDATE}，与线上应用的全局写锁互斥，
   *       确保迁移期间没有业务在写；
   *   <li>查 {@code schema_migrations}，已有 {@code relational_v2} 标记就直接返回（幂等）；
   *   <li>校验源数据里的 {@code kind} 都在当前模型定义中——出现未知 kind 说明代码与数据版本不匹配，
   *       贸然迁移会丢数据；
   *   <li>校验所有目标表必须为空。若已有数据，说明新系统已投入使用，此时覆盖会造成数据丢失；
   *   <li>每条记录插入后立刻做 {@link #assertPreserved} 逐字段比对。
   * </ol>
   *
   * <p>最后写入迁移标记与源记录总数，作为"这次迁移处理了多少"的凭证。
   */
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
                // 插入后立即回读比对，把"映射丢失字段"的问题暴露在对应记录上。
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

  /**
   * 递归比对源对象与落库后读回的对象，确保迁移无损。
   *
   * <p>比对规则：
   *
   * <ul>
   *   <li>Map：只遍历<b>源</b>的键（目标多出的字段允许存在），逐个递归；
   *   <li>List：长度必须相等且顺序一致——数组顺序在业务上有意义（如图片顺序），不能乱；
   *   <li>数值：用 {@link BigDecimal#compareTo} 而不 equals，因为 {@code 1} 与 {@code 1.0}
   *       在 BigDecimal 下 equals 为 false，但对业务是同一个值。
   * </ul>
   *
   * <p>"只允许缺失值与 NULL 等价"是刻意的宽松：源里没有的键、目标里为 null，
   * 在 JSON 语义下没有区别，不该因此判定迁移失败。
   *
   * @param path 出错时用于定位的字段路径（如 {@code order.payment.status}）
   */
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

  /**
   * 把 {@code pet_occupancy} 的外键从旧表改指向新表。
   *
   * <p>两步：先删掉所有指向 {@code resources}/{@code resources_legacy} 的外键，
   * 再按"缺失才添加"的方式补上指向 {@code pets(id)} 与 {@code orders(id)} 的外键。
   *
   * <p><b>可重复执行</b>是刻意设计：删除外键的循环只匹配旧目标，补外键前先查
   * {@code KEY_COLUMN_USAGE}。这样中途失败后重跑不会报"外键已存在"。
   *
   * <p>安全细节：外键名来自 {@code information_schema}，属于数据库返回的标识符，
   * 但仍用 {@code [a-zA-Z0-9_]+} 校验后才拼进 DDL——DDL 不能用参数占位符，
   * 这是防注入的最后一道防线。出现不符合规则的名字说明库里有异常对象，直接中止更安全。
   */
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
