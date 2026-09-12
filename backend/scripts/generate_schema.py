#!/usr/bin/env python3
"""按接口 v0.9 定义关系模型；生成 SQL 与 Java 持久层使用的白名单字段映射。"""
import json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
models=[]
def fields(text):
    result=[]
    for item in text.split():
        name,typ=item.split(':',1)
        types={'id':'VARCHAR(64)','s':'VARCHAR(255)','text':'LONGTEXT','time':'VARCHAR(40)','date':'DATE','int':'INT','money':'BIGINT','bool':'BOOLEAN','json':'LONGTEXT','decimal':'DECIMAL(12,2)','coord':'DECIMAL(12,8)'}
        result.append({'field':name,'column':re.sub(r'(?<!^)(?=[A-Z])','_',name).lower(),'sql':types.get(typ,typ),'json':typ=='json'})
    return result
def child(table,path,cols,mode='list',children=None,scalar=None):
    return {'table':table,'path':path,'fields':fields(cols),'mode':mode,'children':children or [],'scalar':scalar}
def ids(table,path):return child(table,path,'fileId:id',scalar='fileId')
def model(kind,table,label,cols,children=None,constraints=None):
    common='id:id ownerId:id status:VARCHAR(40) version:'+('VARCHAR(32)' if kind=='agreement' else 'int')+' createdAt:time updatedAt:time'
    obj={'kind':kind,'table':table,'label':label,'fields':fields(common+' '+cols),'children':children or [],'constraints':constraints or []}
    models.append(obj);return obj
model('admin','admins','管理员','username:VARCHAR(32) passwordHash:s',None,['UNIQUE (username)'])
model('user','users','买家用户','phone:VARCHAR(11) nickname:VARCHAR(30) avatarUrl:VARCHAR(2048)',constraints=['UNIQUE (phone)'])
model('wechat','wechat_accounts','微信账号绑定','appId:VARCHAR(64) openid:VARCHAR(128)',constraints=['UNIQUE (app_id,openid)','UNIQUE (app_id,owner_id)','FOREIGN KEY (owner_id) REFERENCES users(id)'])
model('wechat_openid','wechat_openid_claims','微信身份唯一归属','appId:VARCHAR(64)',constraints=['FOREIGN KEY (owner_id) REFERENCES users(id)'])
model('session','auth_sessions','可撤销登录会话','role:VARCHAR(16) expiresAt:time')
model('file','file_assets','文件元数据及访问权限','orderId:id originalName:s mimeType:VARCHAR(100) sizeBytes:money purpose:VARCHAR(40) visibility:VARCHAR(16) publicUrl:VARCHAR(2048)')
model('shop','shops','门店','name:VARCHAR(80) address:VARCHAR(200) latitude:coord longitude:coord coordinateSystem:VARCHAR(16) phone:VARCHAR(200) wechat:VARCHAR(200) businessHours:VARCHAR(200) pickupInstructions:text paymentTimeoutMinutes:int pickupRetentionHours:int exchangeEnabled:bool',[
 child('shop_banners','banners','id:id title:VARCHAR(80) imageFileId:id linkType:VARCHAR(16) petId:id noticeText:text sortOrder:int enabled:bool')])
