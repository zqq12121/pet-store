const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const config = require('../config')
const store = new Map()
let definition, navigations = [], requests = []
global.Page = value => { definition = value }
global.getApp = () => ({ globalData: {} })
global.getCurrentPages = () => []
global.wx = {
  getStorageSync: k => store.get(k), setStorageSync: (k, v) => store.set(k, structuredClone(v)), removeStorageSync: k => store.delete(k),
  navigateTo: options => navigations.push(options), redirectTo: options => navigations.push(options), switchTab: options => navigations.push(options),
  showModal: options => options.success({ confirm: true }), pageScrollTo() {}
}
function page(name) {
  const file = path.resolve(__dirname, '../pages', name, 'index.js'); delete require.cache[file]; require(file)
  const instance = { ...definition, data: structuredClone(definition.data), setData(data) { Object.assign(this.data, data) } }
  return instance
}
const api = require('../utils/api')
const fixture = { id: 'p', name: '猫咪', breed: '田园猫', priceAmount: 10000, coverUrl: '/images/cat.jpg', gender: 'female', ageMonths: 4 }
const settle = () => new Promise(resolve => setImmediate(resolve))
test('登录解码微信导航返回地址，保留预约宠物并拒绝外部或损坏的地址', () => {
  const target = '/pages/booking/index?petId=DEMO-PET-001'
  // 微信 onLoad 保留 encodeURIComponent 编码；模拟器复现过登录误跳“我的”。
  const p = page('login'); p.onLoad({ redirect: encodeURIComponent(target) }); assert.equal(p.target, target)
  p.onLoad({ redirect: target }); assert.equal(p.target, target)
  p.onLoad({ redirect: encodeURIComponent('https://example.test') }); assert.equal(p.target, '/pages/my/index')
  p.onLoad({ redirect: '%ZZ' }); assert.equal(p.target, '/pages/my/index')
})
test('真实选宠请求省略空筛选，快速切换分类时旧响应不能覆盖新结果', async () => {
  config.demo = false; requests = []
  try {
    wx.request = options => requests.push(options)
    const p = page('pets'), first = p.reload()
    assert.deepEqual(requests[0].data, { page: 1, pageSize: 12 })
    p.setData({ category: 'cat' }); const second = p.reload()
    requests[1].success({ statusCode: 200, data: { code: 'OK', data: { items: [fixture], total: 1 } } }); await second
    requests[0].success({ statusCode: 200, data: { code: 'OK', data: { items: [], total: 0 } } }); await first
    assert.equal(p.data.pets[0].id, 'p'); assert.equal(p.data.total, 1)
  } finally { config.demo = true }
})
test('真实预约取消返回状态摘要后重查详情，页面保留宠物与取消状态', async () => {
  config.demo = false; requests = []
  try {
    api.saveAuth({ accessToken: 'test', expiresIn: 60, user: { id: 'u' } })
    wx.request = options => { requests.push(options); options.success({ statusCode: 200, data: { code: 'OK', data: options.method === 'POST' ? { orderId: 'o', status: 'cancelled', nextPollAfter: 0 } : { id: 'o', status: 'cancelled', product: fixture, availableActions: [], amount: 10000 } } }) }
    const p = page('record'); p.onLoad({ id: 'o' }); p.cancel(); await settle()
    assert.deepEqual(requests.map(r => r.method), ['POST', 'GET']); assert.equal(p.data.order.product.id, 'p'); assert.equal(p.data.order.canCancel, false); assert.equal(p.data.cancelling, false)
  } finally { config.demo = true }
})

test('微信登录在点击后启动，不要求手机号；重复点击只发一次请求并返回原预约', async () => {
  config.demo = false; requests = []; navigations = []; store.clear()
  let complete, calls = 0
  wx.login = options => { calls++; complete = options.success }
  wx.navigateBack = () => navigations.push({ back: true })
  global.getCurrentPages = () => [{ route: 'pages/booking/index' }, { route: 'pages/login/index' }]
  try {
    wx.request = options => { requests.push(options); options.success({ statusCode: 200, data: { code: 'OK', data: { accessToken: 'test', expiresIn: 60, user: { id: 'wechat-user', phoneMasked: null } } } }) }
    const p = page('login'); p.onLoad({ redirect: '/pages/booking/index?petId=p' })
    assert.equal(calls, 0); await p.login(); assert.equal(calls, 0)
    p.setData({ accepted: true }); const pending = p.login(); await p.login(); assert.equal(calls, 1)
    complete({ code: 'one-use-code' }); await pending
    assert.deepEqual(requests[0].data, { code: 'one-use-code' })
    assert.ok(requests[0].url.endsWith('/auth/mini-wechat/login'))
    assert.deepEqual(navigations, [{ back: true }]); assert.equal(p.data.busy, false)
    assert.equal(api.auth().user.id, 'wechat-user')
  } finally { config.demo = true; global.getCurrentPages = () => [] }
})
test('微信登录失败、空凭证、后端拒绝与页面离开不保存身份或跳转', async () => {
  config.demo = false; navigations = []; store.clear(); requests = []
  try {
    wx.request = options => { requests.push(options); options.success({ statusCode: 401, data: { code: 'WECHAT_AUTH_INVALID', message: '微信登录凭证已失效' } }) }
    for (const callback of [o => o.fail({}), o => o.success({}), o => o.success({ code: 'expired' })]) {
      wx.login = callback; const p = page('login'); p.onLoad({}); p.setData({ accepted: true }); await p.login()
      assert.ok(p.data.error); assert.equal(p.data.busy, false); assert.equal(api.auth(), null)
    }
    assert.equal(requests.length, 1)
    let complete; wx.login = o => { complete = o.success }
    const p = page('login'); p.onLoad({}); p.setData({ accepted: true }); const pending = p.login()
    p.onUnload(); complete({ code: 'valid' }); await pending
    assert.equal(requests.length, 1); assert.equal(navigations.length, 0)
    // 后端响应晚于卸载时也不能保存会话。
    let response; wx.login = o => o.success({ code: 'valid' }); wx.request = o => { response = o.success }
    const q = page('login'); q.onLoad({}); q.setData({ accepted: true }); const later = q.login(); await settle()
    q.onUnload(); response({ statusCode: 200, data: { code: 'OK', data: { accessToken: 'late', expiresIn: 60, user: { id: 'u' } } } }); await later
    assert.equal(api.auth(), null); assert.equal(navigations.length, 0)
  } finally { config.demo = true }
})
