const test = require('node:test')
const assert = require('node:assert/strict')
const store = new Map()
global.wx = { getStorageSync: k => store.get(k), setStorageSync: (k, v) => store.set(k, structuredClone(v)), removeStorageSync: k => store.delete(k) }
const config = require('../config')
const api = require('../utils/api')
const format = require('../utils/format')
// 测试直接切换本机模拟身份，不向真实接口提交客户端身份字段。
async function login(identity) { wx.setStorageSync('rongrong:demo:wechatIdentity', identity); api.saveAuth(await api.request('POST', '/auth/mini-wechat/login', {})) }
test('演示动物收藏：登录要求、重复收藏、跨账号隔离、重新登录找回和取消', async () => {
  store.clear()
  const cats = await api.request('GET', '/pets', { category: 'cat', maxPriceAmount: 200000 })
  assert.equal(cats.total, 2); assert.ok(cats.items.every(p => p.category === 'cat'))
  const p = cats.items[0], url = '/me/favorites/' + p.id
  await assert.rejects(api.request('PUT', url, { active: true }), /请先登录/)
  await login('favorite-owner')
  await api.request('PUT', url, { active: true }); await api.request('PUT', url, { active: true })
  assert.equal((await api.request('GET', '/me/favorites')).total, 1)
  assert.equal((await api.request('GET', url)).favorited, true)
  await login('favorite-other')
  assert.equal((await api.request('GET', '/me/favorites')).total, 0)
  await api.request('PUT', url, { active: false })
  await login('favorite-owner')
  assert.equal((await api.request('GET', '/me/favorites')).total, 1)
  await api.request('PUT', url, { active: false }); await api.request('PUT', url, { active: false })
  assert.equal((await api.request('GET', '/me/favorites')).total, 0)
  assert.equal(format.money(480000), '¥ 4,800')
})
test('真实收藏接口携带认证，401 清理会话，网络失败不回退演示', async () => {
  config.demo = false
  try {
    api.saveAuth({ accessToken: 'test-token', expiresIn: 60, user: { id: 'u' } })
    wx.request = opts => { assert.equal(opts.header.Authorization, 'Bearer test-token'); opts.success({ statusCode: 201, data: { code: 'OK', data: { id: 'real' } } }) }
    assert.deepEqual(await api.request('PUT', '/me/favorites/p', { active: true }), { id: 'real' })
    wx.request = opts => opts.success({ statusCode: 401, data: { code: 'UNAUTHORIZED', message: '请先登录' } })
    await assert.rejects(api.request('GET', '/me/favorites'), /请先登录/); assert.equal(api.auth(), null)
    wx.request = opts => opts.fail({})
    await assert.rejects(api.request('GET', '/home'), /连接暂时中断/)
  } finally { config.demo = true }
})
test('微信演示重复登录身份稳定，旧短信会话及跨模式会话不复用', async () => {
  store.clear(); const first = await api.request('POST', '/auth/mini-wechat/login', {})
  const next = await api.request('POST', '/auth/mini-wechat/login', {})
  assert.equal(first.user.id, next.user.id); assert.equal(first.user.phoneMasked, null)
  api.saveAuth(first); config.demo = false
  try { assert.equal(api.auth(), null) } finally { config.demo = true }
  wx.setStorageSync('rongrong:auth', { accessToken: 'old-sms', expiresAt: Date.now() + 60000 })
  assert.equal(api.auth(), null)
})
