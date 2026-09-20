-- 预约短信事务 outbox 增量迁移：部署新版 order-server 前执行，可重复创建且不改历史订单。
CREATE TABLE IF NOT EXISTS appointment_sms_outbox (
  event_key VARCHAR(100) NOT NULL COMMENT '订单ID加事件的幂等键',
  order_id VARCHAR(64) NOT NULL COMMENT '订单ID',
  event VARCHAR(32) NOT NULL COMMENT '预约事件',
  phone VARCHAR(11) NOT NULL COMMENT '登记时的联系人手机号快照',
  parameters LONGTEXT NOT NULL COMMENT '短信模板参数JSON快照',
  status VARCHAR(16) NOT NULL COMMENT 'pending/sending/retry/sent/failed',
  attempts INT NOT NULL DEFAULT 0 COMMENT '已认领发送次数',
  next_attempt_at VARCHAR(40) NOT NULL COMMENT '下次可重试时间',
  locked_until VARCHAR(40) COMMENT '多实例发送租约',
  last_error VARCHAR(64) COMMENT '稳定错误码，不保存敏感响应',
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  sent_at VARCHAR(40),
  PRIMARY KEY (event_key),
  FOREIGN KEY (order_id) REFERENCES orders(id),
  INDEX idx_appointment_sms_due (status,next_attempt_at)
) COMMENT='预约短信事务发件箱';
