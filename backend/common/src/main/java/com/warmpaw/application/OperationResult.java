package com.warmpaw.application;

/** 业务执行结果。资源标识用于幂等重放，HTTP 层负责包装统一响应。 */
public record OperationResult(int status, Object data, String kind, String resource) {
  public OperationResult(int status, Object data) {
    this(status, data, null, null);
  }
}
