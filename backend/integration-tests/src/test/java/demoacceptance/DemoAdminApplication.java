package demoacceptance;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.boot.admin.AdminServerApplication;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.AuthService;
import com.warmpaw.service.GraphCaptchaService;
import com.warmpaw.service.TemporaryStore;
import java.util.Map;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 仅打包到隔离验收辅助 JAR，生产镜像与正常测试扫描均不加载此入口。 */
@Configuration
public class DemoAdminApplication {
  public static void main(String[] args) {
    // 必须由隔离验收脚本显式启用，禁止携带真实短信或 OSS 配置运行。
    if (!"true".equals(System.getenv("PAW_DEMO_ACCEPTANCE"))
        || !"true".equals(System.getenv("PAW_MOCK_PROVIDERS"))
        || !"false".equals(System.getenv("PAW_OSS_ENABLED"))) {
      throw new IllegalStateException("仅允许隔离的模拟验收环境启动");
    }
    SpringApplication.run(new Class<?>[] {AdminServerApplication.class, DemoAdminApplication.class}, args);
  }

  @Bean
  @Primary
  GraphCaptchaService demoGraphCaptcha(TemporaryStore temp) {
    return new GraphCaptchaService(temp) {
      @Override
      public Map<String, Object> config() {
        return map("captchaId", "demo-acceptance-only");
      }

      @Override
      public void verify(Map<String, Object> body) {
        String lot = text(body, "lot_number");
        require(lot.startsWith("demo-") && lot.length() <= 128
            && "demo-only".equals(text(body, "captcha_output"))
            && "demo-only".equals(text(body, "pass_token"))
            && text(body, "gen_time").matches("[0-9]{10}"),
            400, "CAPTCHA_INVALID", "仅接受隔离验收的模拟图形凭据");
        // 模拟凭据也只能消费一次，业务登录、密码校验和会话签发继续使用真实实现。
        temp.limit("demo-graph:" + hash(lot), 1, 600);
      }
    };
  }

  @Bean
  CommandLineRunner demoAccounts(BusinessRepository store, AuthService auth,
      PlatformTransactionManager manager) {
    return args -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
      store.lock();
      // 只修改隔离副本的管理员密码，保持宠物材料原有的归属 ID。
      Map<String, Object> admin = store.byKey("admin", "admin");
      admin.put("passwordHash", auth.passwordHash("DemoAdmin2026!"));
      store.save(admin);
      String phone = "13900000001";
      Map<String, Object> buyer = store.byKey("user", phone);
      if (buyer == null) {
        buyer = store.create("user", null, phone,
            map("phone", phone, "nickname", "模拟买家", "avatarUrl", null));
      }
      buyer.put("username", "demo_buyer");
      buyer.put("passwordHash", auth.passwordHash("DemoBuyer2026!"));
      store.save(buyer);
      // 新到店预约不开放在线售后；另建明确标记的历史订单夹具验证旧售后流程。
      if (store.byKey("order", "demo-legacy-after-sale") == null) {
        Map<String, Object> legacy = copy(store.list("order").stream()
            .filter(o -> !o.containsKey("appointment") && "completed".equals(text(o, "status")))
            .findFirst().orElseThrow(() -> new IllegalStateException("备份缺少历史订单演示模板")));
        legacy.remove("id");
        legacy.put("version", 1);
        legacy.put("orderNo", "DEMO-AFTERSALE-20261009");
        legacy.put("contactName", "模拟买家");
        legacy.put("contactPhone", phone);
        legacy.put("remark", "仅供售后流程验收的模拟历史订单，无真实收款");
        legacy.put("refundedAmount", 0L);
        legacy.put("pickedUpAt", Instant.now().minus(1, ChronoUnit.HOURS).toString());
        legacy.put("healthGuaranteeExpiresAt", Instant.now().plus(7, ChronoUnit.DAYS).toString());
        legacy.remove("paymentId");
        store.create("order", text(buyer, "id"), "demo-legacy-after-sale", legacy);
      }
    });
  }
}
