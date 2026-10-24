package com.warmpaw.common;
import java.util.Objects;
import static com.warmpaw.common.ApiException.require;
/** 跨服务复用的环境读取与站内跳转校验，不包含支付业务。 */
public final class ProviderSupport {
  private ProviderSupport() {}
  /**
   * 校验"只允许跳回站内"的返回路径，防开放重定向。
   *
   * <p>微信网页授权、支付完成后都要带一个 {@code redirect} 回跳，这个值来自请求参数。
   * 如果允许 {@code https://evil.com}，攻击者就能构造一条<b>看起来是本店域名</b>的授权链接，
   * 用户点进去、授权完被弹到钓鱼站——所以必须只允许相对路径。
   *
   * <p>拒绝项及原因：
   *
   * <ul>
   *   <li>不以 {@code /} 开头 —— 可能被浏览器当成绝对 URL（{@code evil.com/x} 是相对路径，
   *       但 {@code //evil.com} 是协议相对 URL，所以下一条单独拦）；
   *   <li>{@code //} 开头 —— 协议相对 URL，浏览器会补上 {@code https:} 跳外站；
   *   <li>反斜杠 —— 部分浏览器把 {@code \\evil.com} 等价于 {@code //evil.com}；
   *   <li>CR/LF —— 响应头注入；
   *   <li>{@code %2f}/{@code %5c}（大小写都查）—— 编码后再解码即可绕过前两条，
   *       所以直接禁止编码形式的斜杠出现在路径里。
   * </ul>
   */
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

  /**
   * 读环境变量，<b>永不为 null</b>：未设置时返回空串。
   *
   * <p>统一用空串而不是 null，是为了让所有配置判断都能写成 {@code isBlank()}——
   * 不用区分"没配"和"配了个空值"，也不用在每个调用点判空。
   *
   * <p>注意它<b>只读环境变量</b>，不读 {@code application.yml}、不读 {@code -D} 系统属性：
   * 密钥类配置刻意只从环境注入，避免被打包进镜像或提交到仓库。
   * 这也意味着本地调试必须 {@code export}，写进配置文件是无效的。
   */
  public static String env(String key) {
    return Objects.toString(System.getenv(key), "");
  }
}
