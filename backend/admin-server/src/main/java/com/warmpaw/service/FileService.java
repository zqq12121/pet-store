package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件落在独立存储根目录；私有附件仅用短时随机授权，原始文件名不参与路径拼接。
 *
 * <p><b>存储布局</b>：本地根目录（{@code app.storage}）下用系统生成的 {@code file_<32 hex>}
 * 作文件名，原始文件名只进数据库用于展示。于是既不会同名覆盖，也不存在 {@code ../} 穿越——
 * 用户可控的字符串永远不进文件路径（见 {@link #safeName}）。
 *
 * <p><b>类型判定靠内容，不靠声明</b>：扩展名与 {@code Content-Type} 都能伪造，这里按魔数嗅探
 * （{@link #detect}），再按用途做深度校验（图片真解码 + 像素上限、MP4 交给 ffprobe、
 * PDF 限页数并禁止主动内容）。所有校验都在本地副本上完成，通过之后才推给 OSS。
 *
 * <p><b>公开与私有</b>：{@code visibility} 由 {@code purpose} 推导，
 * {@code avatar/pet_image/banner/pet_video/quarantine_public} 为 public，其余 private。
 * 公开文件任何人可读；私有文件必须持 {@link #access} 签发的 5 分钟令牌——归属判断只在
 * 签发令牌时做，{@link #content} 本身不查所有者，所以"先给链接再判归属"是行不通的。
 * {@code quarantine_original}（检疫证明原件）与 {@code knowledge_import} 是<b>店长专属</b>，
 * 连上传者本人都换不到链接。
 *
 * <p><b>用途白名单按角色划分</b>（{@link #upload}）：店长传商品图、轮播、检疫材料；
 * 买家只能传头像、诊断材料、售后证据、社区图片。用途同时决定大小上限、允许的 MIME，
 * 以及是否需要绑定订单——订单类附件必须带 {@code orderId} 且订单属于本人，其余用途反而禁止带。
 *
 * <p>本类不改业务态（不写宠物、不写售后单），只回答两个问题："这个文件是谁的"、
 * "能不能给这个人看"。
 */
@Service
public class FileService {
  private final BusinessRepository store;
  private final TemporaryStore temp;
  private final AuthService auth;
  private final Path root;
  private final OssStorage oss;

  public FileService(
      BusinessRepository store,
      TemporaryStore temp,
      AuthService auth,
      OssStorage oss,
      @Value("${app.storage}") String root) {
    this.store = store;
    this.temp = temp;
    this.auth = auth;
    this.root = Path.of(root).toAbsolutePath().normalize();
    this.oss = oss;
  }

  /**
   * 访问令牌校验仍由 content 执行，Controller 只拿到已授权的下载描述。
   *
   * <p>返回的 {@code private} 标志是控制器的缓存策略依据：私有文件 {@code no-store}、
   * 公开文件缓存 1 小时。本方法<b>不做任何鉴权</b>，安全责任完全在 {@link #content}——
   * 先拿路径再判权限的顺序是错的，别在别处模仿。
   */
  public com.warmpaw.dto.FileDownload download(String fileId, String accessToken) {
    Path path = content(fileId, accessToken);
    Map<String, Object> file = store.get("file", fileId);
    return new com.warmpaw.dto.FileDownload(
        path, text(file, "mimeType"), "private".equals(text(file, "visibility")));
  }

  /**
   * 上传并登记一个文件，返回文件视图（不含任何路径）。
   *
   * <p><b>顺序有讲究</b>：用途白名单 → 限流（同一用户每分钟 30 次）→ 订单归属 → 大小 →
   * 落本地副本 → 嗅探类型 → 解码校验 → 推 OSS → 写 {@code file} 行。任何一步失败都要把
   * 已落盘、已上传的副本删掉，并且不留下 {@code ready} 记录——否则会出现"库里说有、取文件 404"。
   *
   * <p><b>大小上限按用途分档</b>：视频 100MB、头像 2MB、图片类（宠物图/轮播/社区图）5MB、
   * 其余（诊断/证据/PDF）10MB。先看 {@code file.getSize()} 再落盘，避免先写 100MB 再拒绝。
   *
   * <p><b>订单类附件是双向约束</b>：{@code diagnosis}/{@code after_sale_evidence} 必须带
   * {@code orderId} 且订单属于本人；其它用途反而<b>禁止</b>带 {@code orderId}——
   * 否则可以把私密附件挂到某个订单上，借订单归属扩大可见范围。
   *
   * <p><b>图片必须真解码</b>：只嗅探魔数挡不住解压炸弹（小文件解出巨图），所以限制总像素
   * ≤ 4000 万，并要求 {@code ImageIO} 能读出 {@code BufferedImage}。
   *
   * <p><b>PDF 的额外检查</b>：不超过 100 页、未加密，且字节流里不能出现 {@code /JavaScript}、
   * {@code /JS}、{@code /Launch}、{@code /EmbeddedFile}——这些能让阅读器执行动作或外连。
   *
   * <p>失败映射：尺寸/类型 413/415（客户端可修正），IO 或解码服务异常统一 503，
   * 不把底层异常信息透给调用方（可能含本地路径）。
   */
  @org.springframework.transaction.annotation.Transactional
  public Map<String, Object> upload(
      MultipartFile file, String purpose, String orderId, AuthService.Actor actor) {
    List<String> allowed =
        actor.role().equals("admin")
            ? List.of(
                "after_sale_evidence",
                "pet_image",
                "banner",
                "pet_video",
                "quarantine_public",
                "quarantine_original")
            : List.of("avatar", "diagnosis", "after_sale_evidence", "community_image");
    require(allowed.contains(purpose), 422, "FILE_PURPOSE_MISMATCH", "不允许上传此用途文件");
    temp.limit("upload:" + actor.id(), 30, 60);
    boolean orderFile = List.of("diagnosis", "after_sale_evidence").contains(purpose);
    if (orderFile) {
      require(orderId != null, 400, "VALIDATION_ERROR", "此附件必须关联订单");
      store.ownedOrder(orderId, actor);
    } else require(orderId == null, 400, "VALIDATION_ERROR", "此用途不接收订单ID");
    int limit =
        purpose.equals("pet_video")
            ? 100
            : purpose.equals("avatar")
                ? 2
                : List.of("pet_image", "banner", "community_image").contains(purpose) ? 5 : 10;
    require(
        !file.isEmpty() && file.getSize() <= limit * 1024L * 1024,
        413,
        "FILE_TOO_LARGE",
        "文件为空或超过大小限制");
    String id = id("file");
    Path target = root.resolve(id);
    boolean storedRemotely = false;
    try {
      Files.createDirectories(root);
      file.transferTo(target);
      String mime = detect(target);
      boolean image = mime.startsWith("image/");
      boolean pdf = mime.equals("application/pdf");
      require(
          purpose.equals("pet_video")
              ? mime.equals("video/mp4")
              : List.of("diagnosis", "after_sale_evidence", "quarantine_original").contains(purpose)
                  ? (pdf || mime.equals("image/jpeg") || mime.equals("image/png"))
                  : purpose.equals("quarantine_public")
                      ? (mime.equals("image/jpeg") || mime.equals("image/png"))
                      : image,
          415,
          "UNSUPPORTED_FILE_TYPE",
          "文件真实类型不符合用途");
      BufferedImage decoded = null;
      if (image) {
        try (var input = ImageIO.createImageInputStream(target.toFile())) {
          var readers = ImageIO.getImageReaders(input);
          require(readers.hasNext(), 415, "UNSUPPORTED_FILE_TYPE", "图片无法解码");
          var reader = readers.next();
          try {
            reader.setInput(input);
            require(
                (long) reader.getWidth(0) * reader.getHeight(0) <= 40000000,
                415,
                "UNSUPPORTED_FILE_TYPE",
                "图片像素数量过大");
            decoded = reader.read(0);
            require(decoded != null, 415, "UNSUPPORTED_FILE_TYPE", "图片无法解码");
          } finally {
            reader.dispose();
          }
        }
      }
      if (mime.equals("video/mp4")) probe(target, mime);
      if (pdf) {
        try (var document = org.apache.pdfbox.Loader.loadPDF(target.toFile())) {
          require(
              !document.isEncrypted()
                  && document.getNumberOfPages() > 0
                  && document.getNumberOfPages() <= 100,
              415,
              "UNSUPPORTED_FILE_TYPE",
              "PDF 必须可读取且不超过100页");
        }
        byte[] bytes = Files.readAllBytes(target);
        String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        require(
            text.contains("%%EOF")
                && !text.contains("/JavaScript")
                && !text.contains("/JS")
                && !text.contains("/Launch")
                && !text.contains("/EmbeddedFile"),
            415,
            "UNSUPPORTED_FILE_TYPE",
            "PDF 损坏或包含主动内容");
      }
      // 对外展示的图片缩到 1920 以内再存；诊断、售后、检疫等证据类文件保持原样。
      if (decoded != null
          && List.of("pet_image", "banner", "community_image", "avatar").contains(purpose))
        ImageShrinker.shrink(target, mime, decoded, ImageShrinker.MAX_EDGE);
      decoded = null;
      boolean pub =
          List.of("avatar", "pet_image", "banner", "pet_video", "quarantine_public")
              .contains(purpose);
      // 类型和内容校验通过后再上传；失败时不创建 ready 文件记录。
      oss.upload(id, target, mime);
      storedRemotely = true;
      Map<String, Object> asset =
          store.create(
              "file",
              actor.id(),
              null,
              map(
                  "id",
                  id,
                  "orderId",
                  orderId,
                  "originalName",
                  safeName(file.getOriginalFilename()),
                  "mimeType",
                  mime,
                  "sizeBytes",
                  Files.size(target),
                  "purpose",
                  purpose,
                  "visibility",
                  pub ? "public" : "private",
                  "status",
                  "ready",
                  "publicUrl",
                  pub ? "/api/v1/media/" + id : null));
      return dto(asset);
    } catch (ApiException e) {
      if (storedRemotely) deleteRemote(id);
      delete(target);
      throw e;
    } catch (Exception e) {
      if (storedRemotely) deleteRemote(id);
      delete(target);
      throw new ApiException(503, "SERVICE_UNAVAILABLE", "文件存储或解码服务不可用");
    }
  }

  /**
   * 原始文件名清洗，只用于展示与下载时的建议文件名，<b>不参与路径拼接</b>。
   *
   * <p>三步：反斜杠统一成正斜杠后取最后一段（同时挡 Windows 路径与 {@code ../}）、
   * 去掉控制字符（防响应头注入与日志污染）、截断到 200 字符。{@code null} 回落成 {@code "file"}，
   * 但<b>空串会原样保留</b>，所以这个字段不能假定一定非空。
   */
  private String safeName(String name) {
    String clean = Objects.toString(name, "file").replace('\\', '/');
    clean = clean.substring(clean.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
    return clean.substring(0, Math.min(clean.length(), 200));
  }

  /**
   * 按魔数嗅探真实类型，只认五种：jpeg / png / webp / mp4 / pdf。
   *
   * <p>读前 16 字节即可判定，不做全文件扫描。识别不出来就 415，不做"猜一个类型"的兜底——
   * 猜错会让后续所有按类型做的校验全部落空。
   *
   * <p>顺带说明一个边角行为：{@code RIFF} 分支里直接 {@code substring(8)}，
   * 若文件只有 4~7 字节、恰好以 "RIFF" 开头，会抛越界异常并被上层归为 503 而不是 415。
   * 不影响安全性（仍然不会入库），只是错误码不够精确。
   */
  private String detect(Path file) throws Exception {
    byte[] bytes;
    try (var in = Files.newInputStream(file)) {
      bytes = in.readNBytes(16);
    }
    if (bytes.length >= 3
        && (bytes[0] & 255) == 255
        && (bytes[1] & 255) == 216
        && (bytes[2] & 255) == 255) return "image/jpeg";
    if (bytes.length >= 8
        && Arrays.equals(
            Arrays.copyOf(bytes, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
      return "image/png";
    String s = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    if (s.startsWith("RIFF") && s.substring(8).startsWith("WEBP")) return "image/webp";
    if (s.startsWith("%PDF-")) return "application/pdf";
    if (s.length() >= 12 && s.substring(4, 8).equals("ftyp")) return "video/mp4";
    throw new ApiException(415, "UNSUPPORTED_FILE_TYPE", "不支持或伪装的文件类型");
  }

  /**
   * 用外部 {@code ffprobe} 校验视频：必须可解码、含视频流、时长 ≤ 60 秒。
   *
   * <p>为什么不用纯 Java 解析：MP4 容器与编解码的坑太多，交给 ffprobe 更可靠。代价是部署必须
   * 提供该二进制（环境变量 {@code PAW_FFPROBE}），缺失时返回 503 而不是放行——
   * 宁可暂时不能传视频，也不能把未经校验的文件收进来。
   *
   * <p>限时 10 秒并强杀：畸形文件可能让 ffprobe 长时间卡住，不能让它占着请求线程。
   * 输出走管道读 JSON，stderr 丢弃（里面可能含服务器本地路径）。
   */
  private void probe(Path file, String mime) throws Exception {
    String binary = com.warmpaw.common.ProviderSupport.env("PAW_FFPROBE");
    require(!binary.isBlank(), 503, "SERVICE_UNAVAILABLE", "视频解码需配置 PAW_FFPROBE");
    Process p =
        new ProcessBuilder(
                binary,
                "-v",
                "error",
                "-show_entries",
                "format=duration:stream=codec_type",
                "-of",
                "json",
                file.toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    boolean ended = p.waitFor(10, TimeUnit.SECONDS);
    if (!ended) p.destroyForcibly();
    require(ended && p.exitValue() == 0, 415, "UNSUPPORTED_FILE_TYPE", "媒体无法解码");
    Map<String, Object> info =
        read(
            new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    require(
        objects(info, "streams").stream().anyMatch(s -> "video".equals(text(s, "codec_type"))),
        415,
        "UNSUPPORTED_FILE_TYPE",
        "缺少有效图像");
    if (mime.equals("video/mp4")) {
      double duration = Double.parseDouble(text(object(info, "format"), "duration"));
      require(duration > 0 && duration <= 60, 415, "UNSUPPORTED_FILE_TYPE", "视频不得超过60秒");
    }
  }

  /**
   * 尽力删除本地副本。
   *
   * <p>吞掉异常是有意的：这里通常运行在"已经有一个更重要的错误"的回滚路径上，
   * 删除失败不该覆盖原始错误码（否则用户会看到 503 而不知道真正原因是"文件太大"）。
   * 残留文件由 {@link MaintenanceService} 兜底清理。
   */
  private void delete(Path p) {
    try {
      Files.deleteIfExists(p);
    } catch (Exception ignored) {
      /* 不掩盖原始校验失败；孤立文件由后续清理处理。 */
    }
  }

  /** 同上，远端删除失败只吞不抛：留一个孤儿对象，好过让调用方看到一个假的成功。 */
  private void deleteRemote(String id) {
    try {
      oss.delete(id);
    } catch (Exception ignored) {
      /* 不掩盖原始上传错误；保留对象供存储核对，不伪造成功记录。 */
    }
  }

  /** 文件视图：白名单投影，不含本地路径、{@code ownerId} 与订单号。 */
  public Map<String, Object> dto(Map<String, Object> f) {
    return CatalogService.select(
        f, "id,originalName,mimeType,sizeBytes,purpose,visibility,status,publicUrl,createdAt");
  }

  /**
   * 换取一次性访问授权（5 分钟）。
   *
   * <p>放行条件（满足任一）：
   *
   * <ul>
   *   <li>店长——但这是"能看"，不是"能改"；
   *   <li>公开文件——任何已登录用户。未登录直接 401，公开文件也要求先登录才能走这个接口；
   *   <li>文件属于当前用户，或文件挂在某订单上而当前用户正是该订单的买家。
   * </ul>
   *
   * <p>两个例外始终只给店长：{@code quarantine_original}（检疫证明原件，可能含卖家个人信息）
   * 与 {@code knowledge_import}。判断写成"用途不在黑名单"再 {@code &&} 归属条件，
   * 相当于把归属这一整支短路掉，所以连上传者本人都换不到链接——文件归属不等于材料可外传。
   *
   * <p>令牌是 32 字节随机串，临时存储里以 {@code file-link:hash(token)} → 文件 id 的形式存
   * 300 秒。<b>令牌与具体文件 id 绑定</b>，不能拿 A 的令牌去读 B。每次签发都写审计，
   * 便于追查"谁在什么时候看了哪份材料"。
   */
  public Map<String, Object> access(String id, AuthService.Actor actor) {
    require(actor != null, 401, "UNAUTHORIZED", "请先登录");
    Map<String, Object> f = store.get("file", id);
    boolean admin = actor.role().equals("admin"),
        publicFile = "public".equals(text(f, "visibility"));
    boolean allowed =
        admin
            || (!List.of("quarantine_original", "knowledge_import").contains(text(f, "purpose"))
                && (actor.id().equals(text(f, "ownerId"))
                    || f.get("orderId") != null
                        && actor
                            .id()
                            .equals(text(store.get("order", text(f, "orderId")), "ownerId"))));
    require(allowed || publicFile, 404, "RESOURCE_NOT_FOUND", "文件不存在或不可访问");
    String token = auth.randomToken();
    temp.put("file-link:" + hash(token), id, 300);
    store.audit(actor.id(), "file.access", id);
    return map(
        "fileId",
        id,
        "url",
        publicFile ? f.get("publicUrl") : "/api/v1/media/" + id + "?access=" + token,
        "expiresAt",
        Instant.now().plusSeconds(300).toString());
  }

  /**
   * 真正取文件：校验可见性 → 定位本地路径 → 从 OSS 补缓存 → 再确认路径没被带出根目录。
   *
   * <p>三条放行路径：{@code visibility=public}；社区图片且被某条<b>已发布</b>内容引用；
   * 或携带有效令牌。中间那条是动态判断而不是看文件自身状态——社区图片上传时是私有的，
   * 只有内容真正发出去才转为可匿名浏览，内容被隐藏或删除后自动收回，不用回头改文件。
   *
   * <p>{@code ready} 是硬前置：未完成或已被清理的记录一律 404，不能靠"文件还在磁盘上"绕过。
   *
   * <p>{@code p.getParent().equals(root)} 出现两次：第一次在拼出路径后挡 {@code ..}，
   * 第二次在取回缓存后同时要求父目录仍是根、且确实是常规文件——缓存写入失败或路径被换掉时，
   * 宁可 404 也不要把一个来路不明的路径交给控制器去读。
   *
   * <p>顺序上必须<b>先鉴权再 restoreCache</b>：缓存会在本地根目录落一份明文副本，
   * 先拉后判权限等于给了未授权者一个读本地副本的机会。
   */
  public Path content(String id, String token) {
    Map<String, Object> f = store.get("file", id);
    require("ready".equals(text(f, "status")), 404, "RESOURCE_NOT_FOUND", "文件不可访问");
    // 社区草稿图片仅本人可授权预览；关联公开内容后可浏览，隐藏或删除后撤销公开读取。
    boolean communityPublic = "community_image".equals(text(f, "purpose"))
        && store.list("community_content").stream().anyMatch(c ->
            "published".equals(text(c, "status")) && strings(c, "imageFileIds").contains(id));
    require(
        "public".equals(text(f, "visibility"))
            || communityPublic
            || token != null && id.equals(temp.get("file-link:" + hash(token))),
        404,
        "RESOURCE_NOT_FOUND",
        "文件不可访问");
    Path p = root.resolve(id).normalize();
    require(p.getParent().equals(root), 404, "RESOURCE_NOT_FOUND", "文件不存在");
    try {
      // 必须在上方权限校验之后读取 OSS，私有材料不生成公开存储链接。
      oss.restoreCache(id, p);
    } catch (Exception e) {
      throw new ApiException(503, "SERVICE_UNAVAILABLE", "文件存储暂不可用");
    }
    require(
        p.getParent().equals(root) && Files.isRegularFile(p), 404, "RESOURCE_NOT_FOUND", "文件不存在");
    return p;
  }
}
