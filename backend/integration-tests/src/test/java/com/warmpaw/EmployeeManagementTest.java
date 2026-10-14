package com.warmpaw;

import static com.warmpaw.common.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.warmpaw.application.*;
import com.warmpaw.common.ApiException;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 独立 H2 验证员工权限、真实密码登录、撤销会话和请求竞态，不修改运行库。 */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:employee_management;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "spring.sql.init.mode=always", "app.mock-providers=true", "app.redis-enabled=false",
    "app.admin-password=EmployeeTest2026!", "app.storage=./target/employee-files"})
@ActiveProfiles("test")
class EmployeeManagementTest {
  @Autowired AuthService auth;
  @Autowired EmployeeService employees;
  @Autowired BusinessRepository store;
  @Autowired BusinessOperationExecutor operations;
  @Autowired PlatformTransactionManager manager;
  @Autowired org.springframework.web.context.WebApplicationContext context;
  MockMvc mvc;
  String username, staffToken, managerToken;
  Map<String,Object> employee;
  AuthService.Actor owner, staff;
  final String password="EmployeeTest2026!", replacement="EmployeeReset2026!";

  @BeforeEach void fixture() {
    username="e"+UUID.randomUUID().toString().replace("-", "").substring(0,20);
    managerToken=new TransactionTemplate(manager).execute(tx->{
      store.lock();
      var account=store.create("admin",null,"m"+username,
          map("username","m"+username,"passwordHash",auth.passwordHash(password)));
      return text(auth.login(account,"admin"),"accessToken");
    });
    owner=auth.identify("Bearer "+managerToken);
    employee=employees.create(map("username",username,"displayName","测试接待员工",
        "phone",null,"password",password),owner);
    staffToken=text(auth.adminLogin(map("username",username,"password",password),username),"accessToken");
    staff=auth.identify("Bearer "+staffToken);
    mvc=MockMvcBuilders.webAppContextSetup(context).build();
  }

  Map<String,Object> version() {return map("version",employee.get("version"));}
  String id(){return text(employee,"id");}

