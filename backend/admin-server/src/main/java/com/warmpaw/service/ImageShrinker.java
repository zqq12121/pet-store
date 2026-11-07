package com.warmpaw.service;

import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;

/**
 * 上传图片瘦身：长边超过上限的 JPEG/PNG 缩到上限并重新编码。
 *
 * <p>手机原图常见 4000px、3~5MB，而页面最大只按 1920 宽显示；缩小后通常只剩几百 KB。
 *
 * <p><b>EXIF 方向必须先摆正</b>：手机竖拍的照片像素是横的，靠 EXIF Orientation 告诉浏览器旋转。
 * 重新编码会丢掉 EXIF，不先按方向转好，竖图就会变成横图。丢掉 EXIF 同时去掉了 GPS 等隐私信息。
 *
 * <p>WebP 不处理：JDK 与 TwelveMonkeys 都没有 WebP 编码器，且 WebP 通常已经压缩过。
 */
final class ImageShrinker {
  static final int MAX_EDGE = 1920;
  private static final float JPEG_QUALITY = 0.85f;

  private ImageShrinker() {}

  /**
   * 需要时就地改写文件。
   *
   * @return 是否改写
   */
  static boolean shrink(Path file, String mime, BufferedImage image, int maxEdge)
      throws IOException {
    boolean jpeg = mime.equals("image/jpeg");
    if (!jpeg && !mime.equals("image/png")) return false;
    if (Math.max(image.getWidth(), image.getHeight()) <= maxEdge) return false;
    // 先缩小再摆正：长边与方向无关，旋转只作用在小图上，不额外占用一份原图大小的内存。
    BufferedImage scaled = orient(scale(image, maxEdge, jpeg), jpeg ? exifOrientation(file) : 1, jpeg);
    Path temp = Files.createTempFile(file.getParent(), "shrink", ".tmp");
    try {
      if (jpeg) writeJpeg(scaled, temp);
      else ImageIO.write(scaled, "png", temp.toFile());
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
    }
    return true;
  }

  /** 按 EXIF Orientation（1~8）把像素摆成正常朝向；1 或未知值原样返回。 */
  static BufferedImage orient(BufferedImage src, int orientation, boolean opaque) {
    if (orientation < 2 || orientation > 8) return src;
    int w = src.getWidth(), h = src.getHeight();
    boolean swap = orientation >= 5;
    var t = new AffineTransform();
    switch (orientation) {
      case 2 -> t.setTransform(-1, 0, 0, 1, w, 0); // 水平镜像
      case 3 -> t.setTransform(-1, 0, 0, -1, w, h); // 旋转 180°
      case 4 -> t.setTransform(1, 0, 0, -1, 0, h); // 垂直镜像
      case 5 -> t.setTransform(0, 1, 1, 0, 0, 0); // 沿主对角线转置
      case 6 -> t.setTransform(0, 1, -1, 0, h, 0); // 顺时针 90°
      case 7 -> t.setTransform(0, -1, -1, 0, h, w); // 沿副对角线转置
      default -> t.setTransform(0, -1, 1, 0, 0, w); // 8：逆时针 90°
    }
    var out = new BufferedImage(swap ? h : w, swap ? w : h, type(opaque));
    var g = out.createGraphics();
    g.drawImage(src, t, null);
    g.dispose();
    return out;
  }

  /** 每次最多缩一半，逐级缩到目标尺寸，避免一步缩小产生锯齿。 */
  private static BufferedImage scale(BufferedImage src, int maxEdge, boolean opaque) {
    double ratio = (double) maxEdge / Math.max(src.getWidth(), src.getHeight());
    int targetW = Math.max(1, (int) Math.round(src.getWidth() * ratio));
    int targetH = Math.max(1, (int) Math.round(src.getHeight() * ratio));
    BufferedImage current = src;
    do {
      int w = Math.max(targetW, current.getWidth() / 2);
      int h = Math.max(targetH, current.getHeight() / 2);
      var next = new BufferedImage(w, h, type(opaque));
      var g = next.createGraphics();
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.drawImage(current, 0, 0, w, h, null);
      g.dispose();
      current = next;
    } while (current.getWidth() != targetW || current.getHeight() != targetH);
    return current;
  }

  private static int type(boolean opaque) {
    return opaque ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
  }

  private static void writeJpeg(BufferedImage image, Path target) throws IOException {
    var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
    try (var out = ImageIO.createImageOutputStream(target.toFile())) {
      writer.setOutput(out);
      ImageWriteParam param = writer.getDefaultWriteParam();
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      param.setCompressionQuality(JPEG_QUALITY);
      writer.write(null, new IIOImage(image, null, null), param);
    } finally {
      writer.dispose();
    }
  }

  /**
   * 读取 JPEG 的 EXIF Orientation；没有或解析失败返回 1。
   *
   * <p>只扫描 SOS 之前的段：APP1 里 "Exif\0\0" 后是 TIFF 头（II/MM 字节序），
   * IFD0 中标签 0x0112 即方向。
   */
  static int exifOrientation(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      byte[] head = in.readNBytes(256 * 1024);
      int p = 2;
      if (head.length < 4 || (head[0] & 255) != 0xFF || (head[1] & 255) != 0xD8) return 1;
      while (p + 4 <= head.length && (head[p] & 255) == 0xFF) {
        int marker = head[p + 1] & 255;
        int length = ((head[p + 2] & 255) << 8) | (head[p + 3] & 255);
        if (marker == 0xDA || length < 2) return 1;
        int data = p + 4;
        if (marker == 0xE1
            && data + 14 <= head.length
            && new String(head, data, 6, java.nio.charset.StandardCharsets.ISO_8859_1)
                .equals("Exif\0\0")) return tiffOrientation(head, data + 6);
        p += 2 + length;
      }
    } catch (IOException | RuntimeException e) {
      // 方向信息只是锦上添花，读不到就按正常方向处理。
    }
    return 1;
  }

  private static int tiffOrientation(byte[] b, int tiff) {
    boolean little = b[tiff] == 'I';
    int ifd = tiff + read(b, tiff + 4, 4, little);
    int count = read(b, ifd, 2, little);
    for (int i = 0; i < count; i++) {
      int entry = ifd + 2 + i * 12;
      if (read(b, entry, 2, little) == 0x0112) return read(b, entry + 8, 2, little);
    }
    return 1;
  }

  private static int read(byte[] b, int at, int size, boolean little) {
    int value = 0;
    for (int i = 0; i < size; i++) {
      int byteValue = b[at + (little ? size - 1 - i : i)] & 255;
      value = (value << 8) | byteValue;
    }
    return value;
  }
}
