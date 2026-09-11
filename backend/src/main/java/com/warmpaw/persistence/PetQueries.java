package com.warmpaw.persistence;

import static com.warmpaw.common.Json.*;
import static com.warmpaw.service.CatalogService.queryLong;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 宠物搜索、价格/年龄筛选和分页由数据库完成，避免加载整张宠物表。 */
@Repository
public class PetQueries {
  private final JdbcTemplate jdbc;
  private final RelationalRepository repository;
  public PetQueries(JdbcTemplate jdbc, RelationalRepository repository) {
    this.jdbc=jdbc;this.repository=repository;
  }
  public Map<String,Object> page(Map<String,Object> q,boolean admin) {
    List<String> where=new ArrayList<>();List<Object> args=new ArrayList<>();
    if(!admin)where.add("p.status IN ('on_sale','reserved')");
    for(String key:List.of("status","category","breed","gender","color")) if(q.get(key)!=null) {
      where.add("p."+key+"=?");args.add(q.get(key));
    }
    if(q.get("keyword")!=null) {
      where.add("(LOCATE(?,p.name)>0 OR LOCATE(?,p.breed)>0 OR LOCATE(?,p.color)>0 OR EXISTS (SELECT 1 FROM pet_personality_tags t WHERE t.parent_id=p.id AND LOCATE(?,t.tag)>0))");
      for(int i=0;i<4;i++)args.add(q.get("keyword"));
    }
    where.add("p.price_amount BETWEEN ? AND ?");
    args.add(queryLong(q,"minPriceAmount",0,0,100000000));args.add(queryLong(q,"maxPriceAmount",100000000,0,100000000));
    where.add("COALESCE(TIMESTAMPDIFF(MONTH,p.birth_date,CURRENT_DATE),p.age_months) BETWEEN ? AND ?");
    args.add(queryLong(q,"minAgeMonths",0,0,360));args.add(queryLong(q,"maxAgeMonths",360,0,360));
    String filter=String.join(" AND ",where),sort=Objects.toString(q.get("sort"),"comprehensive");
    String order=admin?"p.created_at DESC,p.id DESC":switch(sort) {
      case "price_asc" -> "p.price_amount ASC,p.id DESC";
      case "price_desc" -> "p.price_amount DESC,p.id DESC";
      case "newest" -> "p.published_at DESC,p.id DESC";
      default -> "p.is_recommended DESC,p.recommendation_order ASC,p.published_at DESC,p.id DESC";
    };
    long page=queryLong(q,"page",1,1,Integer.MAX_VALUE),size=queryLong(q,"pageSize",20,1,100);
    long total=jdbc.queryForObject("SELECT COUNT(*) FROM pets p WHERE "+filter,Long.class,args.toArray());
    args.add(size);args.add((page-1)*size);
    var ids=jdbc.queryForList("SELECT p.id FROM pets p WHERE "+filter+" ORDER BY "+order+" LIMIT ? OFFSET ?",String.class,args.toArray());
    return map("items",ids.stream().map(id -> repository.find("pet",id)).toList(),"total",total,"page",page,"pageSize",size);
  }
}
