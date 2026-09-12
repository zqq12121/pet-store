package com.warmpaw.application;
import java.util.Map;
/** 各服务的事务结果扩展；默认保存原响应，交易服务提供状态刷新和提交后支付处理。 */
public interface BusinessHooks {
  default OperationResult replay(Map<String, Object> saved, String path, boolean admin, int status) {
    return new OperationResult(status, saved.get("data"));
  }
  default OperationResult afterCommit(OperationResult result, RequestContext context) { return result; }
}