  @Test void managementLoginNeedsNoCaptchaAndServerDecidesRole() throws Exception {
    mvc.perform(post("/api/v1/admin/auth/login").contentType("application/json")
        .content(write(map("username",username,"password",password))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.admin.role").value("staff"))
        .andExpect(jsonPath("$.data.expiresIn").value(1800))
        .andExpect(jsonPath("$.data.admin.passwordHash").doesNotExist());
    assertEquals("admin",object(auth.adminLogin(map("username","m"+username,"password",password),username),"admin").get("role"));
    assertEquals(401,assertThrows(ApiException.class,()->auth.adminLogin(map("username",username,"password","wrong"),username)).status);
    assertEquals(400,assertThrows(ApiException.class,()->auth.adminLogin(map("username",username,"password",password,"role","admin"),username)).status);
  }

  @Test void employeeProfilesAreSafeAndUpdateUsesVersion() throws Exception {
    mvc.perform(get("/api/v1/admin/employees").param("keyword",username).header("Authorization","Bearer "+managerToken))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].passwordHash").doesNotExist());
    var body=map("version",employee.get("version"),"displayName","新的员工姓名","phone","13900000000");
    employee=employees.update(id(),body,owner);
    assertEquals("新的员工姓名",employee.get("displayName"));
    assertEquals("VERSION_CONFLICT",assertThrows(ApiException.class,()->employees.update(id(),body,owner)).code);
    assertEquals("USERNAME_TAKEN",assertThrows(ApiException.class,()->employees.create(
        map("username",username,"displayName","重复","password",password),owner)).code);
    assertFalse(write(employee).contains("passwordHash"));
    assertNotEquals(password,store.get("admin",id()).get("passwordHash"));
  }

  @Test void employeeCannotEscalateRoleOrModifyManager() {
    assertEquals(403,assertThrows(ApiException.class,()->employees.list(Map.of(),staff)).status);
    assertEquals(403,assertThrows(ApiException.class,()->employees.status(id(),map("status","disabled","version",1),staff)).status);
    assertEquals(400,assertThrows(ApiException.class,()->employees.create(
        map("username",username+"x","displayName","测试","password",password,"role","admin"),owner)).status);
    assertEquals(404,assertThrows(ApiException.class,()->employees.status(owner.id(),map("status","disabled","version",1),owner)).status);
    assertEquals(404,assertThrows(ApiException.class,()->employees.resetPassword(owner.id(),map("newPassword",replacement,"version",1),owner)).status);
  }

  @Test void receptionHttpAccessIsLimitedToReadsAndDelivery() throws Exception {
    for(String path:List.of("/admin/orders","/admin/pets","/admin/auth/security"))
      mvc.perform(get("/api/v1"+path).header("Authorization","Bearer "+staffToken)).andExpect(status().isOk());
    for(String path:List.of("/admin/employees","/admin/dashboard","/admin/shop","/admin/agreements",
        "/admin/after-sales","/admin/community/contents","/admin/auth/ai-identity")) {
      mvc.perform(get("/api/v1"+path).header("Authorization","Bearer "+staffToken)).andExpect(status().isForbidden());
    }
    for(String path:List.of("/admin/pets","/admin/pets/pet_missing/publish","/admin/orders/order_missing/appointment/confirm",
        "/admin/orders/order_missing/appointment/cancel","/admin/after-sales/sale_missing/review","/admin/refunds/refund_missing/retry"))
      mvc.perform(post("/api/v1"+path).header("Authorization","Bearer "+staffToken)
          .contentType("application/json").content("{}"))
          .andExpect(status().isForbidden());
    mvc.perform(put("/api/v1/admin/shop").header("Authorization","Bearer "+staffToken)
        .contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/admin/orders")).andExpect(status().isUnauthorized());
  }

  @Test void disablingAndReenablingNeverRestoresOldSessions() {
    employee=employees.status(id(),map("status","disabled","version",employee.get("version")),owner);
    assertEquals(401,assertThrows(ApiException.class,()->auth.identify("Bearer "+staffToken)).status);
    assertEquals("账号或密码不正确",assertThrows(ApiException.class,()->auth.adminLogin(
        map("username",username,"password",password),username)).getMessage());
    employee=employees.status(id(),map("status","active","version",employee.get("version")),owner);
    assertThrows(ApiException.class,()->auth.identify("Bearer "+staffToken));
    assertNotNull(auth.adminLogin(map("username",username,"password",password),username).get("accessToken"));
  }

  @Test void resetPasswordRevokesEverySessionAndKeepsCooldown() {
    String second=text(auth.adminLogin(map("username",username,"password",password),username),"accessToken");
    employee=employees.resetPassword(id(),map("newPassword",replacement,"version",employee.get("version")),owner);
    for(String token:List.of(staffToken,second))assertThrows(ApiException.class,()->auth.identify("Bearer "+token));
    assertThrows(ApiException.class,()->auth.adminLogin(map("username",username,"password",password),username));
    assertNotNull(auth.adminLogin(map("username",username,"password",replacement),username).get("accessToken"));
    assertNotNull(employee.get("passwordChangeAvailableAt"));
    assertEquals("CHANGE_COOLDOWN",assertThrows(ApiException.class,()->employees.resetPassword(
        id(),map("newPassword",password,"version",employee.get("version")),owner)).code);
  }

  @Test void employeeCanChangeOwnPasswordAndLogout() throws Exception {
    mvc.perform(put("/api/v1/admin/auth/password").header("Authorization","Bearer "+staffToken)
        .contentType("application/json").content(write(map("oldPassword",password,"newPassword",replacement))))
        .andExpect(status().isOk());
    assertThrows(ApiException.class,()->auth.identify("Bearer "+staffToken));
    String next=text(auth.adminLogin(map("username",username,"password",replacement),username),"accessToken");
    mvc.perform(post("/api/v1/admin/auth/logout").header("Authorization","Bearer "+next)).andExpect(status().isOk());
    assertThrows(ApiException.class,()->auth.identify("Bearer "+next));
  }

  @Test void blockedWriteRechecksCapturedIdentityAfterDisable() {
    employees.status(id(),map("status","disabled","version",employee.get("version")),owner);
    // 模拟写请求认证后等待写锁，拿锁时账号已被店长停用。
    var request=new RequestContext("POST","/admin/orders/order_missing/pickup",UUID.randomUUID().toString(),staff,"test");
    assertEquals(401,assertThrows(ApiException.class,()->operations.execute(request,Map.of(),AccessLevel.STAFF,true,
        ()->{fail("停用员工不应执行业务");return null;})).status);
  }

  @Test void loginFailureLockoutStillWorksWithoutCaptcha() {
    for(int i=0;i<5;i++)assertEquals(401,assertThrows(ApiException.class,()->auth.adminLogin(
        map("username",username,"password","wrong"),username)).status);
    assertEquals(429,assertThrows(ApiException.class,()->auth.adminLogin(
        map("username",username,"password",password),username)).status);
  }
}
