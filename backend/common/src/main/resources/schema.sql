-- 关系模型 v2：金额为分；ISO-8601时间保留时区文本；出生/检疫日期使用DATE。
-- 不包含 resources 通用业务表；旧库由显式迁移工具处理，不在启动时删除数据。
CREATE TABLE IF NOT EXISTS business_lock (id INT PRIMARY KEY);
INSERT INTO business_lock (id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM business_lock WHERE id=1);

-- 管理员
CREATE TABLE IF NOT EXISTS admins (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  username VARCHAR(32) COMMENT 'username',
  password_hash VARCHAR(255) COMMENT 'passwordHash',
  PRIMARY KEY (id),
  UNIQUE (username)
) COMMENT='管理员';

-- 买家用户
CREATE TABLE IF NOT EXISTS users (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  phone VARCHAR(11) COMMENT 'phone',
  nickname VARCHAR(30) COMMENT 'nickname',
  avatar_url VARCHAR(2048) COMMENT 'avatarUrl',
  PRIMARY KEY (id),
  UNIQUE (phone)
) COMMENT='买家用户';

-- 微信账号绑定
CREATE TABLE IF NOT EXISTS wechat_accounts (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  app_id VARCHAR(64) COMMENT 'appId',
  openid VARCHAR(128) COMMENT 'openid',
  PRIMARY KEY (id),
  UNIQUE (app_id,openid),
  UNIQUE (app_id,owner_id),
  FOREIGN KEY (owner_id) REFERENCES users(id)
) COMMENT='微信账号绑定';

-- 微信身份唯一归属
CREATE TABLE IF NOT EXISTS wechat_openid_claims (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  app_id VARCHAR(64) COMMENT 'appId',
  PRIMARY KEY (id),
  FOREIGN KEY (owner_id) REFERENCES users(id)
) COMMENT='微信身份唯一归属';

-- 可撤销登录会话
CREATE TABLE IF NOT EXISTS auth_sessions (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  role VARCHAR(16) COMMENT 'role',
  expires_at VARCHAR(40) COMMENT 'expiresAt',
  PRIMARY KEY (id)
) COMMENT='可撤销登录会话';

-- 文件元数据及访问权限
CREATE TABLE IF NOT EXISTS file_assets (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  order_id VARCHAR(64) COMMENT 'orderId',
  original_name VARCHAR(255) COMMENT 'originalName',
  mime_type VARCHAR(100) COMMENT 'mimeType',
  size_bytes BIGINT COMMENT 'sizeBytes',
  purpose VARCHAR(40) COMMENT 'purpose',
  visibility VARCHAR(16) COMMENT 'visibility',
  public_url VARCHAR(2048) COMMENT 'publicUrl',
  PRIMARY KEY (id)
) COMMENT='文件元数据及访问权限';

-- 门店
CREATE TABLE IF NOT EXISTS shops (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  name VARCHAR(80) COMMENT 'name',
  address VARCHAR(200) COMMENT 'address',
  latitude DECIMAL(12,8) COMMENT 'latitude',
  longitude DECIMAL(12,8) COMMENT 'longitude',
  coordinate_system VARCHAR(16) COMMENT 'coordinateSystem',
  phone VARCHAR(200) COMMENT 'phone',
  wechat VARCHAR(200) COMMENT 'wechat',
  business_hours VARCHAR(200) COMMENT 'businessHours',
  pickup_instructions LONGTEXT COMMENT 'pickupInstructions',
  payment_timeout_minutes INT COMMENT 'paymentTimeoutMinutes',
  pickup_retention_hours INT COMMENT 'pickupRetentionHours',
  exchange_enabled BOOLEAN COMMENT 'exchangeEnabled',
  PRIMARY KEY (id)
) COMMENT='门店';

-- banners
CREATE TABLE IF NOT EXISTS shop_banners (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  position INT NOT NULL COMMENT '集合顺序，从0开始',
  id VARCHAR(64) COMMENT 'id',
  title VARCHAR(80) COMMENT 'title',
  image_file_id VARCHAR(64) COMMENT 'imageFileId',
  link_type VARCHAR(16) COMMENT 'linkType',
  pet_id VARCHAR(64) COMMENT 'petId',
  notice_text LONGTEXT COMMENT 'noticeText',
  sort_order INT COMMENT 'sortOrder',
  enabled BOOLEAN COMMENT 'enabled',
  PRIMARY KEY (parent_id,position),
  FOREIGN KEY (parent_id) REFERENCES shops(id)
) COMMENT='banners';