model('pet','pets','宠物个体档案','name:VARCHAR(80) category:VARCHAR(8) breed:VARCHAR(50) gender:VARCHAR(8) priceAmount:money ageMonths:int birthDate:date weightKg:decimal color:VARCHAR(30) vaccineStatus:VARCHAR(500) dewormStatus:VARCHAR(500) description:text feedingNotes:text healthDescription:text videoFileId:id isRecommended:bool recommendationOrder:int activeOrderId:id reviewedAt:time lastSaleReviewNote:text publishedAt:time',[
 ids('pet_images','imageFileIds'),child('pet_personality_tags','personalityTags','tag:VARCHAR(20)',scalar='tag'),
 child('pet_quarantine_certificates','quarantine','certificateNo:VARCHAR(100) validUntil:date','object',[
 ids('pet_quarantine_public_files','publicImageFileIds'),ids('pet_quarantine_original_files','originalFileIds')])],
 ['CHECK (price_amount > 0)','CHECK (category IN (\'cat\',\'dog\'))','CHECK (gender IN (\'male\',\'female\'))','CHECK (age_months BETWEEN 0 AND 360)','CHECK (weight_kg > 0 AND weight_kg <= 200)','CHECK (status IN (\'off\',\'on_sale\',\'reserved\',\'sold\'))','INDEX idx_pets_filter (status,category,price_amount)','INDEX idx_pets_breed (category,breed)','INDEX idx_pets_published (published_at,id)'])
model('agreement','agreement_versions','协议不可变版本','type:VARCHAR(32) title:VARCHAR(100) content:text contentFormat:VARCHAR(16) contentHash:VARCHAR(64) healthGuaranteeDays:int publishedAt:time',constraints=['UNIQUE (type,version)'])
model('order','orders','单宠订单','orderNo:VARCHAR(64) petId:id amount:money refundedAmount:money currency:VARCHAR(3) contactName:VARCHAR(30) contactPhone:VARCHAR(11) remark:VARCHAR(500) expiresAt:time pickupRetentionHours:int healthGuaranteeDays:int paidAt:time pickupDeadlineAt:time completedAt:time healthGuaranteeExpiresAt:time pickedUpAt:time closedAt:time cancelledAt:time cancelReason:VARCHAR(500) refundPreviousStatus:VARCHAR(40)',[
 child('order_snapshots','snapshots','product:json priceSnapshot:json productSnapshot:json shopSnapshot:json agreement:json','embedded'),
 child('order_payment_state','payment','paymentId:id status:VARCHAR(40) paidAmount:money paidAt:time','object'),
 child('pickup_credentials','pickup','code:VARCHAR(8) qrPayload:VARCHAR(128) expiresAt:time buyerConfirmedAt:time','object'),
 child('order_delivery_evidence','delivery','pickupEvidence:json deliveryQuarantine:json','embedded'),
 ids('order_delivery_original_files','deliveryOriginalFileIds')],
 ['UNIQUE (order_no)','FOREIGN KEY (owner_id) REFERENCES users(id)','FOREIGN KEY (pet_id) REFERENCES pets(id)','CHECK (amount > 0)','CHECK (refunded_amount >= 0 AND refunded_amount <= amount)','INDEX idx_orders_owner (owner_id,status,created_at)','INDEX idx_orders_pet (pet_id)','INDEX idx_orders_expiry (status,expires_at)','INDEX idx_orders_contact (contact_phone,created_at)'])
