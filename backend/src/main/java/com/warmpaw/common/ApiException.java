package com.warmpaw.common;

/** 业务错误只向客户端暴露明确的错误码和可理解的信息。 */
public class ApiException extends RuntimeException {
  public final int status;
  public final String code;

  public ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static void require(boolean condition, int status, String code, String message) {
    if (!condition) throw new ApiException(status, code, message);
  }
}