-- 宠物个体档案
CREATE TABLE IF NOT EXISTS pets (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  name VARCHAR(80) COMMENT 'name',
  category VARCHAR(8) COMMENT 'category',
  breed VARCHAR(50) COMMENT 'breed',
  gender VARCHAR(8) COMMENT 'gender',
  price_amount BIGINT COMMENT 'priceAmount',
  age_months INT COMMENT 'ageMonths',
  birth_date DATE COMMENT 'birthDate',
  weight_kg DECIMAL(12,2) COMMENT 'weightKg',
  color VARCHAR(30) COMMENT 'color',
  vaccine_status VARCHAR(500) COMMENT 'vaccineStatus',
  deworm_status VARCHAR(500) COMMENT 'dewormStatus',
  description LONGTEXT COMMENT 'description',
  feeding_notes LONGTEXT COMMENT 'feedingNotes',
  health_description LONGTEXT COMMENT 'healthDescription',
  video_file_id VARCHAR(64) COMMENT 'videoFileId',
  is_recommended BOOLEAN COMMENT 'isRecommended',
  recommendation_order INT COMMENT 'recommendationOrder',
  active_order_id VARCHAR(64) COMMENT 'activeOrderId',
  reviewed_at VARCHAR(40) COMMENT 'reviewedAt',
  last_sale_review_note LONGTEXT COMMENT 'lastSaleReviewNote',
  published_at VARCHAR(40) COMMENT 'publishedAt',
  PRIMARY KEY (id),
  CHECK (price_amount > 0),
  CHECK (category IN ('cat','dog')),
  CHECK (gender IN ('male','female')),
  CHECK (age_months BETWEEN 0 AND 360),
  CHECK (weight_kg > 0 AND weight_kg <= 200),
  CHECK (status IN ('off','on_sale','reserved','sold')),
  INDEX idx_pets_filter (status,category,price_amount),
  INDEX idx_pets_breed (category,breed),
  INDEX idx_pets_published (published_at,id)
) COMMENT='宠物个体档案';

-- imageFileIds
CREATE TABLE IF NOT EXISTS pet_images (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  position INT NOT NULL COMMENT '集合顺序，从0开始',
  file_id VARCHAR(64) COMMENT 'fileId',
  PRIMARY KEY (parent_id,position),
  FOREIGN KEY (parent_id) REFERENCES pets(id),
  FOREIGN KEY (file_id) REFERENCES file_assets(id)
) COMMENT='imageFileIds';

-- personalityTags
CREATE TABLE IF NOT EXISTS pet_personality_tags (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  position INT NOT NULL COMMENT '集合顺序，从0开始',
  tag VARCHAR(20) COMMENT 'tag',
  PRIMARY KEY (parent_id,position),
  FOREIGN KEY (parent_id) REFERENCES pets(id)
) COMMENT='personalityTags';

-- quarantine
CREATE TABLE IF NOT EXISTS pet_quarantine_certificates (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  certificate_no VARCHAR(100) COMMENT 'certificateNo',
  valid_until DATE COMMENT 'validUntil',
  PRIMARY KEY (parent_id),
  FOREIGN KEY (parent_id) REFERENCES pets(id)
) COMMENT='quarantine';

-- publicImageFileIds
CREATE TABLE IF NOT EXISTS pet_quarantine_public_files (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  position INT NOT NULL COMMENT '集合顺序，从0开始',
  file_id VARCHAR(64) COMMENT 'fileId',
  PRIMARY KEY (parent_id,position),
  FOREIGN KEY (parent_id) REFERENCES pet_quarantine_certificates(parent_id),
  FOREIGN KEY (file_id) REFERENCES file_assets(id)
) COMMENT='publicImageFileIds';

-- originalFileIds
CREATE TABLE IF NOT EXISTS pet_quarantine_original_files (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  position INT NOT NULL COMMENT '集合顺序，从0开始',
  file_id VARCHAR(64) COMMENT 'fileId',
  PRIMARY KEY (parent_id,position),
  FOREIGN KEY (parent_id) REFERENCES pet_quarantine_certificates(parent_id),
  FOREIGN KEY (file_id) REFERENCES file_assets(id)
) COMMENT='originalFileIds';

