package com.warmpaw.dto;

/** 前端统一成功响应契约；data 保持各业务接口原有形状。 */
public record ApiResponse<T>(
    String code, String message, T data, String requestId, String serverTime) {}
