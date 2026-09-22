-- 先备份再执行一次；只增加可空字段，旧账号无需重建或设置默认密码。
ALTER TABLE admins ADD COLUMN password_changed_at VARCHAR(40) NULL;
ALTER TABLE users
  ADD COLUMN username VARCHAR(32) NULL,
  ADD COLUMN password_hash VARCHAR(255) NULL,
  ADD COLUMN password_changed_at VARCHAR(40) NULL,
  ADD COLUMN username_changed_at VARCHAR(40) NULL,
  ADD CONSTRAINT uk_users_username UNIQUE (username);
INSERT INTO schema_migrations (version, applied_at, source_count)
VALUES ('account-security-v1', DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-%dT%H:%i:%sZ'), 0);
