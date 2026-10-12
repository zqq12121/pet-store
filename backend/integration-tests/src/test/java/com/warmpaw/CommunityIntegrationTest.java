package com.warmpaw;

import static com.warmpaw.common.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 独立 H2 中验证真实 HTTP、事务、附件归属与通知；不向运行库写演示评论。 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:community;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "spring.sql.init.mode=always", "app.mock-providers=true", "app.redis-enabled=false",
    "app.admin-password=CommunityTest2026!", "app.storage=./target/community-files", "PAW_OSS_ENABLED=false"})
@ActiveProfiles("test")
class CommunityIntegrationTest {
  @Autowired BusinessRepository store;
  @Autowired AuthService auth;
  @Autowired FileService files;
  @Autowired PlatformTransactionManager manager;
  @Autowired org.springframework.web.context.WebApplicationContext context;
  MockMvc mvc;
  String owner, other, buyerToken, otherToken, adminToken;
  AuthService.Actor buyerActor;

  @BeforeEach void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
    new TransactionTemplate(manager).execute(tx -> {
      store.lock();
      var buyer = user(); var second = user();
      owner = text(buyer, "id"); other = text(second, "id");
      buyerToken = text(auth.login(buyer, "buyer"), "accessToken");
      otherToken = text(auth.login(second, "buyer"), "accessToken");
      adminToken = text(auth.login(store.byKey("admin", "admin"), "admin"), "accessToken");
      return null;
    });
    buyerActor = auth.identify("Bearer " + buyerToken);
  }

  Map<String, Object> user() {
    String phone = "139" + String.format("%08d", Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 100000000));
    return store.create("user", null, phone, map("phone", phone, "nickname", "社区测试宠友"));
  }

  MockHttpServletResponse call(String method, String path, Map<String, Object> body, String token) throws Exception {
    var request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), "/api/v1" + path)
        .header("Idempotency-Key", UUID.randomUUID().toString());
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (body != null) request.contentType("application/json").content(write(body));
    return mvc.perform(request).andReturn().getResponse();
  }

  Map<String, Object> data(MockHttpServletResponse response, int status) throws Exception {
    assertEquals(status, response.getStatus(), response.getContentAsString());
    return object(read(response.getContentAsString()), "data");
  }

  Map<String, Object> postBody() {
    return map("type", "post", "title", "模拟养宠心得", "category", "experience", "body", "模拟资料，仅供测试：分享喂养经验。");
  }

  String post() throws Exception {
    return text(data(call("POST", "/community/contents", postBody(), buyerToken), 201), "id");
  }

  String order(String status, String ownerId) {
    return new TransactionTemplate(manager).execute(tx -> {
      store.lock();
      // 测试上下文不自动造商品，订单资格用例自行建立最小模拟档案。
      var pet = store.create("pet", null, null, map("name", "模拟宠物", "category", "cat",
          "gender", "female", "priceAmount", 100, "ageMonths", 2, "weightKg", 1, "status", "sold"));
      return text(store.create("order", ownerId, null, map("orderNo", UUID.randomUUID().toString(),
          "status", status, "petId", pet.get("id"), "amount", 100, "refundedAmount", 0,
          "product", map("name", "模拟宠物"))), "id");
    });
  }

  Map<String, Object> reviewBody(String order) {
    return map("type", "review", "orderId", order, "rating", 5, "body", "模拟资料，仅供测试：到店体验评价。");
  }

  String comment(String content, String token, String replyTo) throws Exception {
    var body = map("body", "模拟资料，仅供测试：讨论回复");
    if (replyTo != null) body.put("replyToId", replyTo);
    return text(data(call("POST", "/community/contents/" + content + "/comments", body, token), 201), "id");
  }

  List<Map<String, Object>> comments(String id) throws Exception {
    var response = call("GET", "/community/contents/" + id + "/comments", null, null);
    assertEquals(200, response.getStatus());
    return objects(read(response.getContentAsString()), "data");
  }

  @Test void anonymousCanBrowseButCannotWriteOrAccessPrivateFilters() throws Exception {
    String id = post();
    assertEquals(200, call("GET", "/community/contents", null, null).getStatus());
    var view = data(call("GET", "/community/contents/" + id, null, null), 200);
    assertFalse(view.containsKey("orderId")); assertFalse(write(view).contains("phone"));
    assertEquals(401, call("POST", "/community/contents", postBody(), null).getStatus());
    assertEquals(401, call("POST", "/community/contents/" + id + "/comments", map("body", "你好"), null).getStatus());
    assertEquals(401, call("PUT", "/community/contents/" + id + "/reactions/like", map("active", true), null).getStatus());
    assertEquals(401, call("GET", "/community/contents?scope=mine", null, null).getStatus());
    assertEquals(401, call("GET", "/community/notices", null, null).getStatus());
    assertEquals(403, call("GET", "/admin/community/words", null, buyerToken).getStatus());
  }

  @Test void searchCategoryAndPublicationOrderAreRespected() throws Exception {
    var body = postBody(); String marker = UUID.randomUUID().toString();
    body.put("title", marker); body.put("category", "question");
    String id = text(data(call("POST", "/community/contents", body, buyerToken), 201), "id");
    var result = data(call("GET", "/community/contents?query=" + marker + "&category=question", null, null), 200);
    assertEquals(1, number(result, "total")); assertEquals(id, text(objects(result, "items").getFirst(), "id"));
    assertEquals(0, number(data(call("GET", "/community/contents?query=" + marker + "&category=daily", null, null), 200), "total"));
    assertEquals(400, call("GET", "/community/contents?page=0", null, null).getStatus());
  }

  @Test void onlyOwnCompletedOrdersCanBeReviewedAndDeletionNeverReleasesEligibility() throws Exception {
    assertEquals(409, call("POST", "/community/contents", reviewBody(order("reservation_confirmed", owner)), buyerToken).getStatus());
    assertEquals(404, call("POST", "/community/contents", reviewBody(order("completed", other)), buyerToken).getStatus());
    String order = order("completed", owner);
    var body = reviewBody(order);
    String id = text(data(call("POST", "/community/contents", body, buyerToken), 201), "id");
    assertEquals(409, call("POST", "/community/contents", body, buyerToken).getStatus());
    data(call("PUT", "/community/contents/" + id, map("body", "更新消费体验", "rating", 4), buyerToken), 200);
    assertEquals(404, call("DELETE", "/community/contents/" + id, null, otherToken).getStatus());
    data(call("DELETE", "/community/contents/" + id, null, buyerToken), 200);
    assertEquals(404, call("GET", "/community/contents/" + id, null, null).getStatus());
    assertEquals(409, call("POST", "/community/contents", body, buyerToken).getStatus());
    assertNotNull(store.byKey("community_content", order));
    assertEquals(201, call("POST", "/community/contents", reviewBody(order("completed", owner)), buyerToken).getStatus());
  }

  @Test void commentRetriesReplayWithoutDuplicatingDiscussionOrNotices() throws Exception {
    String id = post(), key = UUID.randomUUID().toString();
    var body = map("body", "模拟资料，仅供测试：请求重试");
    String first = null;
    for (int i = 0; i < 2; i++) {
      var response = mvc.perform(MockMvcRequestBuilders.post("/api/v1/community/contents/" + id + "/comments")
          .header("Authorization", "Bearer " + otherToken).header("Idempotency-Key", key)
          .contentType("application/json").content(write(body))).andReturn().getResponse();
      String result = text(data(response, 201), "id");
      if (first == null) first = result; else assertEquals(first, result);
    }
    assertEquals(1, comments(id).size());
    assertEquals(1, number(data(call("GET", "/community/notices", null, buyerToken), 200), "unreadCount"));
  }

  @Test void concurrentReviewsProduceExactlyOneRecord() throws Exception {
    String order = order("completed", owner); var body = reviewBody(order);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<Integer> request = () -> { start.await(); return call("POST", "/community/contents", body, buyerToken).getStatus(); };
      var a = pool.submit(request); var b = pool.submit(request); start.countDown();
      var statuses = new ArrayList<>(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
      Collections.sort(statuses); assertEquals(List.of(201, 409), statuses);
    }
    assertEquals(1, store.list("community_content").stream().filter(c -> order.equals(text(c, "orderId"))).count());
  }

  @Test void ownersCanEditButCannotChangeProtectedFieldsOrBypassHiddenStatus() throws Exception {
    String id = post();
    var body = map("title", "更新心得", "body", "更新后的经验", "category", "daily");
    assertEquals(404, call("PUT", "/community/contents/" + id, body, otherToken).getStatus());
    body.put("ownerId", other);
    assertEquals(400, call("PUT", "/community/contents/" + id, body, buyerToken).getStatus()); body.remove("ownerId");
    data(call("POST", "/admin/community/content/" + id + "/hide", null, adminToken), 200);
    assertEquals(404, call("GET", "/community/contents/" + id, null, otherToken).getStatus());
    var updated = data(call("PUT", "/community/contents/" + id, body, buyerToken), 200);
    assertEquals("hidden", text(updated, "status"));
    assertEquals(404, call("GET", "/community/contents/" + id, null, null).getStatus());
  }

  @Test void maliciousWordsApplyToCreatesEditsReviewsRepliesAndCanBeDisabled() throws Exception {
    String term = "测试恶意" + UUID.randomUUID().toString().substring(0, 8);
    var word = data(call("POST", "/admin/community/words", map("term", term, "enabled", true), adminToken), 201);
    var body = postBody(); body.put("body", term);
    assertEquals(422, call("POST", "/community/contents", body, buyerToken).getStatus());
    String id = post();
    assertEquals(422, call("PUT", "/community/contents/" + id, map("title", "更新", "body", term, "category", "daily"), buyerToken).getStatus());
    assertNotEquals(term, text(store.get("community_content", id), "body"));
    assertEquals(422, call("POST", "/community/contents/" + id + "/comments", map("body", String.join(" ", term.split(""))), otherToken).getStatus());
    var review = reviewBody(order("completed", owner)); review.put("body", term);
    assertEquals(422, call("POST", "/community/contents", review, buyerToken).getStatus());
    data(call("PUT", "/admin/community/words/" + word.get("id"), map("term", term, "enabled", false), adminToken), 200);
    data(call("POST", "/community/contents", body, buyerToken), 201);
    assertEquals(409, call("POST", "/admin/community/words", map("term", term, "enabled", true), adminToken).getStatus());
  }

  @Test void unicodeLengthBoundariesAndRatingRangesAreValidated() throws Exception {
    var body = postBody(); body.put("title", "🐾".repeat(80)); body.put("body", "字".repeat(5000));
    assertEquals(201, call("POST", "/community/contents", body, buyerToken).getStatus());
    body.put("title", "🐾".repeat(81)); assertEquals(400, call("POST", "/community/contents", body, buyerToken).getStatus());
    body.put("title", "心得"); body.put("body", "字".repeat(5001)); assertEquals(400, call("POST", "/community/contents", body, buyerToken).getStatus());
    String id = post();
    assertEquals(201, call("POST", "/community/contents/" + id + "/comments", map("body", "🐾".repeat(500)), otherToken).getStatus());
    assertEquals(400, call("POST", "/community/contents/" + id + "/comments", map("body", "🐾".repeat(501)), otherToken).getStatus());
    var review = reviewBody(order("completed", owner)); review.put("rating", 6);
    assertEquals(400, call("POST", "/community/contents", review, buyerToken).getStatus());
    review.put("rating", 1); review.put("body", "字".repeat(1001));
    assertEquals(400, call("POST", "/community/contents", review, buyerToken).getStatus());
    review.put("body", "字".repeat(1000)); assertEquals(201, call("POST", "/community/contents", review, buyerToken).getStatus());
  }

  @Test void replyThreadsStayTwoLevelsAndRootDeletionHidesEntireThread() throws Exception {
    String id = post(); String root = comment(id, otherToken, null);
    String reply = comment(id, buyerToken, root); String next = comment(id, otherToken, reply);
    var entries = comments(id);
    assertEquals(root, text(entries.stream().filter(c -> next.equals(c.get("id"))).findFirst().orElseThrow(), "rootId"));
    data(call("DELETE", "/community/comments/" + reply, null, buyerToken), 200);
    entries = comments(id); assertEquals(2, entries.size());
    assertEquals("原回复已删除", text(entries.stream().filter(c -> next.equals(c.get("id"))).findFirst().orElseThrow(), "replyToName"));
    assertEquals(404, call("DELETE", "/community/comments/" + root, null, buyerToken).getStatus());
    data(call("DELETE", "/community/comments/" + root, null, otherToken), 200);
    assertTrue(comments(id).isEmpty());
    assertEquals(404, call("POST", "/community/contents/" + id + "/comments", map("body", "回复", "replyToId", next), buyerToken).getStatus());
  }

  @Test void repliesCannotCrossContentAndHiddenRootCannotBeRepliedTo() throws Exception {
    String id = post(), second = post(), root = comment(id, otherToken, null);
    assertEquals(404, call("POST", "/community/contents/" + second + "/comments", map("body", "回复", "replyToId", root), buyerToken).getStatus());
    data(call("POST", "/admin/community/comment/" + root + "/hide", null, adminToken), 200);
    assertTrue(comments(id).isEmpty());
    assertEquals(404, call("POST", "/community/contents/" + id + "/comments", map("body", "回复", "replyToId", root), buyerToken).getStatus());
  }

  @Test void reactionsAreIdempotentAndFavoritesRespectVisibility() throws Exception {
    String id = post(), path = "/community/contents/" + id + "/reactions/";
    data(call("PUT", path + "like", map("active", true), otherToken), 200);
    assertEquals(1, number(data(call("PUT", path + "like", map("active", true), otherToken), 200), "likeCount"));
    data(call("PUT", path + "favorite", map("active", true), otherToken), 200);
    assertTrue(objects(data(call("GET", "/community/contents?scope=favorites", null, otherToken), 200), "items").stream().anyMatch(c -> id.equals(c.get("id"))));
    assertEquals(0, number(data(call("PUT", path + "like", map("active", false), otherToken), 200), "likeCount"));
    data(call("DELETE", "/community/contents/" + id, null, buyerToken), 200);
    assertTrue(objects(data(call("GET", "/community/contents?scope=favorites", null, otherToken), 200), "items").stream().noneMatch(c -> id.equals(c.get("id"))));
  }

  @Test void noticesReachOnlyRecipientAndCannotBeReadByAnotherUser() throws Exception {
    String id = post(); comment(id, buyerToken, null);
    assertEquals(0, number(data(call("GET", "/community/notices", null, buyerToken), 200), "unreadCount"));
    String root = comment(id, otherToken, null);
    var notices = data(call("GET", "/community/notices", null, buyerToken), 200);
    assertEquals(1, number(notices, "unreadCount"));
    String notice = text(objects(notices, "items").getFirst(), "id");
    assertEquals(404, call("PUT", "/community/notices/" + notice + "/read", null, otherToken).getStatus());
    data(call("PUT", "/community/notices/" + notice + "/read", null, buyerToken), 200);
    assertEquals(0, number(data(call("GET", "/community/notices", null, buyerToken), 200), "unreadCount"));
    comment(id, buyerToken, root);
    assertEquals(1, number(data(call("GET", "/community/notices", null, otherToken), 200), "unreadCount"));
    data(call("DELETE", "/community/comments/" + root, null, otherToken), 200);
    assertEquals(false, objects(data(call("GET", "/community/notices", null, otherToken), 200), "items").getFirst().get("available"));
  }

  @Test void shopRepliesAreIdentifiedAndFilteredLikeBuyerComments() throws Exception {
    String id = text(data(call("POST", "/community/contents", reviewBody(order("completed", owner)), buyerToken), 201), "id");
    comment(id, otherToken, null);
    var reply = data(call("POST", "/admin/community/contents/" + id + "/comments", map("body", "谢谢反馈"), adminToken), 201);
    assertEquals(true, reply.get("isShop")); assertEquals("店铺", reply.get("authorName"));
    assertEquals(403, call("POST", "/admin/community/contents/" + post() + "/comments", map("body", "回复"), adminToken).getStatus());
    assertEquals(422, call("POST", "/admin/community/contents/" + id + "/comments", map("body", "傻逼"), adminToken).getStatus());
  }

  @Test void reportsCanBeHandledWithoutGrantingBuyerModerationRights() throws Exception {
    String id = post();
    var report = data(call("POST", "/community/reports", map("targetType", "content", "targetId", id, "reason", "模拟举报"), otherToken), 201);
    String path = "/admin/community/reports/" + report.get("id");
    assertEquals(403, call("PUT", path, map("action", "hide"), otherToken).getStatus());
    data(call("PUT", path, map("action", "hide"), adminToken), 200);
    assertEquals("handled", text(store.get("community_report", text(report, "id")), "status"));
    assertEquals(404, call("GET", "/community/contents/" + id, null, null).getStatus());
    data(call("PUT", path, map("action", "hide"), adminToken), 200);
  }

  @Test void uploadedCommunityImagesHaveOwnerSizeTypeAndPublicationChecks() throws Exception {
    var output = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
    var file = files.upload(new MockMultipartFile("file", "demo.png", "image/png", output.toByteArray()), "community_image", null, buyerActor);
    String image = text(file, "id");
    assertEquals(404, call("GET", "/media/" + image, null, null).getStatus());
    var body = postBody(); body.put("imageFileIds", List.of(image));
    assertEquals(422, call("POST", "/community/contents", body, otherToken).getStatus());
    String id = text(data(call("POST", "/community/contents", body, buyerToken), 201), "id");
    assertEquals(200, call("GET", "/media/" + image, null, null).getStatus());
    body.put("imageFileIds", Collections.nCopies(10, image));
    assertEquals(400, call("POST", "/community/contents", body, buyerToken).getStatus());
    data(call("POST", "/admin/community/content/" + id + "/hide", null, adminToken), 200);
    assertEquals(404, call("GET", "/media/" + image, null, null).getStatus());
    assertThrows(com.warmpaw.common.ApiException.class, () -> files.upload(
        new MockMultipartFile("file", "fake.png", "image/png", "<html>not image</html>".getBytes()), "community_image", null, buyerActor));
    var oversized = assertThrows(com.warmpaw.common.ApiException.class, () -> files.upload(
        new MockMultipartFile("file", "large.png", "image/png", new byte[5 * 1024 * 1024 + 1]), "community_image", null, buyerActor));
    assertEquals("FILE_TOO_LARGE", oversized.code);
  }
}