-- 协议不可变版本
CREATE TABLE IF NOT EXISTS agreement_versions (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version VARCHAR(32) NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  type VARCHAR(32) COMMENT 'type',
  title VARCHAR(100) COMMENT 'title',
  content LONGTEXT COMMENT 'content',
  content_format VARCHAR(16) COMMENT 'contentFormat',
  content_hash VARCHAR(64) COMMENT 'contentHash',
  health_guarantee_days INT COMMENT 'healthGuaranteeDays',
  published_at VARCHAR(40) COMMENT 'publishedAt',
  PRIMARY KEY (id),
  UNIQUE (type,version)
) COMMENT='协议不可变版本';

-- 单宠订单
CREATE TABLE IF NOT EXISTS orders (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  order_no VARCHAR(64) COMMENT 'orderNo',
  pet_id VARCHAR(64) COMMENT 'petId',
  amount BIGINT COMMENT 'amount',
  refunded_amount BIGINT COMMENT 'refundedAmount',
  currency VARCHAR(3) COMMENT 'currency',
  contact_name VARCHAR(30) COMMENT 'contactName',
  contact_phone VARCHAR(11) COMMENT 'contactPhone',
  remark VARCHAR(500) COMMENT 'remark',
  expires_at VARCHAR(40) COMMENT 'expiresAt',
  pickup_retention_hours INT COMMENT 'pickupRetentionHours',
  health_guarantee_days INT COMMENT 'healthGuaranteeDays',
  paid_at VARCHAR(40) COMMENT 'paidAt',
  pickup_deadline_at VARCHAR(40) COMMENT 'pickupDeadlineAt',
  completed_at VARCHAR(40) COMMENT 'completedAt',
  health_guarantee_expires_at VARCHAR(40) COMMENT 'healthGuaranteeExpiresAt',
  picked_up_at VARCHAR(40) COMMENT 'pickedUpAt',
  closed_at VARCHAR(40) COMMENT 'closedAt',
  cancelled_at VARCHAR(40) COMMENT 'cancelledAt',
  cancel_reason VARCHAR(500) COMMENT 'cancelReason',
  refund_previous_status VARCHAR(40) COMMENT 'refundPreviousStatus',
  PRIMARY KEY (id),
  UNIQUE (order_no),
  FOREIGN KEY (owner_id) REFERENCES users(id),
  FOREIGN KEY (pet_id) REFERENCES pets(id),
  CHECK (amount > 0),
  CHECK (refunded_amount >= 0 AND refunded_amount <= amount),
  INDEX idx_orders_owner (owner_id,status,created_at),
  INDEX idx_orders_pet (pet_id),
  INDEX idx_orders_expiry (status,expires_at),
  INDEX idx_orders_contact (contact_phone,created_at)
) COMMENT='单宠订单';

-- appointment
CREATE TABLE IF NOT EXISTS order_appointments (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  visit_at VARCHAR(40) COMMENT 'visitAt',
  confirmation_deadline_at VARCHAR(40) COMMENT 'confirmationDeadlineAt',
  confirmed_at VARCHAR(40) COMMENT 'confirmedAt',
  completed_by VARCHAR(64) COMMENT 'completedBy',
  PRIMARY KEY (parent_id),
  FOREIGN KEY (parent_id) REFERENCES orders(id)
) COMMENT='appointment';

-- snapshots
CREATE TABLE IF NOT EXISTS order_snapshots (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  product LONGTEXT COMMENT 'product，历史快照或外部调用参数JSON',
  price_snapshot LONGTEXT COMMENT 'priceSnapshot，历史快照或外部调用参数JSON',
  product_snapshot LONGTEXT COMMENT 'productSnapshot，历史快照或外部调用参数JSON',
  shop_snapshot LONGTEXT COMMENT 'shopSnapshot，历史快照或外部调用参数JSON',
  agreement LONGTEXT COMMENT 'agreement，历史快照或外部调用参数JSON',
  PRIMARY KEY (parent_id),
  FOREIGN KEY (parent_id) REFERENCES orders(id)
) COMMENT='snapshots';

