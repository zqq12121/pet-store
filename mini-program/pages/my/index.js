const { request, auth, clearAuth } = require('../../utils/api')
const { load, open, switchTab } = require('../../utils/page')
Page({
  data: { demo: require('../../config').demo, user: null, count: 0, loading: false, error: '' },
  onShow() {
    // 退出或切换账号后丢弃尚未完成的旧账号查询。
    this._loadVersion = (this._loadVersion || 0) + 1
    const a = auth(); this.setData({ user: a && a.user, count: 0, loading: false, error: '' })
    if (a) return this.reload()
  },
  reload() { return load(this, async () => { const [user, favorites] = await Promise.all([request('GET', '/me'), request('GET', '/me/favorites', { pageSize: 1 })]); return { user, count: favorites.total } }) },
  favorites() { switchTab('/pages/favorites/index') },
  login() { open('/pages/login/index') },
  logout() { wx.showModal({ title: '退出登录', content: '确定退出当前账号吗？', success: async r => { if (!r.confirm) return; try { await request('POST', '/auth/logout', {}) } catch (e) { this.setData({ error: e.message }); return } clearAuth(); this.onShow() } }) }
})
