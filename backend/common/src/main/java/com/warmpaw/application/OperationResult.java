package com.warmpaw.application;

/**
 * 业务执行结果。资源标识用于幂等重放，HTTP 层负责包装统一响应。
 *
 * @param status    业务状态码，HTTP 层原样用作响应状态（202 表示"已受理、异步推进中"）
 * @param data      响应体内容，由 HTTP 层塞进统一 envelope 的 {@code data}
 * @param kind      受影响实体的类型（如 {@code order}/{@code payment}），供 afterCommit
 *                  判断该做什么、供重放时重新查实体
 * @param resource  受影响实体的 id，与 {@code kind} 一起构成"这条结果对应谁"
 */
public record OperationResult(int status, Object data, String kind, String resource) {
  /**
   * 不关心幂等重放时的便捷构造：{@code kind}/{@code resource} 都为 null。
   *
   * <p>用它的接口在重放时会退化成"返回首次保存的 data"（见 {@code BusinessHooks.replay}
   * 的默认实现），因此不会出现状态刷新——对纯查询式或终态操作是够用的。
   */
  public OperationResult(int status, Object data) {
    this(status, data, null, null);
  }
}