model('payment','payments','支付请求及对账状态','outTradeNo:VARCHAR(64) orderId:id scene:VARCHAR(16) expiresAt:time providerCreated:bool providerAttempted:bool lastQueryAt:time transactionId:VARCHAR(128) lastProviderError:VARCHAR(100) h5Url:VARCHAR(2048) prepayId:VARCHAR(128) invokeParams:json',constraints=['UNIQUE (out_trade_no)','UNIQUE (transaction_id)','FOREIGN KEY (order_id) REFERENCES orders(id)','INDEX idx_payments_order (order_id)','INDEX idx_payments_state (status,last_query_at)'])
model('payment_fact','payment_transactions','已核实的支付流水','paymentId:id orderId:id paidAt:time amount:money',constraints=['FOREIGN KEY (payment_id) REFERENCES payments(id)','FOREIGN KEY (order_id) REFERENCES orders(id)','INDEX idx_payment_paid (paid_at)'])
model('after_sale','after_sales','售后申请与审核','orderId:id petId:id type:VARCHAR(32) requestedResolution:VARCHAR(32) approvedResolution:VARCHAR(32) reason:text diagnosisAt:time requestedAmount:money approvedAmount:money reviewReason:text reviewedAt:time refundId:id exchangeEvidence:json',[
 ids('after_sale_diagnosis_files','diagnosisFileIds'),ids('after_sale_evidence_files','evidenceFileIds'),ids('after_sale_consent_files','buyerConsentFileIds'),ids('exchange_original_files','exchangeOriginalFileIds'),
 child('after_sale_timeline','timeline','event:VARCHAR(40) label:s note:text occurredAt:time actorType:VARCHAR(16)'),
 child('after_sale_returns','returnRecord','id:id receivedAt:time conditionNotes:text operatorId:id','object',[ids('after_sale_return_files','evidenceFileIds')]),
 child('exchange_plans','exchange','id:id replacementPetId:id productSnapshot:json status:VARCHAR(32) confirmationId:id confirmedAt:time deliveredAt:time deliveryQuarantine:json','object')],
 ['FOREIGN KEY (order_id) REFERENCES orders(id)','FOREIGN KEY (pet_id) REFERENCES pets(id)','FOREIGN KEY (owner_id) REFERENCES users(id)','INDEX idx_after_sales_order (order_id,status)','INDEX idx_after_sales_owner (owner_id,created_at)'])
model('refund','refunds','原路退款请求','refundNo:VARCHAR(64) orderId:id afterSaleId:id reason:VARCHAR(100) amount:money succeededAt:time failureReason:text attempted:bool providerAccepted:bool',constraints=['UNIQUE (refund_no)','FOREIGN KEY (order_id) REFERENCES orders(id)','FOREIGN KEY (after_sale_id) REFERENCES after_sales(id)','CHECK (amount > 0)','INDEX idx_refunds_order (order_id,status)'])
model('refund_fact','refund_transactions','已核实退款流水','refundId:id amount:money succeededAt:time',constraints=['FOREIGN KEY (refund_id) REFERENCES refunds(id)'])
model('confirmation','pickup_confirmations','买家现场确认记录','confirmationId:id textVersion:VARCHAR(32) textHash:VARCHAR(64) confirmationText:text confirmedAt:time validUntil:time method:VARCHAR(16) operatorId:id deliveredAt:time phoneHash:VARCHAR(64) smsRequestId:id scope:VARCHAR(255) healthGuaranteeExpiresAt:time',[
 child('pickup_health_checks','checks','mentalState:bool eyesAndNose:bool coat:bool excretion:bool','object')],['UNIQUE (confirmation_id)'])
model('event','analytics_events','访问统计事件','eventId:VARCHAR(36) eventType:VARCHAR(40) occurredAt:time pagePath:VARCHAR(200) petId:id',[
 child('analytics_event_properties','properties','page:VARCHAR(16) pathDepth:int keyword:VARCHAR(50)','object')],['UNIQUE (owner_id,event_id)','INDEX idx_analytics_pet (pet_id,event_type,occurred_at)'])
# PRD P0 的 AI/知识库结构；当前尚无对应业务接口，不填充虚构数据。
model('ai_session','ai_sessions','AI会话（待实现业务）','ownerType:VARCHAR(16) productId:id title:VARCHAR(50) lastMessageAt:time closedAt:time hadNegativeFeedback:bool contactedShop:bool',constraints=['INDEX idx_ai_session_owner (owner_id,last_message_at)'])
model('ai_message','ai_messages','AI消息与引用快照（待实现业务）','sessionId:id sequence:int role:VARCHAR(16) clientMessageId:VARCHAR(64) content:text intent:VARCHAR(40) outcome:VARCHAR(32) feedback:VARCHAR(8) disclaimer:text errorCode:VARCHAR(64) completedAt:time sources:json productCards:json orderCards:json actions:json suggestedQuestions:json firstTokenMs:int generationMs:int',constraints=['FOREIGN KEY (session_id) REFERENCES ai_sessions(id)','UNIQUE (session_id,sequence)','UNIQUE (session_id,client_message_id,role)'])
model('knowledge','knowledge_entries','知识条目（待实现业务）','title:VARCHAR(120) category:VARCHAR(16) petType:VARCHAR(8) format:VARCHAR(16) content:text question:text answer:text sourceName:VARCHAR(200) sourceUrl:VARCHAR(2048) indexStatus:VARCHAR(16) indexedVersion:int indexErrorCode:VARCHAR(64) deletedAt:time',[
 child('knowledge_breeds','breedNames','breedName:VARCHAR(50)',scalar='breedName')],['INDEX idx_knowledge_search (status,category,pet_type,index_status)'])
