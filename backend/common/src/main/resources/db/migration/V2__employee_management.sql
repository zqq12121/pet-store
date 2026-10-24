-- 备份后只执行一次；旧管理员继续作为店长，保留密码、会话与所有业务记录。
ALTER TABLE admins
  ADD COLUMN role VARCHAR(16) NULL COMMENT 'admin 店长 / staff 接待员工',
  ADD COLUMN display_name VARCHAR(30) NULL COMMENT '后台人员姓名',
  ADD COLUMN phone VARCHAR(11) NULL COMMENT '员工联系号码，选填';
UPDATE admins SET role='admin' WHERE role IS NULL;
INSERT INTO schema_migrations (version, applied_at, source_count)
VALUES ('employee-management-v1', DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-%dT%H:%i:%sZ'), 0);
