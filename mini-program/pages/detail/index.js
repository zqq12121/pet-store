const { request, auth, requireLogin } = require('../../utils/api')
const { pet, imageUrl } = require('../../utils/format')
const { load } = require('../../utils/page')
Page({
  data: { demo: require('../../config').demo, pet: null, favorited: false, saving: false, loading: true, error: '' },
  onLoad(query) { this.id = query.id },
  // 从登录页返回时重新读取当前账号收藏，避免沿用游客或其他账号状态。
  onShow() { return this.reload() },
  reload() { return load(this, async () => {
    const product = await request('GET', '/pets/' + encodeURIComponent(this.id))
    const state = auth() ? await request('GET', '/me/favorites/' + encodeURIComponent(this.id)) : { favorited: false }
    return { pet: pet(product), favorited: state.favorited }
  }) },
  async favorite() {
    if (this.data.saving || this.data.loading) return
    if (!requireLogin('/pages/detail/index?id=' + encodeURIComponent(this.id))) return
    this.setData({ saving: true, error: '' })
    try {
      // 发送目标状态；只有接口成功才更新按钮，失败保留原收藏状态。
      const state = await request('PUT', '/me/favorites/' + encodeURIComponent(this.id), { active: !this.data.favorited })
      this.setData({ favorited: state.favorited })
    } catch (e) { this.setData({ error: e.message }) }
    finally { this.setData({ saving: false }) }
  },
  photos() { const p = this.data.pet; wx.previewImage({ urls: (p.images || []).map(i => imageUrl(i.url)).filter(Boolean), current: p.coverUrl }) },
  archive() { const p = this.data.pet; wx.showModal({ title: '成长档案', content: [p.healthDescription, p.vaccineStatus, p.dewormStatus, p.feedingNotes, p.quarantine && p.quarantine.certificateNo].filter(Boolean).join('\n'), showCancel: false }) }
})
