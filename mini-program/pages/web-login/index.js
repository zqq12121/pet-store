const { request, auth, requireLogin } = require('../../utils/api')
const config = require('../../config')
// 扫码只展示待确认请求，绝不在 onLoad/onShow 中自动批准网站登录。
Page({
  data: { loading: false, busy: false, loggedIn: false, code: '', accepted: false, status: '', error: '' },
  onLoad(query) {
    try { this.ticket = decodeURIComponent(query.scene || '') } catch (_) { this.ticket = '' }
  },
  onUnload() { this.disposed = true; this.version = (this.version || 0) + 1 },
  onShow() { this.load() },
  accept(e) { this.setData({ accepted: e.detail.value.includes('yes') }) },
  async load() {
    const current = this.version = (this.version || 0) + 1
    this.setData({ loggedIn: !!auth(), error: '', loading: true })
    try {
      if (config.demo) throw new Error('网站扫码需要真实接口版本，请使用已配置的灵宠小珠')
      if (!/^[a-f0-9]{32}$/.test(this.ticket)) throw new Error('登录请求无效，请扫描电脑上的小程序码')
      const result = await request('GET', '/auth/mini-wechat/qr/' + this.ticket)
      if (!this.disposed && current === this.version) this.setData({ code: result.verificationCode, status: result.status })
    } catch (e) { if (!this.disposed && current === this.version) this.setData({ error: e.message }) }
    finally { if (!this.disposed && current === this.version) this.setData({ loading: false }) }
  },
  login() { requireLogin('/pages/web-login/index?scene=' + this.ticket) },
  async decide(e) {
    if (this.data.busy || this.data.loading || this.data.status !== 'pending' || this.data.error) return
    // 微信 dataset 可能保留布尔值或字符串，两种都只将明确 true 作为批准。
    const approved = String(e.currentTarget.dataset.approved) === 'true'
    if (!requireLogin('/pages/web-login/index?scene=' + this.ticket)) return
    if (approved && !this.data.accepted) { wx.showToast({ title: '请先核对电脑页面并勾选确认', icon: 'none' }); return }
    this.setData({ busy: true, error: '' })
    try {
      const result = await request('POST', '/auth/mini-wechat/qr/' + this.ticket + '/confirm', { approved })
      if (!this.disposed) this.setData({ status: result.status })
    } catch (e) { if (!this.disposed) this.setData({ error: e.message, loggedIn: !!auth() }) }
    finally { if (!this.disposed) this.setData({ busy: false }) }
  }
})