-- payment
CREATE TABLE IF NOT EXISTS order_payment_state (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  payment_id VARCHAR(64) COMMENT 'paymentId',
  status VARCHAR(40) COMMENT 'status',
  paid_amount BIGINT COMMENT 'paidAmount',
  paid_at VARCHAR(40) COMMENT 'paidAt',
  PRIMARY KEY (parent_id),
  FOREIGN KEY (parent_id) REFERENCES orders(id)
) COMMENT='payment';

-- pickup
CREATE TABLE IF NOT EXISTS pickup_credentials (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  code VARCHAR(8) COMMENT 'code',
  qr_payload VARCHAR(128) COMMENT 'qrPayload',
  expires_at VARCHAR(40) COMMENT 'expiresAt',
  buyer_confirmed_at VARCHAR(40) COMMENT 'buyerConfirmedAt',
  PRIMARY KEY (parent_id),
  FOREIGN KEY (parent_id) REFERENCES orders(id)
) COMMENT='pickup';

-- delivery
CREATE TABLE IF NOT EXISTS order_delivery_evidence (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  pickup_evidence LONGTEXT COMMENT 'pickupEvidence，历史快照或外部调用参数JSON',
  delivery_quarantine LONGTEXT COMMENT 'deliveryQuarantine，历史快照或外部调用参数JSON',
  PRIMARY KEY (parent_id),
  FOREIGN KEY (parent_id) REFERENCES orders(id)
) COMMENT='delivery';

-- deliveryOriginalFileIds
CREATE TABLE IF NOT EXISTS order_delivery_original_files (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  position INT NOT NULL COMMENT '集合顺序，从0开始',
  file_id VARCHAR(64) COMMENT 'fileId',
  PRIMARY KEY (parent_id,position),
  FOREIGN KEY (parent_id) REFERENCES orders(id),
  FOREIGN KEY (file_id) REFERENCES file_assets(id)
) COMMENT='deliveryOriginalFileIds';

-- 支付请求及对账状态
CREATE TABLE IF NOT EXISTS payments (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  out_trade_no VARCHAR(64) COMMENT 'outTradeNo',
  order_id VARCHAR(64) COMMENT 'orderId',
  scene VARCHAR(16) COMMENT 'scene',
  expires_at VARCHAR(40) COMMENT 'expiresAt',
  provider_created BOOLEAN COMMENT 'providerCreated',
  provider_attempted BOOLEAN COMMENT 'providerAttempted',
  last_query_at VARCHAR(40) COMMENT 'lastQueryAt',
  transaction_id VARCHAR(128) COMMENT 'transactionId',
  last_provider_error VARCHAR(100) COMMENT 'lastProviderError',
  h5_url VARCHAR(2048) COMMENT 'h5Url',
  prepay_id VARCHAR(128) COMMENT 'prepayId',
  invoke_params LONGTEXT COMMENT 'invokeParams，历史快照或外部调用参数JSON',
  PRIMARY KEY (id),
  UNIQUE (out_trade_no),
  UNIQUE (transaction_id),
  FOREIGN KEY (order_id) REFERENCES orders(id),
  INDEX idx_payments_order (order_id),
  INDEX idx_payments_state (status,last_query_at)
) COMMENT='支付请求及对账状态';

-- 已核实的支付流水
CREATE TABLE IF NOT EXISTS payment_transactions (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  payment_id VARCHAR(64) COMMENT 'paymentId',
  order_id VARCHAR(64) COMMENT 'orderId',
  paid_at VARCHAR(40) COMMENT 'paidAt',
  amount BIGINT COMMENT 'amount',
  PRIMARY KEY (id),
  FOREIGN KEY (payment_id) REFERENCES payments(id),
  FOREIGN KEY (order_id) REFERENCES orders(id),
  INDEX idx_payment_paid (paid_at)
) COMMENT='已核实的支付流水';

