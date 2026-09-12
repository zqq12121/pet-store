package com.warmpaw.mapper;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** 所有动态值均参数绑定；单店写事务按固定行加锁，避免跨订单/退款的锁序死锁。 */
@Mapper
public interface ResourceMapper {
  @Select("SELECT id FROM business_lock WHERE id=1 FOR UPDATE")
  int lock();

  @Insert("INSERT INTO pet_occupancy(pet_id,order_id) VALUES(#{pet},#{order})")
  void occupy(String pet, String order);

  @Select("SELECT order_id FROM pet_occupancy WHERE pet_id=#{pet}")
  String occupation(String pet);

  @Delete("DELETE FROM pet_occupancy WHERE pet_id=#{pet} AND order_id=#{order}")
  int release(String pet, String order);

  @Select("SELECT * FROM idempotency WHERE scope_hash=#{scope}")
  Map<String, Object> replay(String scope);

  @Insert(
      "INSERT INTO"
          + " idempotency(scope_hash,body_hash,resource_id,response_body,http_status,created_at)"
          + " VALUES(#{scope},#{hash},#{resource},#{body},#{status},#{now})")
  void remember(String scope, String hash, String resource, String body, int status, String now);

  @Insert(
      "INSERT INTO audit_log(id,actor_id,action,resource_id,occurred_at)"
          + " VALUES(#{id},#{actor},#{action},#{resource},#{now})")
  void audit(String id, String actor, String action, String resource, String now);
}
