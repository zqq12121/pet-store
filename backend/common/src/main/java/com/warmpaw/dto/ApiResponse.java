package com.warmpaw.dto;

/**
 * 前端统一成功响应契约；data 保持各业务接口原有形状。
 *
 * @param code       成功固定为 {@code "OK"}；失败时为业务错误码（见 {@code ApiErrors}）
 * @param message    成功固定为 {@code "success"}；失败时为面向用户的中文说明
 * @param data       业务负载，形状由具体接口决定，可能为 null（纯动作型接口）
 * @param requestId  本次响应的随机标识，供用户报障时对齐；不是分布式追踪 id
 * @param serverTime 服务器当前时间（ISO-8601），用于客户端校正时钟偏差
 */
public record ApiResponse<T>(
    String code, String message, T data, String requestId, String serverTime) {}