-- 售后申请与审核
CREATE TABLE IF NOT EXISTS after_sales (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  order_id VARCHAR(64) COMMENT 'orderId',
  pet_id VARCHAR(64) COMMENT 'petId',
  type VARCHAR(32) COMMENT 'type',
  requested_resolution VARCHAR(32) COMMENT 'requestedResolution',
  approved_resolution VARCHAR(32) COMMENT 'approvedResolution',
  reason LONGTEXT COMMENT 'reason',
  diagnosis_at VARCHAR(40) COMMENT 'diagnosisAt',
  requested_amount BIGINT COMMENT 'requestedAmount',
  approved_amount BIGINT COMMENT 'approvedAmount',
  review_reason LONGTEXT COMMENT 'reviewReason',
  reviewed_at VARCHAR(40) COMMENT 'reviewedAt',
  refund_id VARCHAR(64) COMMENT 'refundId',
  exchange_evidence LONGTEXT COMMENT 'exchangeEvidence，历史快照或外部调用参数JSON',
  PRIMARY KEY (id),
  FOREIGN KEY (order_id) REFERENCES orders(id),
  FOREIGN KEY (pet_id) REFERENCES pets(id),
  FOREIGN KEY (owner_id) REFERENCES users(id),
  INDEX idx_after_sales_order (order_id,status),
  INDEX idx_after_sales_owner (owner_id,created_at)
) COMMENT='售后申请与审核';

-- diagnosisFileIds
CREATE TABLE IF NOT EXISTS after_sale_diagnosis_files (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  position INT NOT NULL COMMENT '集合顺序，从0开始',
  file_id VARCHAR(64) COMMENT 'fileId',
  PRIMARY KEY (parent_id,position),
  FOREIGN KEY (parent_id) REFERENCES after_sales(id),
  FOREIGN KEY (file_id) REFERENCES file_assets(id)
) COMMENT='diagnosisFileIds';

-- evidenceFileIds
CREATE TABLE IF NOT EXISTS after_sale_evidence_files (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  position INT NOT NULL COMMENT '集合顺序，从0开始',
  file_id VARCHAR(64) COMMENT 'fileId',
  PRIMARY KEY (parent_id,position),
  FOREIGN KEY (parent_id) REFERENCES after_sales(id),
  FOREIGN KEY (file_id) REFERENCES file_assets(id)
) COMMENT='evidenceFileIds';

-- buyerConsentFileIds
CREATE TABLE IF NOT EXISTS after_sale_consent_files (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  position INT NOT NULL COMMENT '集合顺序，从0开始',
  file_id VARCHAR(64) COMMENT 'fileId',
  PRIMARY KEY (parent_id,position),
  FOREIGN KEY (parent_id) REFERENCES after_sales(id),
  FOREIGN KEY (file_id) REFERENCES file_assets(id)
) COMMENT='buyerConsentFileIds';

-- exchangeOriginalFileIds
CREATE TABLE IF NOT EXISTS exchange_original_files (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  position INT NOT NULL COMMENT '集合顺序，从0开始',
  file_id VARCHAR(64) COMMENT 'fileId',
  PRIMARY KEY (parent_id,position),
  FOREIGN KEY (parent_id) REFERENCES after_sales(id),
  FOREIGN KEY (file_id) REFERENCES file_assets(id)
) COMMENT='exchangeOriginalFileIds';

-- timeline
CREATE TABLE IF NOT EXISTS after_sale_timeline (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  position INT NOT NULL COMMENT '集合顺序，从0开始',
  event VARCHAR(40) COMMENT 'event',
  label VARCHAR(255) COMMENT 'label',
  note LONGTEXT COMMENT 'note',
  occurred_at VARCHAR(40) COMMENT 'occurredAt',
  actor_type VARCHAR(16) COMMENT 'actorType',
  PRIMARY KEY (parent_id,position),
  FOREIGN KEY (parent_id) REFERENCES after_sales(id)
) COMMENT='timeline';

-- returnRecord
CREATE TABLE IF NOT EXISTS after_sale_returns (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  id VARCHAR(64) COMMENT 'id',
  received_at VARCHAR(40) COMMENT 'receivedAt',
  condition_notes LONGTEXT COMMENT 'conditionNotes',
  operator_id VARCHAR(64) COMMENT 'operatorId',
  PRIMARY KEY (parent_id),
  FOREIGN KEY (parent_id) REFERENCES after_sales(id)
) COMMENT='returnRecord';

-- evidenceFileIds
CREATE TABLE IF NOT EXISTS after_sale_return_files (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  position INT NOT NULL COMMENT '集合顺序，从0开始',
  file_id VARCHAR(64) COMMENT 'fileId',
  PRIMARY KEY (parent_id,position),
  FOREIGN KEY (parent_id) REFERENCES after_sale_returns(parent_id),
  FOREIGN KEY (file_id) REFERENCES file_assets(id)
) COMMENT='evidenceFileIds';

