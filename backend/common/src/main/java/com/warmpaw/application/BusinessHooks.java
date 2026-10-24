package com.warmpaw.application;
import java.util.Map;
/**
 * 各服务的事务结果扩展；默认保存原响应，交易服务提供状态刷新和提交后支付处理。
 *
 * <p><b>存在意义</b>：{@link BusinessOperationExecutor} 在 common 模块里，不能依赖
 * 订单/支付这些只存在于 order-server 的领域逻辑；于是把"两处差异化行为"抽成接口，
 * 由各服务注入实现。admin-server 提供一个全默认的空实现（{@code @Bean} 里 new 一个匿名类），
 * order-server 提供 {@code OrderBusinessHooks}。
 *
 * <p>两个方法都是 {@code default}，所以实现类<b>只覆盖它关心的那个</b>。
 *
 * <p><b>部署纪律</b>：涉及外部副作用的实现（调支付平台、发短信）只能放在 order-server。
 * 若两个服务都实现，同一笔业务会被执行两次。
 */
public interface BusinessHooks {
  /**
   * 幂等重放时如何构造这次响应。默认直接返回首次保存的 {@code data}。
   *
   * <p>默认实现适用于"结果不会随时间变化"的操作（创建类、终态类）。需要"看到最新状态"
   * 的操作（订单详情、支付单、售后进度）应覆盖它：按 {@code kind}/{@code resource}
   * 重新查库再拼视图，而不是把旧 JSON 原样吐回去。
   *
   * @param saved  首次执行时落库的 JSON 反序列化结果，形如
   *               {@code {data:..., kind:..., resource:...}}
   * @param path   本次请求路径，用于区分"同一资源的不同动作"（如取消 vs 交付）
   * @param admin  是否后台视角，决定重建视图时给哪些字段
   * @param status 首次响应的状态码，默认实现原样带回
   */
  default OperationResult replay(Map<String, Object> saved, String path, boolean admin, int status) {
    return new OperationResult(status, saved.get("data"));
  }
  /**
   * 事务<b>提交之后</b>的收尾钩子，默认原样返回。
   *
   * <p>调用点在 {@code ApiRequestExecutor}：它在事务代理方法返回后才调用本方法，
   * 所以此刻数据已落库，可以安全地做外部网络调用（发起支付、发通知）而不会把
   * 全局写锁拖住。反之，在业务方法里直接调用外部接口会导致<b>锁的持有时间 = 对方响应时间</b>。
   *
   * <p>副作用：钩子里抛异常会变成 500，但业务数据<b>已经提交</b>——调用方必须能接受
   * "请求失败但操作成功"的情形，这也是为什么支付走"先落库再异步推进"而不是同步等待。
   */
  default OperationResult afterCommit(OperationResult result, RequestContext context) { return result; }
}
