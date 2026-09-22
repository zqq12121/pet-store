package com.warmpaw;

import static com.warmpaw.common.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.warmpaw.common.ApiException;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立 H2 验证真实持久化、事务锁、会话撤销与 HTTP 权限，不修改运行库。 */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:account_security;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "spring.sql.init.mode=always", "app.mock-providers=true", "app.redis-enabled=false",
    "app.admin-password=SecurityTest2026!", "app.storage=./target/security-files"})
@ActiveProfiles("test")
class AccountSecurityTest {
  @Autowired AuthService auth;
  @Autowired BusinessRepository store;
  @Autowired TemporaryStore temp;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager manager;
  @Autowired org.springframework.web.context.WebApplicationContext context;
  String phone, userId, token;
  AuthService.Actor actor;
  final String password="SecurityTest2026!", replacement="UpdatedTest2026!";

  @BeforeEach void createUser() {
    phone="139"+String.format("%08d", Math.floorMod(UUID.randomUUID().getMostSignificantBits(),100000000));
    new TransactionTemplate(manager).execute(s->{
      store.lock();
      var user=store.create("user",null,phone,map("phone",phone,"nickname","安全测试"));
      userId=text(user,"id");token=text(auth.login(user,"buyer"),"accessToken");return null;
    });
    actor=auth.identify("Bearer "+token);
  }

  Map<String,Object> reset(String purpose, String next) {
    String request=UUID.randomUUID().toString();
    temp.put("sms:"+request,write(map("phone",phone,"purpose",purpose,"scope","","hash",hash("123456"))),300);
    return map("phone",phone,"smsRequestId",request,"smsCode","123456","newPassword",next);
  }

  Map<String,Object> login(String account, String secret) {
    String captcha=UUID.randomUUID().toString();
    temp.put("captcha:"+captcha,write(map("purpose","password_login","hash",hash("1234"))),120);
    return auth.passwordLogin(map("account",account,"password",secret,"captchaId",captcha,"captchaCode","1234"),userId);
  }

  void authenticate() {
    token=text(login(phone,password),"accessToken");actor=auth.identify("Bearer "+token);
  }

  void age(String column,int days) {
    // 字段只由测试常量传入；模拟越过服务端冷却期，不修改系统时钟。
    jdbc.update("UPDATE users SET "+column+"=? WHERE id=?",Instant.now().minus(Duration.ofDays(days)).toString(),userId);
  }

