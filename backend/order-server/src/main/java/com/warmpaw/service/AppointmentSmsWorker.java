package com.warmpaw.service;

import static com.warmpaw.common.Json.map;

import com.warmpaw.common.ApiException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 预约短信的发送执行者。
 *
 * <p>两个触发源：
 *
 * <ul>
 *   <li>{@link AppointmentSmsNotifier} 在事务提交后立即调 {@link #dispatch}——正常情况下用户几乎无感；
 *   <li>{@link #retryDue} 每 30 秒扫描一次，兜住"即时发送失败""进程崩溃""实例重启"这些情况。
 * </ul>
 *
 * <p><b>日志安全</b>：失败日志只写 {@code orderId}/{@code event}/{@code attempt}/{@code code}，
 * <b>不写手机号和模板参数</b>。手机号属于个人信息，错误码已足够定位问题。
 *
 * <p><b>本地模式</b>：{@code auth.local()} 时不调真实短信平台，而是把内容写进本地开发收件箱
 * （{@code data/local-inbox}），仍走完整的认领/回写流程。
 * 这样本地开发也能验证"重试、去重、状态机"是否正确，而不是跳过这些逻辑。
 */
@Component
public class AppointmentSmsWorker {
  private static final Logger log = LoggerFactory.getLogger(AppointmentSmsWorker.class);

  /** 调度锁任务名；多实例必须一致。 */
  private static final String TASK_RETRY = "order:sms-retry";

  /** 锁存活 25 秒：略小于 30 秒的调度间隔。 */
  private static final java.time.Duration LOCK_TTL = java.time.Duration.ofSeconds(25);

  private final AuthService auth;
  private final SmsGateway gateway;
  private final AppointmentSmsOutbox outbox;
  /** 跨实例互斥：避免同一笔预约短信被两个实例各发一次。 */
  private final SchedulerLock schedulerLock;

  public AppointmentSmsWorker(
      AuthService auth,
      SmsGateway gateway,
      AppointmentSmsOutbox outbox,
      SchedulerLock schedulerLock) {
    this.auth = auth;
    this.gateway = gateway;
    this.outbox = outbox;
    this.schedulerLock = schedulerLock;
  }

  /**
   * 处理一条短信任务。
   *
   * <p>先 {@code claim} 抢租约：抢不到说明别的实例正在发或已达上限，直接返回（不是错误）。
   *
   * <p>成功与失败都回写，但方式不同：
   *
   * <ul>
   *   <li>成功 → {@code sent}，并带上本次的 {@code attempts} 做乐观校验；
   *   <li>失败 → {@code retry}，把异常里的业务错误码写进 {@code last_error}，
   *       非 {@link ApiException} 统一记 {@code INTERNAL_ERROR}。
   * </ul>
   *
   * <p>这里捕获 {@link RuntimeException} 而不是只捕获 {@link ApiException}：
   * 短信 SDK 可能抛任何运行时异常，一条坏数据不该让整轮扫描中断。
   */
  public void dispatch(String key) {
    Instant now = Instant.now();
    AppointmentSmsOutbox.Notice notice = outbox.claim(key, now);
    if (notice == null) return;
    try {
      if (auth.local()) {
        auth.localAppointmentNotice(
            notice.key(),
            map(
                "status",
                "simulated",
                "phone",
                notice.phone(),
                "event",
                notice.event(),
                "parameters",
                notice.parameters()));
      } else {
        gateway.sendAppointment(notice.phone(), notice.event(), notice.parameters());
      }
      // attempts 随每次认领递增，旧 Worker 不能覆盖新租约的结果。
      outbox.sent(notice.key(), notice.attempts(), Instant.now());
    } catch (RuntimeException e) {
      String code = e instanceof ApiException api ? api.code : "INTERNAL_ERROR";
      outbox.retry(notice.key(), notice.attempts(), Instant.now(), code);
      // 不记录手机号、模板参数和平台密钥，只保留订单事件及稳定错误码。
      log.error(
          "预约短信发送失败：orderId={}, event={}, attempt={}, code={}",
          notice.orderId(),
          notice.event(),
          notice.attempts(),
          code);
    }
  }

  /**
   * 每 30 秒补发一次到期任务。
   *
   * <p>{@code initialDelay} 同样 30 秒，避免应用刚启动、数据库连接池还没热就批量发送。
   * 单轮最多处理 {@link AppointmentSmsOutbox#due} 返回的 20 条，防止积压时占满线程。
   *
   * <p>外面套一层 {@link SchedulerLock}：{@code outbox.due} 本身用条件更新做了认领，
   * 多实例并发也不会真的重复发同一条；加锁的意义是避免多个实例同时扫表做无用功，
   * 也避免同一批积压被 N 个实例分摊时反复争抢。
   */
  @Scheduled(fixedDelay = 30000, initialDelay = 30000)
  public void retryDue() {
    if (!schedulerLock.tryAcquire(TASK_RETRY, LOCK_TTL)) return;
    try {
      for (String key : outbox.due(Instant.now())) dispatch(key);
    } finally {
      schedulerLock.release(TASK_RETRY);
    }
  }
}
