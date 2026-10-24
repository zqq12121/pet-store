package com.warmpaw.service;

import com.aliyun.oss.*;
import com.aliyun.oss.model.*;
import com.warmpaw.common.ProviderSupport;
import jakarta.annotation.PreDestroy;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OSS 保存原件，本地卷保留校验及读取缓存；所有对象设为私有，由现有 API 鉴权。
 *
 * <p><b>本地卷不是"降级方案"，而是校验层 + 缓存层</b>：{@link FileService} 先把上传落到本地、
 * 用真实字节做类型嗅探与解码校验，通过后才调用 {@link #upload}；读取时本地若没有再从 OSS 拉回
 * 缓存。所以即使启用了 OSS，本地目录也必须可写，而且校验永远不会被"远端已存在"绕过。
 *
 * <p><b>关闭时是纯本地模式</b>：{@code client == null}，{@link #upload}/{@link #restoreCache}/
 * {@link #delete} 全部退化成空操作，{@link FileService} 不需要感知存储后端。
 * 这是刻意的降级——单机部署不接 OSS 也能跑。
 *
 * <p><b>配置缺失时失败关闭</b>：一旦 {@code PAW_OSS_ENABLED=true} 却缺 bucket / 密钥 / HTTPS
 * endpoint，构造期直接抛 {@code IllegalStateException}，让服务起不来。宁可启动失败，
 * 也不要"以为在上传、其实全落在本地盘"——后者会在换机器时静静丢掉全部附件。
 *
 * <p><b>密钥来源</b>：bucket 与 endpoint 走 Spring 配置，AK/SK 只从进程环境变量读
 * （{@code ProviderSupport.env}），不落配置文件、不进仓库。
 */
@Component
public class OssStorage {
  private final OSS client;
  private final String bucket;

  /**
   * 构造客户端，并在这里做配置一致性校验。
   *
   * <p>启用时四个条件任一不满足就抛异常：bucket、AccessKeyId、AccessKeySecret 为空，
   * 或 endpoint 不是 https。HTTPS 是硬要求——私有对象的鉴权头不该走明文链路。
   *
   * <p>未启用时不建客户端、也不校验其它字段：本地模式允许这些配置全空，所以这里没有把
   * "缺配置"当成错误，只把"启用了却缺配置"当成错误。
   *
   * <p>超时取 10 秒连接 / 30 秒读：偏保守，避免一次慢上传长时间占住请求线程。
   */
  public OssStorage(@Value("${PAW_OSS_ENABLED:false}") boolean enabled,
      @Value("${PAW_OSS_BUCKET:}") String bucket,
      @Value("${PAW_OSS_ENDPOINT:https://oss-cn-beijing.aliyuncs.com}") String endpoint) {
    this.bucket = bucket;
    String key = ProviderSupport.env("OSS_ACCESS_KEY_ID");
    String secret = ProviderSupport.env("OSS_ACCESS_KEY_SECRET");
    if (enabled && (bucket.isBlank() || key.isBlank() || secret.isBlank()
        || !endpoint.startsWith("https://")))
      throw new IllegalStateException("启用 OSS 必须提供 Bucket、HTTPS Endpoint 及访问凭据");
    ClientBuilderConfiguration config = new ClientBuilderConfiguration();
    config.setConnectionTimeout(10000);
    config.setSocketTimeout(30000);
    client = enabled ? new OSSClientBuilder().build(endpoint, key, secret, config) : null;
  }

  /**
   * 由文件 id 生成对象键，并强制校验 id 形状。
   *
   * <p>为什么必须校验：id 会拼进对象键，若不限制字符集，{@code ../} 之类的输入可以把对象写到
   * 别的前缀下（覆盖或读到别的业务对象）。正则只允许 {@code file_} + 32 位小写十六进制，
   * 与 {@code Json.id("file")} 的生成格式一致。
   */
  private String key(String id) {
    if (!id.matches("file_[a-f0-9]{32}")) throw new IllegalArgumentException("无效文件 ID");
    return "warmpaw/files/" + id;
  }

  /**
   * 上传到 OSS（未启用时直接返回，调用方无需分支）。
   *
   * <p>ACL 在同一次 PUT 里设置，而不是"先传再改 private"：后者存在一个对象已存在、但仍是
   * bucket 默认权限（可能公开）的时间窗，足够被枚举到。
   *
   * <p>本方法<b>不吞异常</b>：上传失败必须让 {@link FileService} 感知并清理本地文件，
   * 否则会留下"本地有、远端无"的孤儿，而库里却记着 ready。
   */
  public void upload(String id, Path path, String mime) {
    if (client == null) return;
    ObjectMetadata metadata = new ObjectMetadata();
    metadata.setContentType(mime);
    // 在同一次 PUT 中设置私有 ACL，避免公开 Bucket 中出现短暂暴露。
    metadata.setHeader("x-oss-object-acl", "private");
    PutObjectRequest request = new PutObjectRequest(bucket, key(id), path.toFile());
    request.setMetadata(metadata);
    client.putObject(request);
  }

  /**
   * 把 OSS 对象拉回本地缓存（本地已存在则直接返回，命中缓存不访问远端）。
   *
   * <p>先写 {@code .oss-*.part} 再原子 {@code move}：并发读取不会有人读到半个文件，
   * 下载中断也不会把残缺内容留在正式路径上。临时文件在 finally 里删掉，失败路径同样干净。
   *
   * <p>必须<b>在鉴权之后</b>调用（{@link FileService#content} 正是这么做的）：缓存文件本身就是
   * 一份明文副本，先拉再判权限等于把私有材料写进了本地目录。
   */
  public void restoreCache(String id, Path target) throws Exception {
    if (client == null || Files.isRegularFile(target)) return;
    Files.createDirectories(target.getParent());
    Path pending = Files.createTempFile(target.getParent(), ".oss-", ".part");
    try {
      client.getObject(new GetObjectRequest(bucket, key(id)), pending.toFile());
      Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(pending);
    }
  }

  /** 删除远端对象；未启用时为空操作，所以上传回滚与每日清理都可以无条件调用。 */
  public void delete(String id) {
    if (client != null) client.deleteObject(bucket, key(id));
  }

  /** 容器关闭时释放客户端；未启用时无事可做。不加这个钩子，热重载会一直攒着连接池。 */
  @PreDestroy
  public void close() {
    if (client != null) client.shutdown();
  }
}