  @Test void firstPasswordIsHashedAndRevokesEverySession() {
    var second=new TransactionTemplate(manager).execute(s->auth.login(store.get("user",userId),"buyer"));
    var request=reset("password_reset",password);
    auth.resetPassword(request);
    String stored=jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,userId);
    assertNotEquals(password,stored);assertTrue(stored.contains(":"));
    assertNotEquals(stored,auth.passwordHash(password));
    assertEquals(401,assertThrows(ApiException.class,()->auth.identify("Bearer "+token)).status);
    assertThrows(ApiException.class,()->auth.identify("Bearer "+text(second,"accessToken")));
    assertThrows(ApiException.class,()->auth.resetPassword(request));
    var result=login(phone,password);
    assertFalse(write(result).contains(stored));
    assertFalse(write(result).contains("passwordHash"));
  }

  @Test void resetAndChangeShareSevenDayCooldownAndOldPasswordIsRequired() {
    auth.resetPassword(reset("password_reset",password));authenticate();
    assertEquals("CHANGE_COOLDOWN",assertThrows(ApiException.class,()->
        auth.resetPassword(reset("password_reset",replacement))).code);
    assertEquals("CHANGE_COOLDOWN",assertThrows(ApiException.class,()->
        auth.changePassword(actor,map("oldPassword",password,"newPassword",replacement),"buyer")).code);
    age("password_changed_at",7);
    assertEquals("PASSWORD_INVALID",assertThrows(ApiException.class,()->
        auth.changePassword(actor,map("oldPassword","wrongPassword1","newPassword",replacement),"buyer")).code);
    assertEquals("PASSWORD_UNCHANGED",assertThrows(ApiException.class,()->
        auth.changePassword(actor,map("oldPassword",password,"newPassword",password),"buyer")).code);
    auth.changePassword(actor,map("oldPassword",password,"newPassword",replacement),"buyer");
    assertThrows(ApiException.class,()->auth.identify("Bearer "+token));
    assertThrows(ApiException.class,()->login(phone,password));
    assertNotNull(login(phone,replacement).get("accessToken"));
  }

  @Test void usernameIsNormalizedUniqueAndLimitedForThreeDays() {
    String name="u"+UUID.randomUUID().toString().replace("-","").substring(0,20);
    assertEquals(name,auth.changeUsername(actor,map("username",name.toUpperCase(Locale.ROOT))).get("username"));
    assertEquals("CHANGE_COOLDOWN",assertThrows(ApiException.class,()->
        auth.changeUsername(actor,map("username",name+"x"))).code);
    assertThrows(ApiException.class,()->auth.changeUsername(actor,map("username",phone)));
    age("username_changed_at",3);
    auth.changeUsername(actor,map("username",name+"x"));
    new TransactionTemplate(manager).execute(s->{
      store.create("user",null,"other"+userId,map("phone","13800000000","nickname","other","username",name));return null;
    });
    age("username_changed_at",3);
    assertEquals("USERNAME_TAKEN",assertThrows(ApiException.class,()->
        auth.changeUsername(actor,map("username",name))).code);
    auth.resetPassword(reset("password_reset",password));
    assertNotNull(login((name+"x").toUpperCase(Locale.ROOT),password).get("accessToken"));
  }

  @Test void smsPurposePhoneAndPasswordValidationCannotBeBypassed() {
    assertEquals("SMS_CODE_INVALID",assertThrows(ApiException.class,()->
        auth.resetPassword(reset("login",password))).code);
    var request=reset("password_reset",password);request.put("phone","13800000000");
    assertEquals("SMS_CODE_INVALID",assertThrows(ApiException.class,()->auth.resetPassword(request)).code);
    assertEquals("VALIDATION_ERROR",assertThrows(ApiException.class,()->
        auth.resetPassword(reset("password_reset","onlyletterslong"))).code);
    assertThrows(ApiException.class,()->login(phone,password));
    assertNull(store.get("user",userId).get("passwordHash"));
  }

  @Test void concurrentResetsOnlyAllowOneSuccessfulWrite() throws Exception {
    var first=reset("password_reset",password);var second=reset("password_reset",replacement);
    try(var pool=Executors.newFixedThreadPool(2)) {
      CountDownLatch start=new CountDownLatch(1);
      Callable<Boolean> a=()->{start.await();try{auth.resetPassword(first);return true;}catch(ApiException e){assertEquals("CHANGE_COOLDOWN",e.code);return false;}};
      Callable<Boolean> b=()->{start.await();try{auth.resetPassword(second);return true;}catch(ApiException e){assertEquals("CHANGE_COOLDOWN",e.code);return false;}};
      var fa=pool.submit(a);var fb=pool.submit(b);start.countDown();
      assertNotEquals(fa.get(15,TimeUnit.SECONDS),fb.get(15,TimeUnit.SECONDS));
    }
  }

  @Test void adminPasswordChangeIsProtectedAndRevokesAdminSessions() {
    var admin=new TransactionTemplate(manager).execute(s->store.create("admin",null,userId,
        map("username",userId.substring(0,30),"passwordHash",auth.passwordHash(password))));
    var session=new TransactionTemplate(manager).execute(s->auth.login(admin,"admin"));
    String adminToken=text(session,"accessToken");
    var principal=auth.identify("Bearer "+adminToken);
    assertEquals(403,assertThrows(ApiException.class,()->
        auth.changePassword(actor,map("oldPassword",password,"newPassword",replacement),"admin")).status);
    auth.changePassword(principal,map("oldPassword",password,"newPassword",replacement),"admin");
    assertThrows(ApiException.class,()->auth.identify("Bearer "+adminToken));
    var next=new TransactionTemplate(manager).execute(s->auth.login(store.get("admin",text(admin,"id")),"admin"));
    var nextActor=auth.identify("Bearer "+text(next,"accessToken"));
    assertEquals("CHANGE_COOLDOWN",assertThrows(ApiException.class,()->
        auth.changePassword(nextActor,map("oldPassword",replacement,"newPassword",password),"admin")).code);
  }

  @Test void httpEndpointsEnforceAuthenticationAndReturnSafeProfiles() throws Exception {
    var mvc=MockMvcBuilders.webAppContextSetup(context).build();
    mvc.perform(put("/api/v1/me/username").contentType("application/json").content("{\"username\":\"alice\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/admin/auth/security").header("Authorization","Bearer "+token))
        .andExpect(status().isForbidden());
    mvc.perform(put("/api/v1/me/username").header("Authorization","Bearer "+token)
        .contentType("application/json").content(write(map("username","u"+userId.substring(5,20)))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.hasPassword").value(false))
        .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    mvc.perform(post("/api/v1/auth/password-reset").contentType("application/json")
        .content(write(reset("password_reset",password)))).andExpect(status().isOk());
    mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
  }
}
