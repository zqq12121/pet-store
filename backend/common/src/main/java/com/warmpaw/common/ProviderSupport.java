package com.warmpaw.common;
import java.util.Objects;
import static com.warmpaw.common.ApiException.require;
/** 跨服务复用的环境读取与站内跳转校验，不包含支付业务。 */
public final class ProviderSupport {
  private ProviderSupport() {}
  public static void safePath(String path) {
    require(
        path.startsWith("/")
            && !path.startsWith("//")
            && !path.contains("\\")
            && !path.contains("\r")
            && !path.contains("\n")
            && !path.toLowerCase().contains("%2f")
            && !path.toLowerCase().contains("%5c"),
        400,
        "VALIDATION_ERROR",
        "只允许站内返回路径");
  }

  public static String env(String key) {
    return Objects.toString(System.getenv(key), "");
  }
}