model('knowledge_job','knowledge_jobs','知识导入及索引任务（待实现业务）','type:VARCHAR(40) fileId:id totalCount:int successCount:int failedCount:int errorMessage:text startedAt:time completedAt:time',[
 child('knowledge_job_errors','errors','sourceRow:int message:text')])
model('ai_timing','ai_timings','AI客户端耗时（待实现业务）','eventId:VARCHAR(36) sessionId:id messageId:id firstRenderedMs:int completedRenderedMs:int',constraints=['UNIQUE (owner_id,event_id)','FOREIGN KEY (session_id) REFERENCES ai_sessions(id)','FOREIGN KEY (message_id) REFERENCES ai_messages(id)'])

def ddl(m,parent=None):
    cols=[]
    if parent:
        cols=['parent_id VARCHAR(64) NOT NULL COMMENT \'所属主记录ID\'']
        if m['mode']=='list':cols.append('position INT NOT NULL COMMENT \'集合顺序，从0开始\'')
    else:cols=['business_key VARCHAR(190) UNIQUE COMMENT \'业务幂等唯一键或凭证摘要\'']
    for f in m['fields']:
        required=' NOT NULL' if not parent and f['field'] in ('id','status','version','createdAt','updatedAt') else ''
        cols.append(f"{f['column']} {f['sql']}{required} COMMENT '{f['field']}"+('，历史快照或外部调用参数JSON' if f['json'] else '')+"'")
    if parent:
        cols+=['PRIMARY KEY (parent_id'+(',position' if m['mode']=='list' else '')+')',f"FOREIGN KEY (parent_id) REFERENCES {parent['table']}({'id' if 'kind' in parent else 'parent_id'})"]
    else:cols+=['PRIMARY KEY (id)']+m['constraints']
    # 附件引用由专用外键保护；空引用及测试未录入字段仍允许 NULL。
    if any(f['column']=='file_id' for f in m['fields']):cols.append('FOREIGN KEY (file_id) REFERENCES file_assets(id)')
    if m['table']=='exchange_plans':cols.append('FOREIGN KEY (replacement_pet_id) REFERENCES pets(id)')
    label=m.get('label',m.get('path',''))
    result=[f"-- {label}\nCREATE TABLE IF NOT EXISTS {m['table']} (\n  "+',\n  '.join(cols)+f"\n) COMMENT='{label}';\n"]
    for c in m['children']:result+=ddl(c,m)
    return result

def generate():
    statements=['-- 关系模型 v2：金额为分；ISO-8601时间保留时区文本；出生/检疫日期使用DATE。\n-- 不包含 resources 通用业务表；旧库由显式迁移工具处理，不在启动时删除数据。\nCREATE TABLE IF NOT EXISTS business_lock (id INT PRIMARY KEY);\nINSERT INTO business_lock (id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM business_lock WHERE id=1);\n']
    for m in models:statements+=ddl(m)
    statements+=['''-- 单宠占用约束；订单和宠物必须真实存在。
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
''']
    (ROOT/'common/src/main/resources/schema.sql').write_text('\n'.join(statements))
    (ROOT/'common/src/main/resources/relational-model.json').write_text(json.dumps({'models':models},ensure_ascii=False,indent=2)+'\n')
if __name__=='__main__':generate()
