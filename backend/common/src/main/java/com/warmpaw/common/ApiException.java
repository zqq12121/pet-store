package com.warmpaw.common;

/**
 * 业务错误只向客户端暴露明确的错误码和可理解的信息。
 *
 * <p><b>字段语义</b>：{@link #status} 是 HTTP 状态码（401/403/404/409/422/429/502/503），
 * {@link #code} 是稳定的业务码（如 {@code ORDER_STATE_CONFLICT}），{@code getMessage()}
 * 是直接给用户看的中文文案。三者由异常处理器原样写进响应体，
 * 所以 <b>message 里绝不能放 SQL、堆栈、内部 id 或路径</b>。
 *
 * <p><b>为什么继承 {@link RuntimeException}</b>：
 *
 * <ul>
 *   <li>业务代码不必层层声明 throws，校验可以写在任意深度；
 *   <li>受检异常默认<b>不</b>触发事务回滚，而 <em>RuntimeException 会</em>——
 *       业务失败时撤销已写入的部分正是我们要的语义。
 * </ul>
 *
 * <p>调用点几乎总是通过 {@link #require} 而不是 {@code new}：让"条件 + 错误"写在一行，
 * 避免出现只有 {@code if} 没有 {@code throw} 的分支。
 */
public class ApiException extends RuntimeException {
  /** HTTP 状态码，同时决定响应头 {@code Retry-After} 是否生效（仅 429）。 */
  public final int status;
  /** 业务错误码，前端按它做分支判断；一旦发布就要保持稳定。 */
  public final String code;

  public ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  /**
   * 断言式抛错：条件不成立才抛。全项目最主要的前置条件写法。
   *
   * <p>与 {@code assert} 的区别：它不受 {@code -ea} 开关影响，永远生效——
   * 这里的校验是业务与安全的一部分，不能在生产环境被关掉。
   *
   * <p>注意参数求值顺序：{@code message} 会被立即求值（例如拼接了用户输入），
   * 即使条件成立也照样构造一次。热路径里如果拼消息很贵，应改为先判断再抛。
   */
  public static void require(boolean condition, int status, String code, String message) {
    if (!condition) throw new ApiException(status, code, message);
  }
}
