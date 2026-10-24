-- 预约增量迁移：先于新版本服务执行，可重复运行，不修改历史订单。
CREATE TABLE IF NOT EXISTS order_appointments (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  visit_at VARCHAR(40) COMMENT 'visitAt',
  confirmation_deadline_at VARCHAR(40) COMMENT 'confirmationDeadlineAt',
  confirmed_at VARCHAR(40) COMMENT 'confirmedAt',
  completed_by VARCHAR(64) COMMENT 'completedBy',
  PRIMARY KEY (parent_id),
  FOREIGN KEY (parent_id) REFERENCES orders(id)
) COMMENT='appointment';
