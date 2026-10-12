const { request, requireLogin, mutationKey } = require('../../utils/api')
const { order } = require('../../utils/format')
const { load, open, switchTab } = require('../../utils/page')
Page({
  data: { demo: require('../../config').demo, order: null, loading: false, error: '', cancelling: false },
  onLoad(q) { this.id = q.id; this.key = mutationKey() },
  onShow() { if (requireLogin('/pages/record/index?id=' + encodeURIComponent(this.id))) this.reload() },
  reload() { return load(this, async () => ({ order: order(await request('GET', '/orders/' + encodeURIComponent(this.id))) })) },
  shop() { open('/pages/shop/index') },
  records() { switchTab('/pages/records/index') },
  cancel() {
    if (this.data.cancelling) return
    wx.showModal({ title: '取消到店预约', content: '取消后将释放这只宠物的预约。确定取消吗？', confirmText: '取消预约', success: async result => {
      if (!result.confirm) return
      this.setData({ cancelling: true, error: '' })
      const path = '/orders/' + encodeURIComponent(this.id) + '/cancel', body = { reason: '买家取消预约' }
      // 取消接口返回状态摘要；随后重新查详情，不能按完整预约对象解析。
      try { await request('POST', path, body, { 'Idempotency-Key': this.key(path, body) }); await this.reload() }
      catch (e) { this.setData({ error: e.message }) }
      finally { this.setData({ cancelling: false }) }
    } })
  }
})
