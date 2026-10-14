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
test('登录解码微信导航返回地址，保留收藏动物并拒绝外部或损坏的地址', () => {
  const target = '/pages/detail/index?id=DEMO-PET-001'
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
test('微信登录在点击后启动，不要求手机号；重复点击只发一次请求并返回原动物详情', async () => {
  config.demo = false; requests = []; navigations = []; store.clear()
  let complete, calls = 0
  wx.login = options => { calls++; complete = options.success }
  wx.navigateBack = () => navigations.push({ back: true })
  global.getCurrentPages = () => [{ route: 'pages/detail/index' }, { route: 'pages/login/index' }]
  try {
    wx.request = options => { requests.push(options); options.success({ statusCode: 200, data: { code: 'OK', data: { accessToken: 'test', expiresIn: 60, user: { id: 'wechat-user', phoneMasked: null } } } }) }
    const p = page('login'); p.onLoad({ redirect: '/pages/detail/index?id=p' })
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

// 页面测试通过真实 request 包装器模拟 HTTP，不跳过会话和错误处理。
test('收藏详情要求登录；失败不伪装成功，重复点击只提交一次', async () => {
  config.demo = false; navigations = []; requests = []; store.clear()
  try {
    const p = page('detail'); p.onLoad({ id: 'p' }); p.setData({ loading: false, pet: fixture })
    await p.favorite(); assert.ok(navigations[0].url.includes('redirect=%2Fpages%2Fdetail'))
    api.saveAuth({ accessToken: 'test', expiresIn: 60, user: { id: 'u' } })
    wx.request = options => requests.push(options)
    const pending = p.favorite(); await p.favorite(); assert.equal(requests.length, 1)
    assert.deepEqual(requests[0].data, { active: true })
    requests[0].success({ statusCode: 503, data: { code: 'FAILED', message: '保存失败' } }); await pending
    assert.equal(p.data.favorited, false); assert.equal(p.data.saving, false); assert.equal(p.data.error, '保存失败')
    const retry = p.favorite(); requests[1].success({ statusCode: 200, data: { code: 'OK', data: { favorited: true } } }); await retry
    assert.equal(p.data.favorited, true)
    requests = []; wx.request = o => { requests.push(o); o.success({ statusCode: 200, data: { code: 'OK', data: o.url.endsWith('/pets/p') ? fixture : { favorited: true } } }) }
    await p.onShow(); assert.equal(p.data.favorited, true); assert.equal(requests.length, 2)
  } finally { config.demo = true }
})
test('收藏列表取消后刷新，退出清空列表，分页失败不跳页', async () => {
  config.demo = false; requests = []; store.clear()
  try {
    api.saveAuth({ accessToken: 'test', expiresIn: 60, user: { id: 'u' } })
    wx.request = o => { requests.push(o); o.success({ statusCode: 200, data: { code: 'OK', data: o.method === 'PUT' ? { favorited: false } : { items: [{ ...fixture, available: true }], total: 20 } } }) }
    const p = page('favorites'); await p.onShow(); assert.equal(p.data.pets[0].id, 'p')
    await p.remove({ currentTarget: { dataset: { id: 'p' } } }); assert.deepEqual(requests[1].data, { active: false }); assert.equal(requests[2].method, 'GET')
    wx.request = o => o.fail({}); await p.onReachBottom(); assert.equal(p.data.page, 1); assert.ok(p.data.error)
    api.clearAuth(); p.onShow(); assert.deepEqual(p.data.pets, []); assert.equal(p.data.loggedIn, false)
  } finally { config.demo = true }
})
test('已取消的业务不注册、不打包，登录不能跳回旧预约', () => {
  const fs = require('node:fs'), app = require('../app.json'), project = require('../project.config.json')
  for (const name of ['booking', 'success', 'record', 'records', 'ai', 'shop']) {
    assert.ok(!app.pages.includes('pages/' + name + '/index'))
    assert.ok(project.packOptions.ignore.some(p => p.value === 'pages/' + name))
    const login = page('login'); login.onLoad({ redirect: '/pages/' + name + '/index' }); assert.equal(login.target, '/pages/my/index')
  }
  for (const name of app.pages) {
    const content = fs.readFileSync(path.resolve(__dirname, '..', name + '.wxml'), 'utf8')
    assert.doesNotMatch(content, /预约|到店指南|养宠助手/)
  }
})