-- exchange
CREATE TABLE IF NOT EXISTS exchange_plans (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  id VARCHAR(64) COMMENT 'id',
  replacement_pet_id VARCHAR(64) COMMENT 'replacementPetId',
  product_snapshot LONGTEXT COMMENT 'productSnapshot，历史快照或外部调用参数JSON',
  status VARCHAR(32) COMMENT 'status',
  confirmation_id VARCHAR(64) COMMENT 'confirmationId',
  confirmed_at VARCHAR(40) COMMENT 'confirmedAt',
  delivered_at VARCHAR(40) COMMENT 'deliveredAt',
  delivery_quarantine LONGTEXT COMMENT 'deliveryQuarantine，历史快照或外部调用参数JSON',
  PRIMARY KEY (parent_id),
  FOREIGN KEY (parent_id) REFERENCES after_sales(id),
  FOREIGN KEY (replacement_pet_id) REFERENCES pets(id)
) COMMENT='exchange';

-- 原路退款请求
CREATE TABLE IF NOT EXISTS refunds (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  refund_no VARCHAR(64) COMMENT 'refundNo',
  order_id VARCHAR(64) COMMENT 'orderId',
  after_sale_id VARCHAR(64) COMMENT 'afterSaleId',
  reason VARCHAR(100) COMMENT 'reason',
  amount BIGINT COMMENT 'amount',
  succeeded_at VARCHAR(40) COMMENT 'succeededAt',
  failure_reason LONGTEXT COMMENT 'failureReason',
  attempted BOOLEAN COMMENT 'attempted',
  provider_accepted BOOLEAN COMMENT 'providerAccepted',
  PRIMARY KEY (id),
  UNIQUE (refund_no),
  FOREIGN KEY (order_id) REFERENCES orders(id),
  FOREIGN KEY (after_sale_id) REFERENCES after_sales(id),
  CHECK (amount > 0),
  INDEX idx_refunds_order (order_id,status)
) COMMENT='原路退款请求';

-- 已核实退款流水
CREATE TABLE IF NOT EXISTS refund_transactions (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  refund_id VARCHAR(64) COMMENT 'refundId',
  amount BIGINT COMMENT 'amount',
  succeeded_at VARCHAR(40) COMMENT 'succeededAt',
  PRIMARY KEY (id),
  FOREIGN KEY (refund_id) REFERENCES refunds(id)
) COMMENT='已核实退款流水';

-- 买家现场确认记录
CREATE TABLE IF NOT EXISTS pickup_confirmations (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  confirmation_id VARCHAR(64) COMMENT 'confirmationId',
  text_version VARCHAR(32) COMMENT 'textVersion',
  text_hash VARCHAR(64) COMMENT 'textHash',
  confirmation_text LONGTEXT COMMENT 'confirmationText',
  confirmed_at VARCHAR(40) COMMENT 'confirmedAt',
  valid_until VARCHAR(40) COMMENT 'validUntil',
  method VARCHAR(16) COMMENT 'method',
  operator_id VARCHAR(64) COMMENT 'operatorId',
  delivered_at VARCHAR(40) COMMENT 'deliveredAt',
  phone_hash VARCHAR(64) COMMENT 'phoneHash',
  sms_request_id VARCHAR(64) COMMENT 'smsRequestId',
  scope VARCHAR(255) COMMENT 'scope',
  health_guarantee_expires_at VARCHAR(40) COMMENT 'healthGuaranteeExpiresAt',
  PRIMARY KEY (id),
  UNIQUE (confirmation_id)
) COMMENT='买家现场确认记录';

-- checks
CREATE TABLE IF NOT EXISTS pickup_health_checks (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  mental_state BOOLEAN COMMENT 'mentalState',
  eyes_and_nose BOOLEAN COMMENT 'eyesAndNose',
  coat BOOLEAN COMMENT 'coat',
  excretion BOOLEAN COMMENT 'excretion',
  PRIMARY KEY (parent_id),
  FOREIGN KEY (parent_id) REFERENCES pickup_confirmations(id)
) COMMENT='checks';

