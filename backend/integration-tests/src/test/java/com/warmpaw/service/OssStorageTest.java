package com.warmpaw.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.*;
import java.io.File;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** 不访问云端，覆盖私有 ACL、缓存回源和失败后临时文件清理。 */
class OssStorageTest {
  @TempDir Path directory;
  final String id = "file_0123456789abcdef0123456789abcdef";

  private OssStorage storage(OSS client) {
    OssStorage storage = new OssStorage(false, "test-bucket", "https://oss-cn-beijing.aliyuncs.com");
    ReflectionTestUtils.setField(storage, "client", client);
    return storage;
  }

  @Test void uploadsPrivateObjectInProjectPrefix() throws Exception {
    OSS client = mock(OSS.class);
    Path file = Files.writeString(directory.resolve(id), "test content");
    storage(client).upload(id, file, "image/png");
    ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(client).putObject(request.capture());
    assertEquals("warmpaw/files/" + id, request.getValue().getKey());
    assertEquals("private", request.getValue().getMetadata().getRawMetadata().get("x-oss-object-acl"));
  }

  @Test void restoresMissingCacheAndReusesIt() throws Exception {
    OSS client = mock(OSS.class);
    when(client.getObject(any(GetObjectRequest.class), any(File.class))).thenAnswer(call -> {
      Files.writeString(((File) call.getArgument(1)).toPath(), "from OSS");
      return new ObjectMetadata();
    });
    OssStorage storage = storage(client);
    Path file = directory.resolve(id);
    storage.restoreCache(id, file);
    storage.restoreCache(id, file);
    assertEquals("from OSS", Files.readString(file));
    verify(client, times(1)).getObject(any(GetObjectRequest.class), any(File.class));
  }

  @Test void failedDownloadLeavesNoPartialCache() {
    OSS client = mock(OSS.class);
    when(client.getObject(any(GetObjectRequest.class), any(File.class))).thenThrow(new RuntimeException("offline"));
    assertThrows(RuntimeException.class, () -> storage(client).restoreCache(id, directory.resolve(id)));
    assertFalse(Files.exists(directory.resolve(id)));
    assertEquals(0, directory.toFile().list().length);
  }

  @Test void rejectsKeysOutsideProjectFileIds() {
    OSS client = mock(OSS.class);
    assertThrows(IllegalArgumentException.class, () -> storage(client).delete("../other"));
    verifyNoInteractions(client);
  }
}
