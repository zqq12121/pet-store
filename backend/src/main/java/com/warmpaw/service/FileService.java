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

/** 文件落在独立存储根目录；私有附件仅用短时随机授权，原始文件名不参与路径拼接。 */
@Service
public class FileService {
  private final BusinessRepository store;
  private final OrderService orders;
  private final TemporaryStore temp;
  private final AuthService auth;
  private final Path root;

  public FileService(
      BusinessRepository store,
      OrderService orders,
      TemporaryStore temp,
      AuthService auth,
      @Value("${app.storage}") String root) {
    this.store = store;
    this.orders = orders;
    this.temp = temp;
    this.auth = auth;
    this.root = Path.of(root).toAbsolutePath().normalize();
  }

  /** 访问令牌校验仍由 content 执行，Controller 只拿到已授权的下载描述。 */
  public com.warmpaw.dto.FileDownload download(String fileId, String accessToken) {
    Path path = content(fileId, accessToken);
    Map<String, Object> file = store.get("file", fileId);
    return new com.warmpaw.dto.FileDownload(
        path, text(file, "mimeType"), "private".equals(text(file, "visibility")));
  }

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
            : List.of("avatar", "diagnosis", "after_sale_evidence");
    require(allowed.contains(purpose), 422, "FILE_PURPOSE_MISMATCH", "不允许上传此用途文件");
    temp.limit("upload:" + actor.id(), 30, 60);
    boolean orderFile = List.of("diagnosis", "after_sale_evidence").contains(purpose);
    if (orderFile) {
      require(orderId != null, 400, "VALIDATION_ERROR", "此附件必须关联订单");
      orders.owned(orderId, actor);
    } else require(orderId == null, 400, "VALIDATION_ERROR", "此用途不接收订单ID");
    int limit =
        purpose.equals("pet_video")
            ? 100
            : purpose.equals("avatar")
                ? 2
                : List.of("pet_image", "banner").contains(purpose) ? 5 : 10;
    require(
        !file.isEmpty() && file.getSize() <= limit * 1024L * 1024,
        413,
        "FILE_TOO_LARGE",
        "文件为空或超过大小限制");
    String id = id("file");
    Path target = root.resolve(id);
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
            BufferedImage img = reader.read(0);
            require(img != null, 415, "UNSUPPORTED_FILE_TYPE", "图片无法解码");
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
      boolean pub =
          List.of("avatar", "pet_image", "banner", "pet_video", "quarantine_public")
              .contains(purpose);
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
                  file.getSize(),
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
      delete(target);
      throw e;
    } catch (Exception e) {
      delete(target);
      throw new ApiException(503, "SERVICE_UNAVAILABLE", "文件存储或解码服务不可用");
    }
  }

  private String safeName(String name) {
    String clean = Objects.toString(name, "file").replace('\\', '/');
    clean = clean.substring(clean.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
    return clean.substring(0, Math.min(clean.length(), 200));
  }

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

  private void probe(Path file, String mime) throws Exception {
    String binary = WechatGateway.env("PAW_FFPROBE");
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

  private void delete(Path p) {
    try {
      Files.deleteIfExists(p);
    } catch (Exception ignored) {
      /* 不掩盖原始校验失败；孤立文件由后续清理处理。 */
    }
  }

  public Map<String, Object> dto(Map<String, Object> f) {
    return CatalogService.select(
        f, "id,originalName,mimeType,sizeBytes,purpose,visibility,status,publicUrl,createdAt");
  }

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

  public Path content(String id, String token) {
    Map<String, Object> f = store.get("file", id);
    require("ready".equals(text(f, "status")), 404, "RESOURCE_NOT_FOUND", "文件不可访问");
    require(
        "public".equals(text(f, "visibility"))
            || token != null && id.equals(temp.get("file-link:" + hash(token))),
        404,
        "RESOURCE_NOT_FOUND",
        "文件不可访问");
    Path p = root.resolve(id).normalize();
    require(
        p.getParent().equals(root) && Files.isRegularFile(p), 404, "RESOURCE_NOT_FOUND", "文件不存在");
    return p;
  }
}