-- 访问统计事件
CREATE TABLE IF NOT EXISTS analytics_events (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  event_id VARCHAR(36) COMMENT 'eventId',
  event_type VARCHAR(40) COMMENT 'eventType',
  occurred_at VARCHAR(40) COMMENT 'occurredAt',
  page_path VARCHAR(200) COMMENT 'pagePath',
  pet_id VARCHAR(64) COMMENT 'petId',
  PRIMARY KEY (id),
  UNIQUE (owner_id,event_id),
  INDEX idx_analytics_pet (pet_id,event_type,occurred_at)
) COMMENT='访问统计事件';

-- properties
CREATE TABLE IF NOT EXISTS analytics_event_properties (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  page VARCHAR(16) COMMENT 'page',
  path_depth INT COMMENT 'pathDepth',
  keyword VARCHAR(50) COMMENT 'keyword',
  PRIMARY KEY (parent_id),
  FOREIGN KEY (parent_id) REFERENCES analytics_events(id)
) COMMENT='properties';

-- AI会话（待实现业务）
CREATE TABLE IF NOT EXISTS ai_sessions (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  owner_type VARCHAR(16) COMMENT 'ownerType',
  product_id VARCHAR(64) COMMENT 'productId',
  title VARCHAR(50) COMMENT 'title',
  last_message_at VARCHAR(40) COMMENT 'lastMessageAt',
  closed_at VARCHAR(40) COMMENT 'closedAt',
  had_negative_feedback BOOLEAN COMMENT 'hadNegativeFeedback',
  contacted_shop BOOLEAN COMMENT 'contactedShop',
  PRIMARY KEY (id),
  INDEX idx_ai_session_owner (owner_id,last_message_at)
) COMMENT='AI会话（待实现业务）';

-- AI消息与引用快照（待实现业务）
CREATE TABLE IF NOT EXISTS ai_messages (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  session_id VARCHAR(64) COMMENT 'sessionId',
  sequence INT COMMENT 'sequence',
  role VARCHAR(16) COMMENT 'role',
  client_message_id VARCHAR(64) COMMENT 'clientMessageId',
  content LONGTEXT COMMENT 'content',
  intent VARCHAR(40) COMMENT 'intent',
  outcome VARCHAR(32) COMMENT 'outcome',
  feedback VARCHAR(8) COMMENT 'feedback',
  disclaimer LONGTEXT COMMENT 'disclaimer',
  error_code VARCHAR(64) COMMENT 'errorCode',
  completed_at VARCHAR(40) COMMENT 'completedAt',
  sources LONGTEXT COMMENT 'sources，历史快照或外部调用参数JSON',
  product_cards LONGTEXT COMMENT 'productCards，历史快照或外部调用参数JSON',
  order_cards LONGTEXT COMMENT 'orderCards，历史快照或外部调用参数JSON',
  actions LONGTEXT COMMENT 'actions，历史快照或外部调用参数JSON',
  suggested_questions LONGTEXT COMMENT 'suggestedQuestions，历史快照或外部调用参数JSON',
  first_token_ms INT COMMENT 'firstTokenMs',
  generation_ms INT COMMENT 'generationMs',
  PRIMARY KEY (id),
  FOREIGN KEY (session_id) REFERENCES ai_sessions(id),
  UNIQUE (session_id,sequence),
  UNIQUE (session_id,client_message_id,role)
) COMMENT='AI消息与引用快照（待实现业务）';

-- 知识条目（待实现业务）
CREATE TABLE IF NOT EXISTS knowledge_entries (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  title VARCHAR(120) COMMENT 'title',
  category VARCHAR(16) COMMENT 'category',
  pet_type VARCHAR(8) COMMENT 'petType',
  format VARCHAR(16) COMMENT 'format',
  content LONGTEXT COMMENT 'content',
  question LONGTEXT COMMENT 'question',
  answer LONGTEXT COMMENT 'answer',
  source_name VARCHAR(200) COMMENT 'sourceName',
  source_url VARCHAR(2048) COMMENT 'sourceUrl',
  index_status VARCHAR(16) COMMENT 'indexStatus',
  indexed_version INT COMMENT 'indexedVersion',
  index_error_code VARCHAR(64) COMMENT 'indexErrorCode',
  deleted_at VARCHAR(40) COMMENT 'deletedAt',
  PRIMARY KEY (id),
  INDEX idx_knowledge_search (status,category,pet_type,index_status)
) COMMENT='知识条目（待实现业务）';

