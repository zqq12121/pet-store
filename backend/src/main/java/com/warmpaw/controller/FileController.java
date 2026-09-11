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

/** 文件上传、授权访问和媒体响应接口。 */
@RestController
@RequestMapping("/api/v1")
public class FileController {
  private final AuthService auth;
  private final FileService files;

  public FileController(AuthService auth, FileService files) {
    this.auth = auth;
    this.files = files;
  }

  private AuthService.Actor actor(HttpServletRequest request) {
    return auth.identify(request.getHeader("Authorization"));
  }

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

  @GetMapping("/files/{id}/access-url")
  public ResponseEntity<Object> access(@PathVariable String id, HttpServletRequest req) {
    return ok(200, files.access(id, actor(req)));
  }

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
