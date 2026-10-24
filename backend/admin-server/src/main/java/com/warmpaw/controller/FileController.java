package com.warmpaw.controller;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;
import static com.warmpaw.controller.support.ApiResponses.ok;

import com.warmpaw.service.AuthService;
import com.warmpaw.service.FileService;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传、授权访问和媒体响应接口（调用方：买家、店长、匿名图片加载）。
 *
 * <p>路由前缀 {@code /api/v1}，三条路径的鉴权强度各不相同：
 *
 * <ul>
 *   <li>{@code POST /files} —— 买家上传（头像、诊断、售后凭证、社区图片）；
 *   <li>{@code POST /admin/files} —— 店长上传（宠物图/视频、轮播、检疫材料）。同一个方法
 *       同时挂两个路径，靠 {@code getRequestURI().contains("/admin/")} 决定要求的角色，
 *       所以两个入口的用途白名单与权限是<b>不同</b>的；
 *   <li>{@code GET /files/{id}/access-url} —— 需要登录（Service 内断言 actor 非空），
 *       由所有权/订单归属决定能否换取 5 分钟有效的签名地址；
 *   <li>{@code GET /media/{id}} —— 面向 {@code <img>} 标签，允许匿名：public 文件与已被
 *       公开社区内容引用的图片可直接读取，其余必须带 {@code access} 签名。
 * </ul>
 *
 * <p>委托 {@link FileService}：上传限制、用途校验、私有文件访问控制都在那里，
 * 本类只负责 HTTP 语义（状态码、响应头、未知参数拦截）。
 */
@RestController
@RequestMapping("/api/v1")
public class FileController {
  private final AuthService auth;
  private final FileService files;

  public FileController(AuthService auth, FileService files) {
    this.auth = auth;
    this.files = files;
  }

  /**
   * 解析当前请求身份（可能为 {@code null}）。
   *
   * <p>匿名是合法状态：媒体读取接口允许未登录访问公开文件，因此这里不抛 401，
   * 是否强制登录交由下游 {@link FileService} 按文件可见性决定。
   */
  private AuthService.Actor actor(HttpServletRequest request) {
    return auth.identify(request.getHeader("Authorization"));
  }

  /**
   * 上传文件（multipart）。
   *
   * <p>POST {@code /files} 或 {@code /admin/files}，二选一由路径决定权限：
   * 前者 {@code role(a, "buyer")}，后者 {@code role(a, "admin")}——<b>员工不能上传任何文件</b>，
   * 因为两者都要求角色严格匹配。未登录或角色不符分别 401 / 403。
   *
   * <p>只接受 {@code file}、{@code purpose} 与可选的 {@code orderId} 三个参数，
   * 出现其它字段直接 400（防止客户端塞额外元数据覆盖服务端字段）。
   *
   * <p>语义约束（由 Service 执行）：用途必须在当前角色的白名单内（422
   * {@code FILE_PURPOSE_MISMATCH}）；{@code diagnosis}/{@code after_sale_evidence} 必须带
   * orderId 且订单属于本人，其余用途反过来不允许带 orderId；大小上限按用途区分
   * （视频 100MB、头像 2MB、宠物图/轮播/社区图 5MB、其余 10MB），超限 413。
   *
   * <p>非幂等：每次成功都生成新的 fileId，重复上传会产生多份文件与配额消耗
   * （同一用户每分钟最多 30 次）。
   *
   * @return 201 与文件元信息（含 id、purpose、visibility）
   */
  @PostMapping(
      value = {"/files", "/admin/files"},
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Object> upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam String purpose,
      @RequestParam(required = false) String orderId,
      HttpServletRequest req) {
    AuthService.Actor a = actor(req);
    AuthService.role(a, req.getRequestURI().contains("/admin/") ? "admin" : "buyer");
    require(
        req.getParameterMap().keySet().stream()
            .allMatch(k -> List.of("purpose", "orderId").contains(k)),
        400,
        "VALIDATION_ERROR",
        "存在未知上传字段");
    return ok(201, files.upload(file, purpose, orderId, a));
  }

  /**
   * 换取私有文件的临时访问地址。
   *
   * <p>GET {@code /files/{id}/access-url}。必须登录：Controller 允许 actor 为 null，
   * 但 Service 第一行就断言非空（401）。授权规则：店长可访问任意文件；其他角色只能访问
   * 自己上传的、或所属订单属于自己的文件，且检疫原件/知识库导入件对非店长一律不可见。
   * 无权限与不存在都返回 404，避免探测。
   *
   * <p>返回的 URL 内嵌一次性随机 token，有效 5 分钟，并写一条 {@code file.access} 审计。
   * 非幂等：每次调用生成新 token、新过期时间；public 文件则直接返回固定 publicUrl。
   *
   * @return 200 与 {@code fileId/url/expiresAt}
   */
  @GetMapping("/files/{id}/access-url")
  public ResponseEntity<Object> access(@PathVariable String id, HttpServletRequest req) {
    return ok(200, files.access(id, actor(req)));
  }

  /**
   * 媒体流读取（图片/视频/PDF 直出）。
   *
   * <p>GET {@code /media/{id}?access=token}。鉴权在 Service：public 文件与已被 published
   * 社区内容引用的图片允许匿名；其余必须携带由 access-url 换来的、未过期的签名 token，
   * 否则 404。Controller 的 {@code actor(req)} 只负责"带了无效令牌就报 401"，
   * 不要求登录——否则 {@code <img src>} 这类无法附加请求头的场景会全部失效。
   *
   * <p>响应头是安全边界的一部分：{@code X-Content-Type-Options: nosniff} 阻止浏览器猜类型，
   * {@code CSP: default-src 'none'; sandbox} 让直出的 SVG/HTML 无法执行脚本，
   * PDF 强制 {@code attachment} 下载。缓存策略按可见性区分：私有文件 {@code no-store}，
   * 公开文件缓存 1 小时。
   *
   * <p>幂等（只读）；文件未就绪或已删除返回 404。
   */
  @GetMapping("/media/{id}")
  public ResponseEntity<?> media(
      @PathVariable String id,
      @RequestParam(required = false) String access,
      HttpServletRequest req) {
    actor(req);
    var download = files.download(id, access);
    return ResponseEntity.ok()
        .cacheControl(
            download.privateFile()
                ? CacheControl.noStore()
                : CacheControl.maxAge(Duration.ofHours(1)))
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", "default-src 'none'; sandbox")
        .header(
            "Content-Disposition",
            download.mimeType().equals("application/pdf") ? "attachment" : "inline")
        .contentType(MediaType.parseMediaType(download.mimeType()))
        .body(new FileSystemResource(download.path()));
  }
}
