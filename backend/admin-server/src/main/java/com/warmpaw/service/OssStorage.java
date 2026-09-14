package com.warmpaw.service;

import com.aliyun.oss.*;
import com.aliyun.oss.model.*;
import com.warmpaw.common.ProviderSupport;
import jakarta.annotation.PreDestroy;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** OSS 保存原件，本地卷保留校验及读取缓存；所有对象设为私有，由现有 API 鉴权。 */
@Component
public class OssStorage {
  private final OSS client;
  private final String bucket;

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

  private String key(String id) {
    if (!id.matches("file_[a-f0-9]{32}")) throw new IllegalArgumentException("无效文件 ID");
    return "warmpaw/files/" + id;
  }

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

  public void delete(String id) {
    if (client != null) client.deleteObject(bucket, key(id));
  }

  @PreDestroy
  public void close() {
    if (client != null) client.shutdown();
  }
}
