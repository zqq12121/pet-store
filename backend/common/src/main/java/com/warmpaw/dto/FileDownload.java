package com.warmpaw.dto;

import java.nio.file.Path;

/**
 * 文件服务确认访问权限后返回的下载描述，不向前端暴露磁盘路径。
 *
 * <p><b>{@code path} 只在本进程内传递</b>：它是服务器文件系统路径，
 * 绝不能序列化进响应体（会泄露部署结构，也给路径穿越提供信息）。
 * controller 拿到之后用 {@code FileSystemResource} 流式输出，响应里只有字节流。
 *
 * @param path        服务器上的真实路径；调用方负责流式输出，不要写进任何响应字段
 * @param mimeType    响应 {@code Content-Type}；来自 {@code file} 行里登记的元数据，
 *                    而不是按文件名后缀猜测
 * @param privateFile 是否私有文件（由 {@code visibility=private} 推出）：
 *                    controller 据此决定缓存策略——私有 {@code no-store}，公开可缓存
 */
public record FileDownload(Path path, String mimeType, boolean privateFile) {}
