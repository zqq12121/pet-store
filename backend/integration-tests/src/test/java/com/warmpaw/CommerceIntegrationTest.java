package com.warmpaw;

import static com.warmpaw.common.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 按接口文档的竞态、幂等、权限和实物交付约束测试，使用隔离数据库。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      // 显式指定独立测试库时复用完整业务测试，默认仍使用隔离 H2。
      "spring.datasource.url=${PAW_TEST_DB_URL:jdbc:h2:mem:commerce;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000}",
      "spring.datasource.username=${PAW_TEST_DB_USERNAME:sa}",
      "spring.datasource.password=${PAW_TEST_DB_PASSWORD:}",
      "spring.sql.init.mode=always",
      "app.mock-providers=true",
      "app.redis-enabled=${PAW_TEST_REDIS_ENABLED:false}",
      "spring.data.redis.host=${PAW_REDIS_HOST:127.0.0.1}",
      "spring.data.redis.port=${PAW_REDIS_PORT:6380}",
      "spring.data.redis.password=${PAW_REDIS_PASSWORD:}",
      "spring.data.redis.database=15",
      "app.admin-password=LocalTestPassword2026",
      "app.storage=./target/test-files"
    })
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CommerceIntegrationTest {
  @Autowired BusinessRepository store;
  @Autowired org.springframework.web.context.WebApplicationContext webContext;
  ContractClient business;
  final Map<String, String> tokens = new ConcurrentHashMap<>();
  @Autowired CatalogService catalog;
  @Autowired AuthService auth;
  @Autowired PaymentService payments;
  @Autowired PaymentWorker worker;
  @Autowired FileService files;
  @Autowired OrderService orders;
  @Autowired PlatformTransactionManager manager;
  @Autowired Environment env;
  TransactionTemplate tx;
  AuthService.Actor admin, buyer, other;
  String image, proof;
  static int counter = 10000;

  @BeforeEach
  void fixture() {
    tx = new TransactionTemplate(manager);
    business =
        new ContractClient(
            org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(
                    webContext)
                .build());
    tx.execute(
        s -> {
          store.lock();
          Map<String, Object> a = store.byKey("admin", "admin");
          admin = new AuthService.Actor(text(a, "id"), "admin", null, "");
          Map<String, Object> shop = catalog.shop();
          shop.put("businessHours", "每天 10:00-20:00");
          store.save(shop);
          String phone = "139000" + (counter++);
          Map<String, Object> u =
              store.create(
                  "user", null, phone, map("phone", phone, "nickname", "测试买家", "avatarUrl", null));
          buyer = new AuthService.Actor(text(u, "id"), "buyer", phone, "");
          String phone2 = "139000" + (counter++);
          Map<String, Object> v =
              store.create(
                  "user",
                  null,
                  phone2,
                  map("phone", phone2, "nickname", "另一买家", "avatarUrl", null));
          other = new AuthService.Actor(text(v, "id"), "buyer", phone2, "");
          image =
              text(
                  store.create(
                      "file",
                      admin.id(),
                      null,
                      map(
                          "purpose",
                          "pet_image",
                          "visibility",
                          "public",
                          "publicUrl",
                          "/test.png",
                          "mimeType",
                          "image/png",
                          "sizeBytes",
                          1,
                          "originalName",
                          "test.png")),
                  "id");
          proof =
              text(
                  store.create(
                      "file",
                      admin.id(),
                      null,
                      map(
                          "purpose",
                          "quarantine_public",
                          "visibility",
                          "public",
                          "publicUrl",
                          "/proof.png",
                          "mimeType",
                          "image/png",
                          "sizeBytes",
                          1,
                          "originalName",
                          "proof.png")),
                  "id");
          for (String type : List.of("live_pet_trade", "pickup_confirmation")) {
            if (store.list("agreement").stream()
                .noneMatch(
                    a2 ->
                        type.equals(text(a2, "type")) && "published".equals(text(a2, "status")))) {
              Map<String, Object> b =
                  map(
                      "type",
                      type,
                      "version",
                      "test-v1",
                      "title",
                      "仅用于测试的协议",
                      "content",
                      "仅用于自动化测试，不构成真实交易协议。");
              if (type.equals("live_pet_trade")) b.put("healthGuaranteeDays", 7);
              Map<String, Object> ag = catalog.createAgreement(b, admin.id());
              catalog.publishAgreement(text(ag, "id"), map("reviewConfirmed", true), admin.id());
            }
          }
          return null;
        });
  }

  /** 测试请求经过真实 Spring MVC 映射、认证与事务，不再绕过 Controller 调旧路由器。 */
  private class ContractClient {
    private final org.springframework.test.web.servlet.MockMvc mvc;

    ContractClient(org.springframework.test.web.servlet.MockMvc mvc) {
      this.mvc = mvc;
    }

    com.warmpaw.application.OperationResult execute(
        String method,
        String path,
        Map<String, Object> body,
        Map<String, Object> query,
        String key,
        AuthService.Actor actor,
        String ip) {
      try {
        var request =
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                    org.springframework.http.HttpMethod.valueOf(method), "/api/v1" + path)
                .contentType("application/json")
                .content(write(body))
                .with(
                    req -> {
                      req.setRemoteAddr(ip);
                      return req;
                    });
        query.forEach((name, value) -> request.param(name, String.valueOf(value)));
        if (key != null) request.header("Idempotency-Key", key);
        if (actor != null) {
          String token =
              tokens.computeIfAbsent(
                  actor.id(),
                  id ->
                      tx.execute(
                          status ->
                              text(
                                  auth.login(
                                      store.get(
                                          actor.role().equals("admin") ? "admin" : "user", id),
                                      actor.role()),
                                  "accessToken")));
          request.header("Authorization", "Bearer " + token);
        }
        var response = mvc.perform(request).andReturn().getResponse();
        Map<String, Object> envelope =
            read(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        if (response.getStatus() >= 400)
          throw new ApiException(
              response.getStatus(), text(envelope, "code"), text(envelope, "message"));
        return new com.warmpaw.application.OperationResult(
            response.getStatus(), envelope.get("data"));
      } catch (ApiException e) {
        throw e;
      } catch (Exception e) {
        throw new AssertionError("接口请求失败: " + method + " " + path, e);
      }
    }
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> call(
      String method, String path, Map<String, Object> body, AuthService.Actor actor) {
    return (Map<String, Object>)
        business
            .execute(
                method,
                path,
                body,
                map(),
                UUID.randomUUID().toString(),
                actor,
                "test-" + buyer.id())
            .data();
  }

  Map<String, Object> pet() {
    Map<String, Object> b =
        map(
            "name",
            "测试宠物",
            "category",
            "cat",
            "breed",
            "中华田园猫",
            "priceAmount",
            10000,
            "gender",
            "female",
            "ageMonths",
            6,
            "weightKg",
            2.5,
            "color",
            "橘色",
            "vaccineStatus",
            "测试记录",
            "dewormStatus",
            "测试记录",
            "description",
            "测试宠物",
            "feedingNotes",
            "测试记录",
            "healthDescription",
            "仅供测试",
            "imageFileIds",
            List.of(image),
            "quarantine",
            map(
                "certificateNo",
                "TEST-" + UUID.randomUUID(),
                "publicImageFileIds",
                List.of(proof),
                "originalFileIds",
                List.of(),
                "validUntil",
                LocalDate.now().plusDays(30).toString()),
            "isRecommended",
            true);
    Map<String, Object> p = call("POST", "/admin/pets", b, admin);
    return call(
        "POST",
        "/admin/pets/" + p.get("id") + "/publish",
        map(
            "version",
            p.get("version"),
            "healthyForSale",
            true,
            "quarantineVerified",
            true,
            "reviewNote",
            "人工核验测试"),
        admin);
  }

  Map<String, Object> orderBody(Map<String, Object> p, AuthService.Actor actor) {
    Map<String, Object> preview = call("POST", "/orders/preview", map("petId", p.get("id")), actor);
    return map(
        "visitAt",
        LocalDate.now(AppointmentHours.ZONE).plusDays(1).atTime(12, 0).atZone(AppointmentHours.ZONE).toOffsetDateTime().toString(),
        "petId",
        p.get("id"),
        "productVersion",
        preview.get("productVersion"),
        "expectedAmount",
        preview.get("amount"),
        "contactName",
        "测试本人",
        "contactPhone",
        actor.phone(),
        "agreementVersion",
        preview.get("agreementVersion"),
        "agreementContentHash",
        preview.get("agreementContentHash"),
        "agreementAccepted",
        true);
  }

  Map<String, Object> createOrder() {
    Map<String, Object> p = pet();
    return legacyOrder(p, buyer);
  }

  /** 历史支付/退款回归使用迁移前订单夹具；新建预约仍通过真实 HTTP 测试。 */
  Map<String, Object> legacyOrder(Map<String, Object> pet, AuthService.Actor actor) {
    Map<String, Object> body = orderBody(pet, actor);
    body.remove("visitAt");
    return tx.execute(status -> {
      store.lock();
      return orders.detail(orders.create(body, actor), false);
    });
  }

  Map<String, Object> pay(Map<String, Object> o) {
    return call("POST", "/dev/orders/" + o.get("id") + "/pay", map(), buyer);
  }

  String code(String kind, String id) throws Exception {
    return Files.readString(Path.of("data/local-inbox/" + kind + "-" + id + ".txt"));
  }

  Map<String, Object> confirm(Map<String, Object> o) throws Exception {
    Map<String, Object>
        sms = call("POST", "/orders/" + o.get("id") + "/pickup-verification-codes", map(), buyer),
        agreement = catalog.currentAgreement("pickup_confirmation");
    return call(
        "POST",
        "/orders/" + o.get("id") + "/pickup-confirmations",
        map(
            "smsRequestId",
            sms.get("smsRequestId"),
            "smsCode",
            code("sms", text(sms, "smsRequestId")),
            "confirmationVersion",
            agreement.get("version"),
            "confirmationContentHash",
            agreement.get("contentHash"),
            "accepted",
            true,
            "checks",
            map("mentalState", true, "eyesAndNose", true, "coat", true, "excretion", true)),
        buyer);
  }

  Map<String, Object> deliver(Map<String, Object> o) throws Exception {
    Map<String, Object> c = confirm(o);
    return call(
        "POST",
        "/admin/orders/" + o.get("id") + "/pickup",
        map(
            "confirmationId",
            c.get("confirmationId"),
            "pickupCode",
            object(o, "pickup").get("code"),
            "quarantineVerified",
            true),
        admin);
  }

  void assertCode(String expected, Runnable operation) {
    ApiException e = assertThrows(ApiException.class, operation::run);
    assertEquals(expected, e.code);
  }

  @Test
  @Order(1)
  void realHttpContractAndAuth() throws Exception {
    String base = "http://127.0.0.1:" + env.getProperty("local.server.port") + "/api/v1";
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<String> home =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/home")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, home.statusCode());
    assertEquals("OK", text(read(home.body()), "code"));
    assertTrue(object(read(home.body()), "data").containsKey("banners"));
    var invalid =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/home"))
                .header("Authorization", "Bearer forged")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(401, invalid.statusCode());
    var denied =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/admin/pets")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(401, denied.statusCode());
    var captcha = auth.captcha("admin_login", "http-test");
    Map<String, Object> login =
        map(
            "username",
            "admin",
            "password",
            "LocalTestPassword2026",
            "captchaId",
            captcha.get("captchaId"),
            "captchaCode",
            code("captcha", text(captcha, "captchaId")));
    var logged =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/admin/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(login)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, logged.statusCode(), logged.body());
    assertNotNull(object(read(logged.body()), "data").get("accessToken"));
  }

  @Test
  void concurrentPurchaseHasOneWinner() throws Exception {
    Map<String, Object> p = pet(), one = orderBody(p, buyer), two = orderBody(p, other);
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<String>> results = new ArrayList<>();
      for (var entry : List.of(Map.entry(buyer, one), Map.entry(other, two)))
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    call("POST", "/orders", entry.getValue(), entry.getKey());
                    return "OK";
                  } catch (ApiException e) {
                    return e.code;
                  }
                }));
      start.countDown();
      List<String> states = List.of(results.get(0).get(), results.get(1).get());
      assertEquals(1, states.stream().filter("OK"::equals).count());
      assertTrue(states.contains("PET_NOT_AVAILABLE"));
      assertNotNull(store.occupation(text(p, "id")));
    }
  }

  @Test
  void idempotencyReplaysAndRejectsDifferentBody() {
    Map<String, Object> p = pet(), b = orderBody(p, buyer);
    String key = UUID.randomUUID().toString();
    var first = business.execute("POST", "/orders", b, map(), key, buyer, "test");
    var second = business.execute("POST", "/orders", b, map(), key, buyer, "test");
    // HTTP 响应不暴露内部幂等元数据，比较前后返回的真实订单 ID。
    assertEquals(
        text((Map<String, Object>) first.data(), "id"),
        text((Map<String, Object>) second.data(), "id"));
    assertEquals(201, first.status());
    assertEquals(200, second.status());
    Map<String, Object> changed = copy(b);
    changed.put("remark", "different");
    assertCode(
        "IDEMPOTENCY_CONFLICT",
        () -> business.execute("POST", "/orders", changed, map(), key, buyer, "test"));
  }

  @Test
  void rejectsUnknownFieldsAndStalePrice() {
    Map<String, Object> p = pet(), b = orderBody(p, buyer);
    b.put("userId", other.id());
    assertCode("VALIDATION_ERROR", () -> call("POST", "/orders", b, buyer));
    b.remove("userId");
    b.put("expectedAmount", 1);
    assertCode("PRICE_CHANGED", () -> call("POST", "/orders", b, buyer));
    assertNull(store.occupation(text(p, "id")));
  }

  @Test
  void orderOwnershipAndRoleIsolation() {
    Map<String, Object> o = createOrder();
    assertCode("RESOURCE_NOT_FOUND", () -> call("GET", "/orders/" + o.get("id"), map(), other));
    assertCode("FORBIDDEN", () -> call("GET", "/admin/orders/" + o.get("id"), map(), buyer));
  }

  @Test
  void cancelledOrderReleasesOnlyItsOwnOccupancy() {
    Map<String, Object> o = createOrder();
    Map<String, Object> cancel = call("POST", "/orders/" + o.get("id") + "/cancel", map(), buyer);
    assertEquals("cancelled", cancel.get("status"));
    assertNull(store.occupation(text(object(o, "product"), "id")));
  }

  @Test
  void frontendPaymentRequestDoesNotMarkPaid() {
    Map<String, Object> o = createOrder();
    assertCode("ONLINE_PAYMENT_DISABLED", () -> call("POST", "/orders/" + o.get("id") + "/payments", map("scene", "h5"), buyer));
    Map<String, Object> state = call("GET", "/orders/" + o.get("id"), map(), buyer);
    assertEquals("pending_paid", state.get("status"));
    assertNull(state.get("pickup"));
  }

  @Test
  void duplicatePaymentFactAndAmountMismatch() {
    Map<String, Object> o = createOrder();
    Map<String, Object> p =
        tx.execute(
            s -> {
              store.lock();
              return payments.begin(store.get("order", text(o, "id")), map("scene", "h5"));
            });
    Map<String, Object> bad = payments.mockFact(p, o);
    object(bad, "amount").put("total", 1);
    assertCode(
        "INVALID_ARGUMENT",
        () ->
            tx.execute(
                s -> {
                  store.lock();
                  payments.paid(p, bad);
                  return null;
                }));
    Map<String, Object> fact = payments.mockFact(p, o);
    tx.execute(
        s -> {
          store.lock();
          payments.paid(p, fact);
          payments.paid(store.get("payment", text(p, "id")), fact);
          return null;
        });
    assertEquals("paid", store.get("order", text(o, "id")).get("status"));
  }

  @Test
  void buyerConfirmationAndDeliveryAreRequiredAndIdempotent() throws Exception {
    Map<String, Object> o = pay(createOrder());
    assertCode(
        "PICKUP_CONFIRMATION_REQUIRED",
        () ->
            call(
                "POST",
                "/admin/orders/" + o.get("id") + "/pickup",
                map(
                    "confirmationId",
                    "wrong",
                    "pickupCode",
                    object(o, "pickup").get("code"),
                    "quarantineVerified",
                    true),
                admin));
    Map<String, Object> confirmation = confirm(o);
    Map<String, Object> body =
        map(
            "confirmationId",
            confirmation.get("confirmationId"),
            "pickupCode",
            object(o, "pickup").get("code"),
            "quarantineVerified",
            true);
    String path = "/admin/orders/" + o.get("id") + "/pickup", key = UUID.randomUUID().toString();
    var first = business.execute("POST", path, body, map(), key, admin, "test");
    var second = business.execute("POST", path, body, map(), key, admin, "test");
    assertEquals(first.data(), second.data());
    assertEquals("sold", store.get("pet", text(object(o, "product"), "id")).get("status"));
    assertNull(orders.detail(store.get("order", text(o, "id")), false).get("pickup"));
  }

  @Test
  void pickupAndTimeoutRefundCannotBothSucceed() throws Exception {
    Map<String, Object> o = pay(createOrder());
    Map<String, Object> confirmation = confirm(o);
    tx.execute(
        s -> {
          store.lock();
          Map<String, Object> current = store.get("order", text(o, "id"));
          payments.refund(current, null, number(current, "amount"), "pickup_timeout");
          return null;
        });
    assertCode(
        "ORDER_STATE_CONFLICT",
        () ->
            call(
                "POST",
                "/admin/orders/" + o.get("id") + "/pickup",
                map(
                    "confirmationId",
                    confirmation.get("confirmationId"),
                    "pickupCode",
                    object(o, "pickup").get("code"),
                    "quarantineVerified",
                    true),
                admin));
    assertEquals("reserved", store.get("pet", text(object(o, "product"), "id")).get("status"));
  }

  @Test
  void refundBeforePickupRestocksOnlyAfterSuccess() {
    Map<String, Object> o = pay(createOrder());
    Map<String, Object> s =
        call(
            "POST",
            "/orders/" + o.get("id") + "/after-sales",
            map(
                "type",
                "refund_before_pickup",
                "requestedResolution",
                "full_refund",
                "reason",
                "本地退款测试"),
            buyer);
    Map<String, Object> review =
        call(
            "POST",
            "/admin/after-sales/" + s.get("id") + "/review",
            map(
                "version",
                s.get("version"),
                "decision",
                "approve",
                "resolution",
                "full_refund",
                "reason",
                "测试同意"),
            admin);
    assertEquals("refunding", review.get("status"));
    assertEquals("reserved", store.get("pet", text(object(o, "product"), "id")).get("status"));
    worker.processRefund(text(object(review, "refund"), "id"));
    assertEquals("on_sale", store.get("pet", text(object(o, "product"), "id")).get("status"));
    assertEquals("refunded", store.get("order", text(o, "id")).get("status"));
  }

  @Test
  void soldPetIsNotRestockedAfterHealthRefund() throws Exception {
    Map<String, Object> o = pay(createOrder());
    deliver(o);
    String report =
        tx.execute(
            status ->
                text(
                    store.create(
                        "file",
                        buyer.id(),
                        null,
                        map(
                            "purpose",
                            "diagnosis",
                            "orderId",
                            o.get("id"),
                            "visibility",
                            "private")),
                    "id"));
    Map<String, Object> s =
        call(
            "POST",
            "/orders/" + o.get("id") + "/after-sales",
            map(
                "type",
                "health_issue",
                "requestedResolution",
                "full_refund",
                "reason",
                "健康测试",
                "diagnosisAt",
                Instant.now().toString(),
                "diagnosisFileIds",
                List.of(report)),
            buyer);
    Map<String, Object> review =
        call(
            "POST",
            "/admin/after-sales/" + s.get("id") + "/review",
            map(
                "version",
                s.get("version"),
                "decision",
                "approve",
                "resolution",
                "full_refund",
                "reason",
                "同意"),
            admin);
    assertEquals("awaiting_return", review.get("status"));
    assertEquals("sold", store.get("pet", text(object(o, "product"), "id")).get("status"));
    Map<String, Object> returned =
        call(
            "POST",
            "/admin/after-sales/" + s.get("id") + "/returns",
            map("version", review.get("version"), "received", true, "conditionNotes", "已现场收到"),
            admin);
    worker.processRefund(text(object(returned, "refund"), "id"));
    assertEquals("off", store.get("pet", text(object(o, "product"), "id")).get("status"));
  }

  @Test
  void immutableSnapshotAndOptimisticVersion() {
    Map<String, Object> p = pet();
    Map<String, Object> o = call("POST", "/orders", orderBody(p, buyer), buyer);
    Map<String, Object> snapshot = copy(object(o, "productSnapshot"));
    assertCode(
        "VERSION_CONFLICT",
        () ->
            call(
                "POST",
                "/admin/pets/" + p.get("id") + "/unpublish",
                map("version", 1, "reason", "错误版本"),
                admin));
    assertEquals(
        snapshot, object(call("GET", "/orders/" + o.get("id"), map(), buyer), "productSnapshot"));
  }

  @Test
  void fakeImageAndPrivateOwnershipRejected() throws Exception {
    MockMultipartFile fake =
        new MockMultipartFile("file", "fake.png", "image/png", "<html>not image</html>".getBytes());
    assertCode("UNSUPPORTED_FILE_TYPE", () -> files.upload(fake, "avatar", null, buyer));
    Map<String, Object> o = createOrder();
    String file =
        tx.execute(
            s ->
                text(
                    store.create(
                        "file",
                        buyer.id(),
                        null,
                        map(
                            "purpose",
                            "diagnosis",
                            "orderId",
                            o.get("id"),
                            "visibility",
                            "private")),
                    "id"));
    assertCode("RESOURCE_NOT_FOUND", () -> files.access(file, other));
  }

  @Test
  void randomSmsCannotBeReusedAndLogoutRevokesToken() throws Exception {
    String phone = "138999" + (counter++);
    Map<String, Object> captcha = auth.captcha("sms", "sms-" + phone);
    Map<String, Object> sms =
        auth.sendLoginSms(
            map(
                "phone",
                phone,
                "purpose",
                "login",
                "captchaId",
                captcha.get("captchaId"),
                "captchaCode",
                code("captcha", text(captcha, "captchaId"))),
            "sms-" + phone);
    Map<String, Object> body =
        map(
            "phone",
            phone,
            "smsRequestId",
            sms.get("smsRequestId"),
            "smsCode",
            code("sms", text(sms, "smsRequestId")));
    Map<String, Object> login = auth.smsLogin(body);
    assertCode("SMS_CODE_EXPIRED", () -> auth.smsLogin(body));
    var actor = auth.identify("Bearer " + login.get("accessToken"));
    auth.logout(actor);
    assertCode("UNAUTHORIZED", () -> auth.identify("Bearer " + login.get("accessToken")));
  }

  Map<String, Object> healthSale(Map<String, Object> o, String resolution, long amount)
      throws Exception {
    String report =
        tx.execute(
            status ->
                text(
                    store.create(
                        "file",
                        buyer.id(),
                        null,
                        map(
                            "purpose",
                            "diagnosis",
                            "orderId",
                            o.get("id"),
                            "visibility",
                            "private")),
                    "id"));
    Map<String, Object> b =
        map(
            "type",
            "health_issue",
            "requestedResolution",
            resolution,
            "reason",
            "测试健康售后",
            "diagnosisAt",
            Instant.now().toString(),
            "diagnosisFileIds",
            List.of(report));
    if (resolution.equals("treatment_share")) b.put("requestedAmount", amount);
    return call("POST", "/orders/" + o.get("id") + "/after-sales", b, buyer);
  }

  @Test
  void partialRefundRestoresCompletedAndCannotOverRefund() throws Exception {
    Map<String, Object> o = pay(createOrder());
    deliver(o);
    Map<String, Object> s = healthSale(o, "treatment_share", 3000);
    Map<String, Object> review =
        call(
            "POST",
            "/admin/after-sales/" + s.get("id") + "/review",
            map(
                "version",
                s.get("version"),
                "decision",
                "approve",
                "resolution",
                "treatment_share",
                "approvedAmount",
                2000,
                "reason",
                "治疗分担测试"),
            admin);
    worker.processRefund(text(object(review, "refund"), "id"));
    Map<String, Object> current = store.get("order", text(o, "id"));
    assertEquals("completed", current.get("status"));
    assertEquals(2000, number(current, "refundedAmount"));
    assertEquals("sold", store.get("pet", text(object(o, "product"), "id")).get("status"));
    assertCode(
        "REFUND_AMOUNT_EXCEEDED",
        () ->
            tx.execute(
                status -> {
                  store.lock();
                  payments.refund(current, null, 9000, "treatment_share");
                  return null;
                }));
  }

  @Test
  void rejectedHealthClaimKeepsPetSold() throws Exception {
    Map<String, Object> o = pay(createOrder());
    deliver(o);
    Map<String, Object> s = healthSale(o, "full_refund", 0);
    call(
        "POST",
        "/admin/after-sales/" + s.get("id") + "/review",
        map("version", s.get("version"), "decision", "reject", "reason", "测试驳回"),
        admin);
    assertEquals("sold", store.get("pet", text(object(o, "product"), "id")).get("status"));
    assertEquals("completed", store.get("order", text(o, "id")).get("status"));
  }

  @Test
  void exchangeLocksReplacementAndRefundExitReleasesOnlyReplacement() throws Exception {
    tx.execute(
        status -> {
          store.lock();
          Map<String, Object> s = catalog.shop();
          s.put("exchangeEnabled", true);
          store.save(s);
          return null;
        });
    try {
      Map<String, Object> o = pay(createOrder());
      deliver(o);
      Map<String, Object> sale = healthSale(o, "exchange", 0);
      Map<String, Object> review =
          call(
              "POST",
              "/admin/after-sales/" + sale.get("id") + "/review",
              map(
                  "version",
                  sale.get("version"),
                  "decision",
                  "approve",
                  "resolution",
                  "exchange",
                  "reason",
                  "同意换宠"),
              admin);
      Map<String, Object> returned =
          call(
              "POST",
              "/admin/after-sales/" + sale.get("id") + "/returns",
              map("version", review.get("version"), "received", true, "conditionNotes", "原宠已收到"),
              admin);
      Map<String, Object> replacement = pet();
      Map<String, Object> purchaseBody = orderBody(replacement, other);
      call(
          "PUT",
          "/admin/after-sales/" + sale.get("id") + "/exchange",
          map("version", returned.get("version"), "replacementPetId", replacement.get("id")),
          admin);
      assertCode("PET_NOT_AVAILABLE", () -> call("POST", "/orders", purchaseBody, other));
      String consent =
          tx.execute(
              status ->
                  text(
                      store.create(
                          "file",
                          admin.id(),
                          null,
                          map(
                              "purpose",
                              "after_sale_evidence",
                              "orderId",
                              o.get("id"),
                              "visibility",
                              "private")),
                      "id"));
      Map<String, Object> latest = store.get("after_sale", text(sale, "id"));
      Map<String, Object> converted =
          call(
              "POST",
              "/admin/after-sales/" + sale.get("id") + "/exchange-refund",
              map(
                  "version",
                  latest.get("version"),
                  "reason",
                  "双方协商改退款",
                  "buyerConsentFileIds",
                  List.of(consent)),
              admin);
      assertEquals("on_sale", store.get("pet", text(replacement, "id")).get("status"));
      assertEquals("off", store.get("pet", text(object(o, "product"), "id")).get("status"));
      worker.processRefund(text(object(converted, "refund"), "id"));
    } finally {
      tx.execute(
          status -> {
            store.lock();
            Map<String, Object> s = catalog.shop();
            s.put("exchangeEnabled", false);
            store.save(s);
            return null;
          });
    }
  }

  @Test
  void expiredQuarantineAndConfirmationBlockCheckoutOrDelivery() throws Exception {
    Map<String, Object> p = pet();
    tx.execute(
        status -> {
          store.lock();
          Map<String, Object> current = store.get("pet", text(p, "id"));
          object(current, "quarantine").put("validUntil", LocalDate.now().minusDays(1).toString());
          store.save(current);
          return null;
        });
    assertFalse(catalog.purchasable(store.get("pet", text(p, "id"))));
    Map<String, Object> o = pay(createOrder());
    Map<String, Object> c = confirm(o);
    tx.execute(
        status -> {
          store.lock();
          Map<String, Object> current = store.get("order", text(o, "id"));
          object(current, "pickupEvidence")
              .put("validUntil", Instant.now().minusSeconds(1).toString());
          store.save(current);
          return null;
        });
    assertCode(
        "PICKUP_CONFIRMATION_EXPIRED",
        () ->
            call(
                "POST",
                "/admin/orders/" + o.get("id") + "/pickup",
                map(
                    "confirmationId",
                    c.get("confirmationId"),
                    "pickupCode",
                    object(o, "pickup").get("code"),
                    "quarantineVerified",
                    true),
                admin));
  }

  @Test
  void latePaymentNeverStealsNewOccupancy() {
    Map<String, Object> p = pet(), o = legacyOrder(p, buyer);
    Map<String, Object> payment =
        tx.execute(
            status -> {
              store.lock();
              return payments.begin(store.get("order", text(o, "id")), map("scene", "h5"));
            });
    call("POST", "/orders/" + o.get("id") + "/cancel", map(), buyer);
    worker.processPayment(text(payment, "id"), "127.0.0.1");
    Map<String, Object> newOrder =
        call("POST", "/orders", orderBody(store.get("pet", text(p, "id")), other), other);
    tx.execute(
        status -> {
          store.lock();
          payments.paid(store.get("payment", text(payment, "id")), payments.mockFact(payment, o));
          return null;
        });
    assertEquals("refunding", store.get("order", text(o, "id")).get("status"));
    assertEquals(newOrder.get("id"), store.occupation(text(p, "id")));
    Map<String, Object> refund =
        store.list("refund").stream()
            .filter(r -> Objects.equals(r.get("orderId"), o.get("id")))
            .findFirst()
            .orElseThrow();
    worker.processRefund(text(refund, "id"));
    assertEquals(newOrder.get("id"), store.occupation(text(p, "id")));
  }

  @Test
  void analyticsDeduplicatesAndRejectsForgedPayment() {
    Map<String, Object> event =
        map(
            "eventId",
            UUID.randomUUID().toString(),
            "eventType",
            "page_view",
            "occurredAt",
            Instant.now().toString(),
            "pagePath",
            "/",
            "petId",
            null,
            "properties",
            map("page", "home", "pathDepth", 0));
    assertEquals(
        1,
        number(
            call("POST", "/analytics/events", map("events", List.of(event)), buyer),
            "acceptedCount"));
    assertEquals(
        1,
        number(
            call("POST", "/analytics/events", map("events", List.of(event)), buyer),
            "duplicateCount"));
    event.put("eventType", "order_paid");
    assertCode(
        "VALIDATION_ERROR",
        () -> call("POST", "/analytics/events", map("events", List.of(event)), buyer));
  }

  @Test
  void realImageUploadAndPrivateLinksAreNotPublic() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(12, 12, BufferedImage.TYPE_INT_RGB), "png", output);
    Map<String, Object> asset =
        files.upload(
            new MockMultipartFile("file", "avatar.png", "image/png", output.toByteArray()),
            "avatar",
            null,
            buyer);
    assertTrue(Files.exists(files.content(text(asset, "id"), null)));
    Map<String, Object> o = createOrder();
    Map<String, Object> report =
        files.upload(
            new MockMultipartFile("file", "report.png", "image/png", output.toByteArray()),
            "diagnosis",
            text(o, "id"),
            buyer);
    assertNull(report.get("publicUrl"));
    assertCode("RESOURCE_NOT_FOUND", () -> files.content(text(report, "id"), null));
    Map<String, Object> link = files.access(text(report, "id"), buyer);
    String token = text(link, "url").split("access=")[1];
    assertTrue(Files.exists(files.content(text(report, "id"), token)));
  }

  @Test
  void splitControllersPreservePublicQueriesAndProfileUpdates() {
    Map<String, Object> pet = pet();
    assertEquals(pet.get("id"), call("GET", "/pets/" + pet.get("id"), map(), null).get("id"));
    var pets =
        business.execute(
            "GET", "/pets", map(), map("category", "cat", "pageSize", 1), null, null, "test");
    assertEquals(200, pets.status());
    assertTrue(((Map<?, ?>) pets.data()).containsKey("items"));
    assertEquals(
        "live_pet_trade",
        ((Map<?, ?>)
                business
                    .execute(
                        "GET",
                        "/agreements/current",
                        map(),
                        map("type", "live_pet_trade"),
                        null,
                        null,
                        "test")
                    .data())
            .get("type"));
    assertEquals("新的昵称", call("PATCH", "/me", map("nickname", "新的昵称"), buyer).get("nickname"));
    assertEquals("新的昵称", call("GET", "/me", map(), buyer).get("nickname"));
    assertCode("VALIDATION_ERROR", () -> call("PATCH", "/me", map("role", "admin"), buyer));
    for (String path : List.of("/shop", "/payment-capabilities"))
      assertEquals(200, business.execute("GET", path, map(), map(), null, null, "test").status());
  }

  @Test
  void splitAdminControllersRequireAdminRole() {
    for (String path :
        List.of(
            "/admin/pets",
            "/admin/shop",
            "/admin/orders",
            "/admin/after-sales",
            "/admin/agreements",
            "/admin/dashboard")) {
      assertCode("UNAUTHORIZED", () -> call("GET", path, map(), null));
      assertCode("FORBIDDEN", () -> call("GET", path, map(), buyer));
      assertEquals(200, business.execute("GET", path, map(), map(), null, admin, "test").status());
    }
    for (String path :
        List.of(
            "/admin/pets",
            "/admin/agreements",
            "/admin/pickups/lookup",
            "/admin/after-sales/missing/review",
            "/admin/orders/missing/pickup",
            "/admin/refunds/missing/retry"))
      assertCode("FORBIDDEN", () -> call("POST", path, map(), buyer));
  }

  @Test
  void splitWriteControllersStillRequireIdempotencyKeys() {
    for (String path :
        List.of(
            "/orders",
            "/orders/missing/cancel",
            "/orders/missing/payments",
            "/orders/missing/pickup-confirmations",
            "/orders/missing/after-sales",
            "/after-sales/missing/exchange-confirmations"))
      assertCode(
          "VALIDATION_ERROR",
          () -> business.execute("POST", path, map(), map(), null, buyer, "test"));
    for (String path :
        List.of(
            "/admin/orders/missing/pickup",
            "/admin/after-sales/missing/review",
            "/admin/after-sales/missing/returns",
            "/admin/after-sales/missing/exchange-delivery",
            "/admin/after-sales/missing/exchange-refund",
            "/admin/refunds/missing/retry"))
      assertCode(
          "VALIDATION_ERROR",
          () -> business.execute("POST", path, map(), map(), null, admin, "test"));
    assertCode(
        "VALIDATION_ERROR",
        () ->
            business.execute(
                "PUT", "/admin/after-sales/missing/exchange", map(), map(), null, admin, "test"));
  }

  @Test
  void unknownRoutesAndUnsupportedMethodsRemainNotFound() {
    assertCode("RESOURCE_NOT_FOUND", () -> call("GET", "/missing-api", map(), null));
    assertCode("RESOURCE_NOT_FOUND", () -> call("POST", "/home", map(), null));
    assertCode(
        "RESOURCE_NOT_FOUND", () -> call("POST", "/orders/missing/unknown-action", map(), buyer));
  }

  @Test
  void forgedPaymentCallbacksNeverChangePaymentFacts() throws Exception {
    int before = store.list("payment_fact").size();
    var mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(webContext)
            .build();
    var result =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/callbacks/wechat-pay/payments")
                    .contentType("application/json")
                    .content("{\"event_type\":\"TRANSACTION.SUCCESS\"}"))
            .andReturn()
            .getResponse();
    assertEquals(400, result.getStatus());
    assertEquals("FAIL", text(read(result.getContentAsString()), "code"));
    assertEquals(before, store.list("payment_fact").size());
  }
  Map<String, Object> appointment() {
    Map<String, Object> pet = pet();
    return call("POST", "/orders", orderBody(pet, buyer), buyer);
  }

  String appointmentPath(Map<String, Object> order, String action) {
    return "/admin/orders/" + order.get("id") + "/appointment/" + action;
  }

  @Test
  void appointmentCompletesOfflineOnceAndShowsContactToAdmin() {
    Map<String, Object> o = appointment();
    String id = text(o, "id"), pet = text(object(o, "product"), "id");
    assertEquals("pending_confirmation", o.get("status"));
    assertEquals(id, store.occupation(pet));
    assertEquals("offline_unpaid", text(object(o, "payment"), "status"));
    assertTrue(object(store.get("order", id), "appointment").containsKey("visitAt"));
    var adminView = call("GET", "/admin/orders/" + id, map(), admin);
    assertEquals(buyer.phone(), adminView.get("contactPhone"));
    assertEquals("测试本人", adminView.get("contactName"));
    assertCode("FORBIDDEN", () -> call("POST", appointmentPath(o, "confirm"), map(), buyer));
    assertCode("RESOURCE_NOT_FOUND", () -> call("POST", "/orders/" + id + "/cancel", map(), other));
    assertCode("ONLINE_PAYMENT_DISABLED", () -> call("POST", "/orders/" + id + "/payments", map("scene", "h5"), buyer));
    call("POST", appointmentPath(o, "confirm"), map(), admin);
    assertEquals("reserved", store.get("pet", pet).get("status"));
    assertCode("VALIDATION_ERROR", () -> call("POST", appointmentPath(o, "complete"), map(), admin));
    var dashboardBefore = call("GET", "/admin/dashboard", map(), admin);
    Map<String, Object> body = map("paymentReceived", true, "deliveryConfirmed", true, "quarantineVerified", true);
    String key = UUID.randomUUID().toString();
    business.execute("POST", appointmentPath(o, "complete"), body, map(), key, admin, "test");
    business.execute("POST", appointmentPath(o, "complete"), body, map(), key, admin, "test");
    var saved = store.get("order", id);
    assertEquals("completed", saved.get("status"));
    assertEquals("offline_received", object(saved, "payment").get("status"));
    assertEquals(admin.id(), object(saved, "appointment").get("completedBy"));
    var dashboardAfter = call("GET", "/admin/dashboard", map(), admin);
    assertEquals(number(dashboardBefore, "grossSalesAmount") + number(o, "amount"), number(dashboardAfter, "grossSalesAmount"));
    assertEquals(number(dashboardBefore, "paidOrderCount") + 1, number(dashboardAfter, "paidOrderCount"));
    assertEquals("sold", store.get("pet", pet).get("status"));
    assertNull(store.occupation(pet));
    assertTrue(strings(orders.eligibility(saved), "allowedTypes").isEmpty());
    assertTrue(store.list("payment").stream().noneMatch(payment -> id.equals(payment.get("orderId"))));
    assertCode("ORDER_STATE_CONFLICT", () -> call("POST", "/orders/" + id + "/cancel", map(), buyer));
  }

  @Test
  void appointmentLimitAndCancelReleaseInventory() {
    var first = appointment();
    var secondPet = pet();
    assertCode("ACTIVE_APPOINTMENT_EXISTS", () -> call("POST", "/orders", orderBody(secondPet, buyer), buyer));
    call("POST", appointmentPath(first, "confirm"), map(), admin);
    call("POST", "/orders/" + first.get("id") + "/cancel", map(), buyer);
    String firstPet = text(object(first, "product"), "id");
    assertNull(store.occupation(firstPet));
    assertEquals("on_sale", store.get("pet", firstPet).get("status"));
    var next = call("POST", "/orders", orderBody(secondPet, buyer), buyer);
    call("POST", appointmentPath(next, "cancel"), map("reason", "门店临时休息"), admin);
    assertEquals("门店临时休息", store.get("order", text(next, "id")).get("cancelReason"));
    assertNull(store.occupation(text(secondPet, "id")));
  }

  @Test
  void appointmentDeadlinesExpirePendingAndConfirmed() {
    for (boolean confirmed : List.of(false, true)) {
      var o = appointment();
      if (confirmed) call("POST", appointmentPath(o, "confirm"), map(), admin);
      var current = store.get("order", text(o, "id"));
      Instant visit = Instant.parse(text(object(current, "appointment"), "visitAt"));
      if (confirmed) assertEquals(visit.plus(Duration.ofHours(2)).toString(), current.get("expiresAt"));
      else assertTrue(!Instant.parse(text(current, "expiresAt")).isAfter(visit));
      tx.execute(status -> {
        store.lock();
        current.put("expiresAt", Instant.now().minusSeconds(1).toString());
        store.save(current);
        return null;
      });
      assertCode("APPOINTMENT_EXPIRED", () -> call("POST", appointmentPath(o, confirmed ? "complete" : "confirm"), map(), admin));
      worker.reconcile();
      assertEquals("expired", store.get("order", text(o, "id")).get("status"));
      assertNull(store.occupation(text(object(o, "product"), "id")));
    }
  }

  @Test
  void appointmentRejectsInvalidTimesBeforeOccupyingStock() {
    var pet = pet();
    var body = orderBody(pet, buyer);
    for (String visit : List.of("invalid", Instant.now().minusSeconds(60).toString(),
        Instant.now().plus(Duration.ofDays(8)).toString(),
        LocalDate.now(AppointmentHours.ZONE).plusDays(1).atTime(22, 0).atOffset(ZoneOffset.ofHours(8)).toString())) {
      body.put("visitAt", visit);
      assertCode("VALIDATION_ERROR", () -> call("POST", "/orders", body, buyer));
      assertNull(store.occupation(text(pet, "id")));
    }
    // 不含时区、恰好闭店的时刻和未配置营业时间都不能默许预约。
    assertThrows(ApiException.class, () -> AppointmentHours.validateVisit("2026-09-12T12:00:00", "每天 10:00-20:00", Instant.now()));
    assertThrows(ApiException.class, () -> AppointmentHours.parse("请联系门店确认"));
    assertThrows(ApiException.class, () -> AppointmentHours.parse("周一至周五 10:00-20:00"));
    Instant now = Instant.parse("2026-09-12T00:00:00Z");
    assertThrows(ApiException.class, () -> AppointmentHours.validateVisit("2026-09-12T20:00:00+08:00", "每天 10:00-20:00", now));
    assertEquals(Instant.parse("2026-09-12T02:00:00Z"), AppointmentHours.validateVisit("2026-09-12T10:00:00+08:00", "每天 10:00-20:00", now));
  }

}