-- breedNames
CREATE TABLE IF NOT EXISTS knowledge_breeds (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  position INT NOT NULL COMMENT '集合顺序，从0开始',
  breed_name VARCHAR(50) COMMENT 'breedName',
  PRIMARY KEY (parent_id,position),
  FOREIGN KEY (parent_id) REFERENCES knowledge_entries(id)
) COMMENT='breedNames';

-- 知识导入及索引任务（待实现业务）
CREATE TABLE IF NOT EXISTS knowledge_jobs (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  type VARCHAR(40) COMMENT 'type',
  file_id VARCHAR(64) COMMENT 'fileId',
  total_count INT COMMENT 'totalCount',
  success_count INT COMMENT 'successCount',
  failed_count INT COMMENT 'failedCount',
  error_message LONGTEXT COMMENT 'errorMessage',
  started_at VARCHAR(40) COMMENT 'startedAt',
  completed_at VARCHAR(40) COMMENT 'completedAt',
  PRIMARY KEY (id),
  FOREIGN KEY (file_id) REFERENCES file_assets(id)
) COMMENT='知识导入及索引任务（待实现业务）';

-- errors
CREATE TABLE IF NOT EXISTS knowledge_job_errors (
  parent_id VARCHAR(64) NOT NULL COMMENT '所属主记录ID',
  position INT NOT NULL COMMENT '集合顺序，从0开始',
  source_row INT COMMENT 'sourceRow',
  message LONGTEXT COMMENT 'message',
  PRIMARY KEY (parent_id,position),
  FOREIGN KEY (parent_id) REFERENCES knowledge_jobs(id)
) COMMENT='errors';

-- AI客户端耗时（待实现业务）
CREATE TABLE IF NOT EXISTS ai_timings (
  business_key VARCHAR(190) UNIQUE COMMENT '业务幂等唯一键或凭证摘要',
  id VARCHAR(64) NOT NULL COMMENT 'id',
  owner_id VARCHAR(64) COMMENT 'ownerId',
  status VARCHAR(40) NOT NULL COMMENT 'status',
  version INT NOT NULL COMMENT 'version',
  created_at VARCHAR(40) NOT NULL COMMENT 'createdAt',
  updated_at VARCHAR(40) NOT NULL COMMENT 'updatedAt',
  event_id VARCHAR(36) COMMENT 'eventId',
  session_id VARCHAR(64) COMMENT 'sessionId',
  message_id VARCHAR(64) COMMENT 'messageId',
  first_rendered_ms INT COMMENT 'firstRenderedMs',
  completed_rendered_ms INT COMMENT 'completedRenderedMs',
  PRIMARY KEY (id),
  UNIQUE (owner_id,event_id),
  FOREIGN KEY (session_id) REFERENCES ai_sessions(id),
  FOREIGN KEY (message_id) REFERENCES ai_messages(id)
) COMMENT='AI客户端耗时（待实现业务）';

-- 单宠占用约束；订单和宠物必须真实存在。
CREATE TABLE IF NOT EXISTS pet_occupancy (
 pet_id VARCHAR(64) PRIMARY KEY,
 order_id VARCHAR(64) NOT NULL,
 FOREIGN KEY (pet_id) REFERENCES pets(id),
 FOREIGN KEY (order_id) REFERENCES orders(id)
);
-- HTTP幂等结果是不可变响应快照。
CREATE TABLE IF NOT EXISTS idempotency (
 scope_hash VARCHAR(64) PRIMARY KEY, body_hash VARCHAR(64) NOT NULL,
 resource_id VARCHAR(64), response_body LONGTEXT NOT NULL, http_status INT NOT NULL, created_at VARCHAR(40) NOT NULL
);
CREATE TABLE IF NOT EXISTS audit_log (
 id VARCHAR(64) PRIMARY KEY, actor_id VARCHAR(64) NOT NULL, action VARCHAR(100) NOT NULL,
 resource_id VARCHAR(64), occurred_at VARCHAR(40) NOT NULL
);
CREATE TABLE IF NOT EXISTS schema_migrations (
 version VARCHAR(64) PRIMARY KEY, applied_at VARCHAR(40) NOT NULL, source_count INT NOT NULL
);
