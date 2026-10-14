// 独立本机演示，不调用短信、支付或主数据库。所有动物均带 DEMO 标识。
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
const read = key => wx.getStorageSync('rongrong:demo:' + key) || []
const write = (key, value) => wx.setStorageSync('rongrong:demo:' + key, value)
function signed() { const a = require('./api').auth(); if (!a) throw new Error('请先登录'); return a }
function findPet(id) { const p = pets.find(p => p.id === id); if (!p) throw new Error('宠物不存在'); return p }
async function request(method, path, body = {}, header = {}) {
  if (path === '/home') return { shop, recommendedPets: pets.slice(0, 2), latestPets: pets, banners: [] }
  if (path === '/shop') return shop
  if (path === '/pets') {
    const items = pets.filter(p => (!body.category || p.category === body.category) && (!body.keyword || (p.name + p.breed).includes(body.keyword)) && (!body.maxPriceAmount || p.priceAmount <= body.maxPriceAmount))
    const start = ((body.page || 1) - 1) * (body.pageSize || 12)
    return { items: items.slice(start, start + (body.pageSize || 12)), total: items.length }
  }
  if (path.startsWith('/pets/')) return findPet(decodeURIComponent(path.slice(6)))
  if (path === '/auth/mini-wechat/login') {
    // 固定本机模拟身份；一次性登录 code 不能充当稳定用户标识。
    let identity = wx.getStorageSync('rongrong:demo:wechatIdentity')
    if (!identity) { identity = require('./api').uniqueId(); write('wechatIdentity', identity) }
    return { accessToken: 'DEMO-WECHAT-' + identity, expiresIn: 86400, user: { id: 'DEMO-WECHAT-USER-' + identity, nickname: '微信演示用户', phoneMasked: null } }
  }
  if (path === '/auth/logout') return {}
  const actor = signed()
  if (path === '/me') return actor.user
  const favorites = read('favorites')
  if (path === '/me/favorites' && method === 'GET') {
    const items = favorites.filter(f => f.ownerId === actor.user.id && f.active).map(f => ({ ...findPet(f.petId), available: true }))
    const start = ((body.page || 1) - 1) * (body.pageSize || 12)
    return { items: items.slice(start, start + (body.pageSize || 12)), total: items.length }
  }
  const match = path.match(/^\/me\/favorites\/([^/]+)$/)
  if (match) {
    const petId = decodeURIComponent(match[1])
    let favorite = favorites.find(f => f.ownerId === actor.user.id && f.petId === petId)
    if (method === 'PUT') {
      if (typeof body.active !== 'boolean' || Object.keys(body).some(k => k !== 'active')) throw new Error('收藏参数不正确')
      if (body.active) findPet(petId)
      if (!favorite) { favorite = { ownerId: actor.user.id, petId, active: false }; favorites.unshift(favorite) }
      favorite.active = body.active; write('favorites', favorites)
    }
    return { favorited: !!(favorite && favorite.active) }
  }
  throw new Error('演示模式暂未实现此接口')
}
module.exports = { request }
