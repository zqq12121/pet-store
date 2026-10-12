// 独立本机演示，不调用短信、支付或主数据库。所有记录均带 DEMO 标识。
const shop = { id: 'DEMO-SHOP', name: '茸茸星球', address: '星球路 18 号（虚构地址，仅供练习）', phone: '', wechat: '', latitude: null, longitude: null, businessHours: '每天 08:00–17:00', pickupInstructions: '请携带身份证到店，先核验宠物档案再决定。' }
const pets = [
  ['001', '奶糖', 'dog', '柯基', 'corgi', 480000, 4, 'male'],
  ['002', '年糕', 'cat', '田园猫', 'cat', 180000, 5, 'female'],
  ['003', '松饼', 'dog', '金毛', 'golden', 360000, 3, 'male'],
  ['004', '橘子', 'cat', '田园猫', 'ginger', 150000, 6, 'female']
].map(([id, name, category, breed, img, priceAmount, ageMonths, gender]) => ({
  id: 'DEMO-PET-' + id, name, category, breed, coverUrl: '/assets/' + img + '.jpg', priceAmount, ageMonths, gender,
  status: 'on_sale', version: 1, purchaseAllowed: true, isRecommended: true, shop,
  description: '活泼亲人，喜欢在你身边蹦蹦跳跳。', healthDescription: '模拟资料，仅供测试。疫苗、驱虫与健康记录请到店核验。',
  vaccineStatus: '模拟疫苗记录', dewormStatus: '模拟驱虫记录', feedingNotes: '到家后保持原有饮食，逐步适应。',
  images: [{ url: '/assets/' + img + '.jpg' }], quarantine: { certificateNo: 'DEMO-CERT-' + id, images: [] }
}))
const agreement = { title: '到店预约须知与风险说明（模拟资料，仅供测试）', version: 'DEMO-v1', contentHash: 'DEMO-hash-v1', content: '本项目仅供练习，不正式运营。\n预约仅安排到店时间，线上不收款。\n提交后等待门店确认，每个账号限一个有效预约。\n请携带身份证，并在到店时核验宠物健康、疫苗及检疫资料。\n本演示不构成真实交易或健康保证。' }
const read = key => wx.getStorageSync('rongrong:demo:' + key) || []
const write = (key, value) => wx.setStorageSync('rongrong:demo:' + key, value)
function signed() { const a = require('./api').auth(); if (!a) throw new Error('请先登录'); return a }
function findPet(id) { const p = pets.find(p => p.id === id); if (!p) throw new Error('宠物不存在'); return p }
function view(o) { return { ...o, statusText: o.status === 'cancelled' ? '已取消' : '待门店确认', availableActions: o.status === 'pending_confirmation' ? ['cancel'] : [] } }
async function request(method, path, body = {}, header = {}) {
  if (path === '/home') return { shop, recommendedPets: pets.slice(0, 2), latestPets: pets, banners: [] }
  if (path === '/shop') return shop
  if (path === '/pets') {
    const items = pets.filter(p => (!body.category || p.category === body.category) && (!body.keyword || (p.name + p.breed).includes(body.keyword)) && (!body.maxPriceAmount || p.priceAmount <= body.maxPriceAmount))
    const start = ((body.page || 1) - 1) * (body.pageSize || 12)
    return { items: items.slice(start, start + (body.pageSize || 12)), total: items.length }
  }
  if (path.startsWith('/pets/')) return findPet(decodeURIComponent(path.slice(6)))
  if (path === '/agreements/current') return agreement
  if (path === '/auth/mini-wechat/login') {
    // 固定本机模拟身份；一次性登录 code 不能充当稳定用户标识。
    let identity = wx.getStorageSync('rongrong:demo:wechatIdentity')
    if (!identity) { identity = require('./api').uniqueId(); write('wechatIdentity', identity) }
    return { accessToken: 'DEMO-WECHAT-' + identity, expiresIn: 86400, user: { id: 'DEMO-WECHAT-USER-' + identity, nickname: '微信演示用户', phoneMasked: null } }
  }
  if (path === '/auth/logout') return {}
  const actor = signed()
  if (path === '/me') return actor.user
  const orders = read('orders')
  if (path === '/orders/preview') {
    const product = findPet(body.petId)
    return { product, amount: product.priceAmount, productVersion: product.version, shop, purchaseAllowed: true, agreementVersion: agreement.version, agreementContentHash: agreement.contentHash }
  }
  if (path === '/orders' && method === 'POST') {
    const key = header['Idempotency-Key'], replay = orders.find(o => o.key === key && o.owner === actor.user.id)
    if (replay) { if (replay.fingerprint !== JSON.stringify(body)) throw new Error('幂等键内容不一致'); return view(replay) }
    if (orders.some(o => o.owner === actor.user.id && o.status === 'pending_confirmation')) throw new Error('每个账号限 1 个有效预约')
    const p = findPet(body.petId)
    if (!/^1[3-9]\d{9}$/.test(body.contactPhone)) throw new Error('请填写正确的到店联系号码')
    if (!body.agreementAccepted || body.agreementContentHash !== agreement.contentHash || body.productVersion !== p.version || body.expectedAmount !== p.priceAmount) throw new Error('宠物或协议已更新，请刷新')
    const visit = Date.parse(body.visitAt), hour = new Date(visit + 8 * 3600000).getUTCHours()
    if (!body.contactName.trim() || visit <= Date.now() || visit > Date.now() + 7 * 86400000 || hour < 8 || hour >= 17) throw new Error('请选择未来 7 天内营业时间的到店时间')
    const id = 'DEMO-APPT-' + require('./api').uniqueId()
    const o = { ...body, id, orderNo: id, owner: actor.user.id, key, fingerprint: JSON.stringify(body), status: 'pending_confirmation', product: p, amount: p.priceAmount, shopSnapshot: shop, contactPhoneMasked: body.contactPhone.slice(0, 3) + '****' + body.contactPhone.slice(-4), appointment: { visitAt: body.visitAt }, createdAt: new Date().toISOString() }
    orders.unshift(o); write('orders', orders); return view(o)
  }
  if (path === '/orders') {
    const items = orders.filter(o => o.owner === actor.user.id && (!body.tab || body.tab === 'all' || body.tab === o.status)).map(view)
    const start = ((body.page || 1) - 1) * (body.pageSize || 12)
    return { items: items.slice(start, start + (body.pageSize || 12)), total: items.length }
  }
  const match = path.match(/^\/orders\/([^/]+)(\/cancel)?$/)
  if (match) {
    const o = orders.find(o => o.id === decodeURIComponent(match[1]) && o.owner === actor.user.id)
    if (!o) throw new Error('预约不存在')
    if (match[2]) { if (o.status !== 'pending_confirmation') throw new Error('当前预约不可取消'); o.status = 'cancelled'; write('orders', orders); return { orderId: o.id, status: o.status, nextPollAfter: 0 } }
    return view(o)
  }
  throw new Error('演示模式暂未实现此接口')
}
module.exports = { request }
