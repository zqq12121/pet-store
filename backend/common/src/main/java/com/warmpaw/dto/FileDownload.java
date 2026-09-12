package com.warmpaw.dto;

import java.nio.file.Path;

/** 文件服务确认访问权限后返回的下载描述，不向前端暴露磁盘路径。 */
public record FileDownload(Path path, String mimeType, boolean privateFile) {}
