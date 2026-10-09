-- 仅供本练习项目配置门店基础资料；不修改账号、宠物、订单或轮播图。
-- 应用前先备份 shops 中 shop_1 的原始记录，已有订单快照继续保留。
START TRANSACTION;
UPDATE shops
SET name = '茸茸星球',
    address = '星绒市云朵区毛球路 27 号（虚构地址，仅供练习）',
    phone = '',
    wechat = '',
    latitude = NULL,
    longitude = NULL,
    business_hours = '每天 08:00-17:00',
    pickup_instructions = '请携带身份证到店。',
    version = version + 1,
    updated_at = DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-%dT%H:%i:%sZ')
WHERE id = 'shop_1';
COMMIT;
