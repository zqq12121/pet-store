package com.warmpaw.repository;

import static com.warmpaw.common.Json.*;
import static com.warmpaw.service.CatalogReader.queryLong;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 宠物搜索、价格/年龄筛选和分页由数据库完成，避免加载整张宠物表。
 *
 * <p>与 {@code CatalogReader.pets} 的分工：那边只做<b>参数校验与出口投影</b>，
 * 真正的 WHERE/ORDER/LIMIT 在这里。所以"买家能看到哪些状态"这件安全相关的事，
 * 在两处各有一份——见 {@link #page} 里对 {@code admin} 的处理。
 *
 * <p>本类返回的是<b>实体</b>（含 {@code ownerId} 等内部字段），调用方必须再用
 * {@code CatalogReader.card/adminPet} 投影后才能下发，不能直接透传。
 *
 * <p>所有动态拼进 SQL 的只有白名单里的列名；值一律 {@code ?} 绑定。
 */
@Repository
public class PetQueries {
  private final JdbcTemplate jdbc;
  private final RelationalRepository repository;

  public PetQueries(JdbcTemplate jdbc, RelationalRepository repository) {
    this.jdbc = jdbc;
    this.repository = repository;
  }

  /**
   * 分页查询宠物：COUNT 求总数 + 取一页 id + 逐条装配。
   *
   * <p><b>状态过滤是双重保险</b>：非后台请求无条件追加 {@code status IN ('on_sale','reserved')}，
   * 与用户自选的 {@code status} 再 AND。所以买家即使传 {@code status=off}，
   * 结果也只是空集（上层还会先以 400 拒绝）。把"不可见状态"写进 SQL 而不是只依赖上层校验，
   * 是为了让任何绕过 controller 的调用路径也拿不到下架商品。
   *
   * <p><b>价格与月龄条件总是出现在 WHERE 里</b>（用默认值 {@code 0..1e8} / {@code 0..360} 兜住），
   * 好处是 SQL 结构稳定、便于命中同一份预编译计划；代价是永远多两个无用条件。
   *
   * <p><b>月龄取 COALESCE</b>：优先用 {@code birth_date} 现算（{@code TIMESTAMPDIFF}），
   * 只有没填生日时才退回落库的 {@code age_months}。这与 {@code CatalogReader.card}
   * 展示层"有生日就用生日"的规则一致，保证"筛出来的"和"看到的"是同一个数。
   * 但要注意这里用的是数据库的 {@code CURRENT_DATE}，其时区取服务器/连接配置，
   * 而展示层固定用 {@code Asia/Shanghai}——跨时区部署时边界日的月龄可能差 1。
   *
   * <p><b>关键词是四路 LIKE 的子查询</b>：名称、品种、毛色，加上性格标签表里的
   * {@code EXISTS}。同一个关键词绑定 4 次。标签走子查询而不是 JOIN，是为了避免
   * 一只宠物多个命中标签时结果行重复（那样分页 total 也会虚高）。
   *
   * <p><b>后台忽略 {@code sort}</b>：固定按创建时间倒序——后台是"找最新录入的那只"，
   * 不需要按价格或推荐排序。
   *
   * <p><b>两条 SQL 而非窗口函数</b>：先 COUNT 再取 id，是为了让分页的 total 与 items
   * 在同一份 WHERE 下自洽。两次查询之间若有写入，total 与当页内容可能轻微不一致，
   * 这是可接受的最终一致性。
   *
   * <p><b>按 id 批量回表</b>：拿到 id 列表后用 {@code repository.findAll} 一次装配整页，
   * 主表与每张子表各一次 {@code IN}，查询数与页大小无关；复用模型层的解码逻辑。
   */
  public Map<String, Object> page(Map<String, Object> q, boolean admin) {
    List<String> where = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    if (!admin) where.add("p.status IN ('on_sale','reserved')");
    for (String key : List.of("status", "category", "breed", "gender", "color"))
      if (q.get(key) != null) {
        where.add("p." + key + "=?");
        args.add(q.get(key));
      }
    if (q.get("keyword") != null) {
      where.add(
          "(LOCATE(?,p.name)>0 OR LOCATE(?,p.breed)>0 OR LOCATE(?,p.color)>0 OR EXISTS (SELECT 1"
              + " FROM pet_personality_tags t WHERE t.parent_id=p.id AND LOCATE(?,t.tag)>0))");
      for (int i = 0; i < 4; i++) args.add(q.get("keyword"));
    }
    where.add("p.price_amount BETWEEN ? AND ?");
    args.add(queryLong(q, "minPriceAmount", 0, 0, 100000000));
    args.add(queryLong(q, "maxPriceAmount", 100000000, 0, 100000000));
    where.add(
        "COALESCE(TIMESTAMPDIFF(MONTH,p.birth_date,CURRENT_DATE),p.age_months) BETWEEN ? AND ?");
    args.add(queryLong(q, "minAgeMonths", 0, 0, 360));
    args.add(queryLong(q, "maxAgeMonths", 360, 0, 360));
    String filter = String.join(" AND ", where),
        sort = Objects.toString(q.get("sort"), "comprehensive");
    String order =
        admin
            ? "p.created_at DESC,p.id DESC"
            : switch (sort) {
              case "price_asc" -> "p.price_amount ASC,p.id DESC";
              case "price_desc" -> "p.price_amount DESC,p.id DESC";
              case "newest" -> "p.published_at DESC,p.id DESC";
              default ->
                  "p.is_recommended DESC,p.recommendation_order ASC,p.published_at DESC,p.id DESC";
            };
    long page = queryLong(q, "page", 1, 1, Integer.MAX_VALUE),
        size = queryLong(q, "pageSize", 20, 1, 100);
    long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM pets p WHERE " + filter, Long.class, args.toArray());
    args.add(size);
    args.add((page - 1) * size);
    var ids =
        jdbc.queryForList(
            "SELECT p.id FROM pets p WHERE " + filter + " ORDER BY " + order + " LIMIT ? OFFSET ?",
            String.class,
            args.toArray());
    return map(
        "items",
        repository.findAll("pet", ids),
        "total",
        total,
        "page",
        page,
        "pageSize",
        size);
  }
}
