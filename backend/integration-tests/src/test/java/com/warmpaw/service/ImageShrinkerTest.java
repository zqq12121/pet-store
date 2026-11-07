package com.warmpaw.service;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 上传图片瘦身：EXIF 方向摆正、缩放、透明度保留，小图不动。 */
class ImageShrinkerTest {
  @TempDir Path dir;

  /** EXIF 规范：显示坐标 (X,Y) 来自原图 (x,y) 的映射，w/h 为原图宽高。 */
  private static int[] expected(int o, int x, int y, int w, int h) {
    return switch (o) {
      case 2 -> new int[] {w - 1 - x, y};
      case 3 -> new int[] {w - 1 - x, h - 1 - y};
      case 4 -> new int[] {x, h - 1 - y};
      case 5 -> new int[] {y, x};
      case 6 -> new int[] {h - 1 - y, x};
      case 7 -> new int[] {h - 1 - y, w - 1 - x};
      case 8 -> new int[] {y, w - 1 - x};
      default -> new int[] {x, y};
    };
  }

  @Test
  void everyExifOrientationMatchesTheSpec() {
    int w = 3, h = 2;
    var src = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) src.setRGB(x, y, 0x101010 * (1 + x + y * w));
    for (int o = 1; o <= 8; o++) {
      var out = ImageShrinker.orient(src, o, true);
      assertEquals(o >= 5 ? h : w, out.getWidth(), "orientation " + o);
      for (int x = 0; x < w; x++)
        for (int y = 0; y < h; y++) {
          int[] p = expected(o, x, y, w, h);
          assertEquals(src.getRGB(x, y), out.getRGB(p[0], p[1]), "orientation " + o + " at " + x + "," + y);
        }
    }
  }

  /** 在 JPEG 的 SOI 之后插入只含 Orientation 的 EXIF 段。 */
  private static byte[] withOrientation(byte[] jpeg, int orientation, boolean little) {
    byte[] tiff =
        little
            ? new byte[] {'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0, 0, 0, 0, 0}
            : new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, 0, 0, 0, 0};
    var out = new ByteArrayOutputStream();
    out.write(jpeg, 0, 2);
    int length = 2 + 6 + tiff.length;
    out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length});
    out.writeBytes(new byte[] {'E', 'x', 'i', 'f', 0, 0});
    out.writeBytes(tiff);
    out.write(jpeg, 2, jpeg.length - 2);
    return out.toByteArray();
  }

  private static byte[] encode(BufferedImage image, String format) throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(image, format, out);
    return out.toByteArray();
  }

  @Test
  void readsOrientationInBothByteOrders() throws Exception {
    byte[] jpeg = encode(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "jpg");
    Path mm = dir.resolve("mm.jpg"), ii = dir.resolve("ii.jpg"), none = dir.resolve("none.jpg");
    Files.write(mm, withOrientation(jpeg, 6, false));
    Files.write(ii, withOrientation(jpeg, 8, true));
    Files.write(none, jpeg);
    assertEquals(6, ImageShrinker.exifOrientation(mm));
    assertEquals(8, ImageShrinker.exifOrientation(ii));
    assertEquals(1, ImageShrinker.exifOrientation(none));
  }

  @Test
  void largePortraitPhotoIsScaledUprightAndExifRemoved() throws Exception {
    // 手机竖拍：像素横向 3000x1000，EXIF 6 表示需顺时针转 90°。
    var image = new BufferedImage(3000, 1000, BufferedImage.TYPE_INT_RGB);
    Path file = dir.resolve("photo.jpg");
    Files.write(file, withOrientation(encode(image, "jpg"), 6, false));
    long before = Files.size(file);
    assertTrue(ImageShrinker.shrink(file, "image/jpeg", ImageIO.read(file.toFile()), 300));
    var stored = ImageIO.read(file.toFile());
    assertEquals(100, stored.getWidth());
    assertEquals(300, stored.getHeight());
    assertEquals(1, ImageShrinker.exifOrientation(file));
    assertTrue(Files.size(file) < before);
  }

  @Test
  void pngKeepsTransparencyAndSmallImagesAreUntouched() throws Exception {
    var png = new BufferedImage(800, 400, BufferedImage.TYPE_INT_ARGB);
    Path file = dir.resolve("logo.png");
    Files.write(file, encode(png, "png"));
    assertTrue(ImageShrinker.shrink(file, "image/png", png, 200));
    var stored = ImageIO.read(file.toFile());
    assertEquals(200, stored.getWidth());
    assertEquals(100, stored.getHeight());
    assertTrue(stored.getColorModel().hasAlpha());
    assertEquals(0, stored.getRGB(10, 10) >>> 24);

    Path small = dir.resolve("small.jpg");
    byte[] bytes = encode(new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB), "jpg");
    Files.write(small, bytes);
    assertFalse(ImageShrinker.shrink(small, "image/jpeg", ImageIO.read(small.toFile()), 300));
    assertArrayEquals(bytes, Files.readAllBytes(small));
    assertFalse(ImageShrinker.shrink(small, "image/webp", new BufferedImage(4000, 10, 1), 300));
  }
}
