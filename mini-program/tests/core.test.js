const test = require('node:test')
const assert = require('node:assert/strict')
const store = new Map()
global.wx = { getStorageSync: k => store.get(k), setStorageSync: (k, v) => store.set(k, structuredClone(v)), removeStorageSync: k => store.delete(k) }
const config = require('../config')
const api = require('../utils/api')
const format = require('../utils/format')
const { parseReply } = require('../utils/sse')
// 测试直接切换本机模拟身份，不向真实接口提交客户端身份字段。
async function login(identity) { wx.setStorageSync('rongrong:demo:wechatIdentity', identity); api.saveAuth(await api.request('POST', '/auth/mini-wechat/login', {})) }
test('演示预约完整流程：宠物筛选、登录、协议校验、重复提交与跨账号隔离', async () => {
  store.clear()
  const cats = await api.request('GET', '/pets', { category: 'cat', maxPriceAmount: 200000 })
  assert.equal(cats.total, 2); assert.ok(cats.items.every(p => p.category === 'cat'))
  const p = cats.items[1]
  await assert.rejects(api.request('POST', '/orders/preview', { petId: p.id }), /请先登录/)
  await login('13800008000')
  const preview = await api.request('POST', '/orders/preview', { petId: p.id })
  const date = format.beijing(Date.now() + 86400000)
  const body = { petId: p.id, productVersion: preview.productVersion, expectedAmount: preview.amount, contactName: '测试同学', contactPhone: '13800008000', visitAt: date + 'T10:00:00+08:00', remark: '', agreementVersion: preview.agreementVersion, agreementContentHash: preview.agreementContentHash, agreementAccepted: true }
  await assert.rejects(api.request('POST', '/orders', { ...body, agreementAccepted: false }), /协议已更新/)
  await assert.rejects(api.request('POST', '/orders', { ...body, contactPhone: '123' }), /到店联系号码/)
  const key = api.mutationKey(), h = { 'Idempotency-Key': key('/orders', body) }
  const o = await api.request('POST', '/orders', body, h), replay = await api.request('POST', '/orders', body, h)
  assert.equal(api.auth().user.phoneMasked, null); assert.equal(o.contactPhoneMasked, '138****8000'); assert.equal(o.id, replay.id); assert.equal(o.product.id, p.id); assert.equal(o.status, 'pending_confirmation')
  assert.equal((await api.request('GET', '/orders')).total, 1)
  await assert.rejects(api.request('POST', '/orders', body, { 'Idempotency-Key': 'new' }), /限 1 个有效预约/)
  await login('13900008000')
  assert.equal((await api.request('GET', '/orders')).total, 0)
  await assert.rejects(api.request('GET', '/orders/' + o.id), /不存在/)
  await login('13800008000')
  const cancelled = await api.request('POST', '/orders/' + o.id + '/cancel', {})
  assert.equal(cancelled.status, 'cancelled'); assert.deepEqual((await api.request('GET', '/orders/' + o.id)).availableActions, [])
})
test('预约输入按北京时间解释，幂等键仅在内容改变时更新', () => {
  assert.equal(format.beijing('2026-10-10T17:00:00Z'), '2026-10-11')
  assert.equal(format.visitText('2026-10-11T02:00:00Z'), '10月11日 10:00')
  assert.equal(format.money(480000), '¥ 4,800')
  const key = api.mutationKey(), a = key('/orders', { petId: 'a' })
  assert.equal(a, key('/orders', { petId: 'a' })); assert.notEqual(a, key('/orders', { petId: 'b' }))
})
test('真实接口携带认证与幂等头，401 清理会话，网络失败不回退演示', async () => {
  config.demo = false
  try {
    api.saveAuth({ accessToken: 'test-token', expiresIn: 60, user: { id: 'u' } })
    wx.request = opts => { assert.equal(opts.header.Authorization, 'Bearer test-token'); assert.equal(opts.header['Idempotency-Key'], 'key'); opts.success({ statusCode: 201, data: { code: 'OK', data: { id: 'real' } } }) }
    assert.deepEqual(await api.request('POST', '/orders', {}, { 'Idempotency-Key': 'key' }), { id: 'real' })
    wx.request = opts => opts.success({ statusCode: 401, data: { code: 'UNAUTHORIZED', message: '请先登录' } })
    await assert.rejects(api.request('GET', '/orders'), /请先登录/); assert.equal(api.auth(), null)
    wx.request = opts => opts.fail({})
    await assert.rejects(api.request('GET', '/home'), /连接暂时中断/)
  } finally { config.demo = true }
})
test('SSE 中文回复、来源和卡片解析；截断和服务器失败均不伪装完成', () => {
  const sse = (event, data) => `event: ${event}\r\ndata: ${JSON.stringify(data)}\r\n\r\n`
  const text = sse('meta', { assistantMessageId: 'm' }) + sse('delta', { text: '你好，🐱\n' }) + sse('sources', { items: [{ title: '照护指南' }] }) + sse('cards', { productCards: [{ id: 'p' }] }) + sse('done', { disclaimer: 'AI 生成，仅供参考' })
  const reply = parseReply(text); assert.equal(reply.content, '你好，🐱\n'); assert.equal(reply.sources[0].title, '照护指南'); assert.equal(reply.productCards[0].id, 'p')
  assert.throws(() => parseReply(sse('delta', { text: '部分回复' })), /连接中断/)
  assert.throws(() => parseReply(sse('error', { message: '暂不可用', retryable: false })), /暂不可用/)
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
