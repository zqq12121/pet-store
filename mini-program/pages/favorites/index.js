const { request, auth } = require('../../utils/api')
const { pet } = require('../../utils/format')
const { load, open, switchTab } = require('../../utils/page')
Page({
  data: { demo: require('../../config').demo, loggedIn: false, pets: [], total: 0, page: 0, removing: '', loading: false, error: '' },
  onShow() {
    this._loadVersion = (this._loadVersion || 0) + 1
    this.setData({ loggedIn: !!auth(), pets: [], total: 0, page: 0, error: '', loading: false })
    if (this.data.loggedIn) return this.reload()
  },
  reload() { if (!auth()) { this.onShow(); return } return this.fetch(1) },
  async fetch(page) { await load(this, async () => {
    const result = await request('GET', '/me/favorites', { page, pageSize: 12 })
    const pets = result.items.map(p => p.available ? pet(p) : p)
    return { pets: page === 1 ? pets : this.data.pets.concat(pets), total: result.total, page }
  })
    // 401 会清除会话，同时清空旧收藏并恢复登录入口，保留错误提示。
    if (!auth()) this.setData({ loggedIn: false, pets: [], total: 0, page: 0 })
  },
  onReachBottom() { if (!this.data.loading && this.data.pets.length < this.data.total) return this.fetch(this.data.page + 1) },
  login() { open('/pages/login/index?redirect=' + encodeURIComponent('/pages/favorites/index')) },
  browse() { switchTab('/pages/pets/index') },
  async remove(e) {
    if (this.data.removing || this.data.loading) return
    this.setData({ removing: e.currentTarget.dataset.id, error: '' })
    try {
      // 下架动物仍可取消收藏，成功后重新分页，防止列表缺项。
      await request('PUT', '/me/favorites/' + encodeURIComponent(this.data.removing), { active: false })
      await this.reload()
    } catch (e) {
      if (e.status === 401) this.onShow()
      this.setData({ error: e.message })
    }
    finally { this.setData({ removing: '' }) }
  }
})
