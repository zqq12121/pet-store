-- 宠友社区 v1：已有数据库先备份，仅新增领域表，不重建旧库。
-- 社区帖子与店铺评价
CREATE TABLE IF NOT EXISTS community_contents (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  type VARCHAR(16) COMMENT 'type',
  title VARCHAR(160) COMMENT 'title',
  body LONGTEXT COMMENT 'body',
  category VARCHAR(16) COMMENT 'category',
  rating INT COMMENT 'rating',
  order_id VARCHAR(64) COMMENT 'orderId',
  PRIMARY KEY (id),
  UNIQUE (order_id),
  FOREIGN KEY (owner_id) REFERENCES users(id),
  FOREIGN KEY (order_id) REFERENCES orders(id),
  CHECK (rating IS NULL OR rating BETWEEN 1 AND 5),
  INDEX idx_community_contents (type,status,created_at)
) COMMENT='社区帖子与店铺评价';

-- imageFileIds
CREATE TABLE IF NOT EXISTS community_images (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  position INT NOT NULL COMMENT '集合顺序，从0开始',
  file_id VARCHAR(64) COMMENT 'fileId',
  PRIMARY KEY (parent_id,position),
  FOREIGN KEY (parent_id) REFERENCES community_contents(id),
  FOREIGN KEY (file_id) REFERENCES file_assets(id)
) COMMENT='imageFileIds';

-- 社区评论与两层回复
CREATE TABLE IF NOT EXISTS community_comments (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  content_id VARCHAR(64) COMMENT 'contentId',
  root_id VARCHAR(64) COMMENT 'rootId',
  reply_to_id VARCHAR(64) COMMENT 'replyToId',
  author_role VARCHAR(16) COMMENT 'authorRole',
  body LONGTEXT COMMENT 'body',
  PRIMARY KEY (id),
  FOREIGN KEY (content_id) REFERENCES community_contents(id),
  FOREIGN KEY (root_id) REFERENCES community_comments(id),
  FOREIGN KEY (reply_to_id) REFERENCES community_comments(id),
  INDEX idx_community_comments (content_id,status,created_at)
) COMMENT='社区评论与两层回复';

-- 帖子点赞与收藏
CREATE TABLE IF NOT EXISTS community_reactions (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  content_id VARCHAR(64) COMMENT 'contentId',
  type VARCHAR(16) COMMENT 'type',
  PRIMARY KEY (id),
  FOREIGN KEY (content_id) REFERENCES community_contents(id),
  FOREIGN KEY (owner_id) REFERENCES users(id),
  UNIQUE (owner_id,content_id,type)
) COMMENT='帖子点赞与收藏';

-- 评论回复站内通知
CREATE TABLE IF NOT EXISTS community_notices (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  content_id VARCHAR(64) COMMENT 'contentId',
  comment_id VARCHAR(64) COMMENT 'commentId',
  actor_name VARCHAR(30) COMMENT 'actorName',
  message VARCHAR(100) COMMENT 'message',
  is_read BOOLEAN COMMENT 'read',
  PRIMARY KEY (id),
  FOREIGN KEY (owner_id) REFERENCES users(id),
  FOREIGN KEY (content_id) REFERENCES community_contents(id),
  FOREIGN KEY (comment_id) REFERENCES community_comments(id),
  INDEX idx_community_notices (owner_id,created_at)
) COMMENT='评论回复站内通知';

-- 社区内容举报
CREATE TABLE IF NOT EXISTS community_reports (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  target_id VARCHAR(64) COMMENT 'targetId',
  target_type VARCHAR(16) COMMENT 'targetType',
  reason VARCHAR(500) COMMENT 'reason',
  action VARCHAR(16) COMMENT 'action',
  handled_by VARCHAR(64) COMMENT 'handledBy',
  PRIMARY KEY (id),
  FOREIGN KEY (owner_id) REFERENCES users(id),
  INDEX idx_community_reports (status,created_at)
) COMMENT='社区内容举报';

-- 管理员维护的恶意词库
CREATE TABLE IF NOT EXISTS community_words (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  term VARCHAR(160) COMMENT 'term',
  normalized VARCHAR(160) COMMENT 'normalized',
  enabled BOOLEAN COMMENT 'enabled',
  PRIMARY KEY (id),
  UNIQUE (normalized)
) COMMENT='管理员维护的恶意词库';
INSERT INTO community_words (id,business_key,status,version,created_at,updated_at,term,normalized,enabled) SELECT 'community_word_seed_0','傻逼','ready',1,'2026-10-11T00:00:00Z','2026-10-11T00:00:00Z','傻逼','傻逼',TRUE WHERE NOT EXISTS (SELECT 1 FROM community_words WHERE business_key='傻逼');
INSERT INTO community_words (id,business_key,status,version,created_at,updated_at,term,normalized,enabled) SELECT 'community_word_seed_1','操你妈','ready',1,'2026-10-11T00:00:00Z','2026-10-11T00:00:00Z','操你妈','操你妈',TRUE WHERE NOT EXISTS (SELECT 1 FROM community_words WHERE business_key='操你妈');
INSERT INTO community_words (id,business_key,status,version,created_at,updated_at,term,normalized,enabled) SELECT 'community_word_seed_2','他妈的','ready',1,'2026-10-11T00:00:00Z','2026-10-11T00:00:00Z','他妈的','他妈的',TRUE WHERE NOT EXISTS (SELECT 1 FROM community_words WHERE business_key='他妈的');
INSERT INTO schema_migrations (version,applied_at,source_count) SELECT 'community-v1','2026-10-11T00:00:00Z',0 WHERE NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version='community-v1');
