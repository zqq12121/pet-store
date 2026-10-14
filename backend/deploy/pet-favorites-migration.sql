-- 已有数据库的增量迁移：先备份，再执行本文件；不重建已有表。
-- 买家动物收藏
CREATE TABLE IF NOT EXISTS pet_favorites (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  pet_id VARCHAR(64) COMMENT 'petId',
  PRIMARY KEY (id),
  FOREIGN KEY (owner_id) REFERENCES users(id),
  FOREIGN KEY (pet_id) REFERENCES pets(id),
  UNIQUE (owner_id,pet_id),
  INDEX idx_pet_favorites_owner (owner_id,status,created_at)
) COMMENT='买家动物收藏';
INSERT INTO schema_migrations (version,applied_at,source_count) SELECT 'pet-favorites-v1','2026-10-12T00:00:00Z',0 WHERE NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version='pet-favorites-v1');
