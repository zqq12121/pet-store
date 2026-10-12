const { request, requireLogin } = require('../../utils/api')
const { order } = require('../../utils/format')
const { load, open, switchTab } = require('../../utils/page')
Page({
  data: { demo: require('../../config').demo, order: null, loading: true, error: '' },
  onLoad(q) { this.id = q.id; if (requireLogin('/pages/success/index?id=' + encodeURIComponent(this.id))) this.reload() },
  reload() { return load(this, async () => ({ order: order(await request('GET', '/orders/' + encodeURIComponent(this.id))) })) },
  records() { switchTab('/pages/records/index') },
  shop() { open('/pages/shop/index') }
})
