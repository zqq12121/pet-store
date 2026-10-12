const { request, auth } = require('../../utils/api')
const { order } = require('../../utils/format')
const { load, open, switchTab } = require('../../utils/page')
Page({
  data: { demo: require('../../config').demo, loggedIn: false, tab: 'all', orders: [], total: 0, page: 0, loading: false, error: '' },
  onShow() { this.setData({ loggedIn: !!auth(), tab: getApp().globalData.recordTab || 'all' }); getApp().globalData.recordTab = 'all'; if (this.data.loggedIn) this.reload(); else this.setData({ orders: [], error: '' }) },
  tab(e) { this.setData({ tab: e.currentTarget.dataset.tab }); if (this.data.loggedIn) this.reload() },
  reload() { return this.fetch(1) },
  fetch(page) { return load(this, async () => { const r = await request('GET', '/orders', { tab: this.data.tab, page, pageSize: 12 }); return { orders: page === 1 ? r.items.map(order) : this.data.orders.concat(r.items.map(order)), total: r.total, page, loggedIn: !!auth() } }) },
  onReachBottom() { if (!this.data.loading && this.data.orders.length < this.data.total) this.fetch(this.data.page + 1) },
  record(e) { open('/pages/record/index?id=' + encodeURIComponent(e.currentTarget.dataset.id)) },
  browse() { switchTab('/pages/pets/index') },
  login() { open('/pages/login/index?redirect=' + encodeURIComponent('/pages/records/index')) }
})
