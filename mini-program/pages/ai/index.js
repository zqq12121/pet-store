const config = require('../../config')
const { request, auth, headers, clearAuth, uniqueId } = require('../../utils/api')
const { parseReply } = require('../../utils/sse')
const { pet } = require('../../utils/format')
const { open, switchTab } = require('../../utils/page')
Page({
  data: { demo: config.demo, input: '', messages: [], sending: false, loading: false, error: '', ready: false, canRetry: false },
  onLoad(q) { this.petId = q.petId || '' },
  onShow() {
    const owner = auth() ? auth().user.id : 'guest'
    // 登录状态改变后创建新会话，避免把游客会话误当买家会话查询。
    if (owner !== this.owner) { this.owner = owner; this.openSession() }
  },
  onUnload() { this.disposed = true; if (this.task) this.task.abort() },
  async credentials() {
    if (auth()) return headers()
    if (!this.guest || Date.parse(this.guest.expiresAt) <= Date.now()) this.guest = await request('POST', '/guest-sessions', {})
    return { 'X-Guest-Token': this.guest.guestToken }
  },
  async ai(path, method = 'GET', body) { return request(method, path, body, await this.credentials()) },
  async openSession() {
    this.setData({ loading: true, ready: false, messages: [], error: '', canRetry: false })
    this.pending = null
    try { if (!config.demo) { const r = await this.ai('/ai/sessions', 'POST', this.petId ? { productId: this.petId } : {}); this.sid = r.id } this.setData({ ready: true }) }
    catch (e) { this.setData({ error: e.message }) }
    finally { this.setData({ loading: false }) }
  },
  input(e) { this.setData({ input: e.detail.value }) },
  suggestion(e) { this.send(e.currentTarget.dataset.question) },
  submit() { this.send(this.data.input) },
  retry() { this.send('', true) },
  async send(text, retry = false) {
    if (this.data.sending || !this.data.ready || (!retry && !text.trim())) return
    let messages = this.data.messages.slice()
    if (!retry) { this.pending = { clientMessageId: uniqueId(), content: text.trim() }; messages.push({ id: this.pending.clientMessageId, role: 'user', content: this.pending.content }); this.setData({ input: '' }) }
    if (!this.pending) return
    this.setData({ sending: true, canRetry: false, error: '', messages })
    try {
      let answer
      if (config.demo) {
        answer = { id: uniqueId(), role: 'assistant', status: 'completed', content: /预约/.test(this.pending.content) ? '演示预约请前往“我的预约”查看。真实模式会按当前登录身份查询本人信息。' : '从这 3 件小事开始：\n\n1. 准备食盆、水碗、牵引用品和固定休息区。\n2. 和家人确认照护安排，了解时间与费用投入。\n3. 到店观察性格、核验宠物档案，再决定是否合适。', sources: [{ title: '新手养宠准备清单', sourceName: '本地示例回复' }], disclaimer: '本地示例回复，未调用 DeepSeek。健康问题请咨询兽医。' }
      } else {
        const extra = await this.credentials()
        const text = await new Promise((resolve, reject) => {
          this.task = wx.request({ url: config.apiBase + '/ai/sessions/' + encodeURIComponent(this.sid) + '/messages', method: 'POST', data: this.pending, dataType: 'text', timeout: 120000,
            header: { 'Content-Type': 'application/json', Accept: 'text/event-stream', ...extra },
            success(res) { if (res.statusCode === 401) clearAuth(); if (res.statusCode >= 200 && res.statusCode < 300) resolve(res.data); else { let message = '智能助手暂不可用'; try { message = JSON.parse(res.data).message || message } catch (_) { /* 非 JSON 错误响应使用统一提示。 */ } reject(new Error(message)) } },
            fail() { reject(new Error('回复连接中断，请重试本次问题')) }
          })
        })
        answer = { ...parseReply(text), role: 'assistant' }
        answer.productCards = answer.productCards.map(pet)
      }
      if (this.disposed) return
      messages.push(answer); this.pending = null; this.setData({ messages })
      wx.pageScrollTo({ scrollTop: 100000, duration: 0 })
    } catch (e) { if (!this.disposed) this.setData({ error: e.message, canRetry: e.retryable !== false }); if (e.retryable === false) this.pending = null }
    finally { if (!this.disposed) this.setData({ sending: false }) }
  },
  shop() { open('/pages/shop/index') },
  record(e) { open('/pages/record/index?id=' + encodeURIComponent(e.currentTarget.dataset.id)) },
  action(e) {
    const { type, id } = e.currentTarget.dataset
    if (type === 'login') open('/pages/login/index?redirect=' + encodeURIComponent('/pages/ai/index'))
    if (type === 'view_products') switchTab('/pages/pets/index')
    if (type === 'contact_shop') this.shop()
    if (type === 'book_appointment' && id) open('/pages/booking/index?petId=' + encodeURIComponent(id))
  }
})
