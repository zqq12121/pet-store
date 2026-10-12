const { request, auth, clearAuth } = require('../../utils/api')
const { load, open, switchTab } = require('../../utils/page')
Page({
  data: { demo: require('../../config').demo, user: null, counts: [0, 0, 0], loading: false, error: '' },
  onShow() { const a = auth(); this.setData({ user: a && a.user, counts: [0, 0, 0] }); if (a) this.reload() },
  reload() { return load(this, async () => { const [user, ...counts] = await Promise.all([request('GET', '/me'), ...['pending_confirmation', 'reservation_confirmed', 'completed'].map(tab => request('GET', '/orders', { tab, pageSize: 1 }))]); return { user, counts: counts.map(r => r.total) } }) },
  records(e) { getApp().globalData.recordTab = e.currentTarget.dataset.tab || 'all'; switchTab('/pages/records/index') },
  login() { open('/pages/login/index') },
  shop() { open('/pages/shop/index') },
  assistant() { open('/pages/ai/index') },
  logout() { wx.showModal({ title: '退出登录', content: '确定退出当前账号吗？', success: async r => { if (!r.confirm) return; try { await request('POST', '/auth/logout', {}) } catch (e) { this.setData({ error: e.message }); return } clearAuth(); this.setData({ user: null, counts: [0, 0, 0] }) } }) }
})
