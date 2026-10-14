package com.warmpaw;

import static com.warmpaw.common.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.AuthService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 独立 H2 验证账号持久化、HTTP 权限和并发幂等，不接触运行数据库。 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:petfavorites;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "spring.sql.init.mode=always", "app.mock-providers=true", "app.redis-enabled=false",
    "app.admin-password=FavoriteTest2026!", "app.storage=./target/favorite-files", "PAW_OSS_ENABLED=false"})
@ActiveProfiles("test")
class PetFavoriteIntegrationTest {
  @Autowired BusinessRepository store;
  @Autowired AuthService auth;
  @Autowired PlatformTransactionManager manager;
  @Autowired org.springframework.web.context.WebApplicationContext context;
  MockMvc mvc;
  String token, otherToken, deviceToken, adminToken, petId, owner;

  @BeforeEach void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
    new TransactionTemplate(manager).execute(tx -> {
      store.lock();
      var user = store.create("user", null, null, map("nickname", "模拟微信用户"));
      owner = text(user, "id");
      token = text(auth.login(user, "buyer"), "accessToken");
      // 第二设备持有独立会话，但对应同一无手机号微信账号。
      deviceToken = text(auth.login(user, "buyer"), "accessToken");
      otherToken = text(auth.login(store.create("user", null, null, map("nickname", "另一模拟用户")), "buyer"), "accessToken");
      adminToken = text(auth.login(store.byKey("admin", "admin"), "admin"), "accessToken");
      petId = text(store.create("pet", null, null, map("name", "模拟动物", "category", "cat",
          "gender", "female", "priceAmount", 180000, "ageMonths", 3, "weightKg", 1,
          "status", "on_sale")), "id");
      return null;
    });
  }

  MockHttpServletResponse call(String method, String path, Map<String, Object> body, String session) throws Exception {
    var request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), "/api/v1/me/favorites" + path);
    if (session != null) request.header("Authorization", "Bearer " + session);
    if (body != null) request.contentType("application/json").content(write(body));
    return mvc.perform(request).andReturn().getResponse();
  }

  Map<String, Object> data(MockHttpServletResponse response, int status) throws Exception {
    assertEquals(status, response.getStatus(), response.getContentAsString());
    return object(read(response.getContentAsString()), "data");
  }

  @Test void favoritePersistsAcrossSessionsAndCancelIsIdempotent() throws Exception {
    data(call("PUT", "/" + petId, map("active", true), token), 200);
    data(call("PUT", "/" + petId, map("active", true), deviceToken), 200);
    var list = data(call("GET", "", null, deviceToken), 200);
    assertEquals(1, number(list, "total"));
    assertEquals(180000, number(objects(list, "items").getFirst(), "priceAmount"));
    assertEquals(true, data(call("GET", "/" + petId, null, deviceToken), 200).get("favorited"));
    data(call("PUT", "/" + petId, map("active", false), deviceToken), 200);
    data(call("PUT", "/" + petId, map("active", false), token), 200);
    assertEquals(0, number(data(call("GET", "", null, token), 200), "total"));
    data(call("PUT", "/" + petId, map("active", true), token), 200);
    assertEquals(1, store.list("pet_favorite").stream().filter(f -> owner.equals(text(f, "ownerId"))).count());
  }

  @Test void ownerComesOnlyFromBuyerSession() throws Exception {
    assertEquals(401, call("GET", "", null, null).getStatus());
    assertEquals(401, call("GET", "/" + petId, null, null).getStatus());
    assertEquals(401, call("PUT", "/" + petId, map("active", true), null).getStatus());
    assertEquals(403, call("PUT", "/" + petId, map("active", true), adminToken).getStatus());
    data(call("PUT", "/" + petId, map("active", true), token), 200);
    assertEquals(0, number(data(call("GET", "", null, otherToken), 200), "total"));
    assertEquals(false, data(call("GET", "/" + petId, null, otherToken), 200).get("favorited"));
    data(call("PUT", "/" + petId, map("active", false), otherToken), 200);
    assertEquals(true, data(call("GET", "/" + petId, null, token), 200).get("favorited"));
    assertEquals(400, call("PUT", "/" + petId, map("active", true, "ownerId", owner), otherToken).getStatus());
  }

  @Test void unavailableAnimalCanBeRemovedAndInvalidInputIsRejected() throws Exception {
    data(call("PUT", "/" + petId, map("active", true), token), 200);
    new TransactionTemplate(manager).execute(tx -> { store.lock(); var p = store.get("pet", petId); p.put("status", "off"); store.save(p); return null; });
    var item = objects(data(call("GET", "", null, token), 200), "items").getFirst();
    assertEquals(false, item.get("available")); assertFalse(item.containsKey("priceAmount"));
    assertEquals(404, call("PUT", "/" + petId, map("active", true), token).getStatus());
    data(call("PUT", "/" + petId, map("active", false), token), 200);
    assertEquals(400, call("PUT", "/" + petId, map("active", "true"), token).getStatus());
    assertEquals(404, call("PUT", "/missing", map("active", true), token).getStatus());
    assertEquals(400, call("GET", "?page=0", null, token).getStatus());
    assertEquals(400, call("GET", "?ownerId=" + owner, null, otherToken).getStatus());
  }

  @Test void concurrentSameAnimalCreatesOneFavoriteAndPaginationIsStable() throws Exception {
    var pool = Executors.newFixedThreadPool(4);
    try {
      var calls = new ArrayList<Callable<Integer>>();
      for (int i = 0; i < 8; i++) calls.add(() -> call("PUT", "/" + petId, map("active", true), token).getStatus());
      for (var result : pool.invokeAll(calls)) assertEquals(200, result.get());
    } finally { pool.shutdownNow(); }
    assertEquals(1, number(data(call("GET", "", null, deviceToken), 200), "total"));
    assertEquals(0, objects(data(call("GET", "?page=2&pageSize=1", null, token), 200), "items").size());
    assertEquals(1, store.list("pet_favorite").stream().filter(f -> owner.equals(text(f, "ownerId"))).count());
  }

  @Test void incrementalMigrationCanBeRepeatedWithoutChangingExistingData() throws Exception {
    data(call("PUT", "/" + petId, map("active", true), token), 200);
    int users = store.list("user").size(), pets = store.list("pet").size();
    var jdbc = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
    // 执行交付的增量文件两次，检查迁移标记和已有账号、动物、收藏均保留。
    try (var connection = jdbc.getDataSource().getConnection()) {
      var script = new org.springframework.core.io.FileSystemResource("../deploy/pet-favorites-migration.sql");
      for (int i = 0; i < 2; i++)
        org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection, script);
    }
    assertEquals(users, store.list("user").size()); assertEquals(pets, store.list("pet").size());
    assertEquals(true, data(call("GET", "/" + petId, null, deviceToken), 200).get("favorited"));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM schema_migrations WHERE version='pet-favorites-v1'", Integer.class));
  }
}
