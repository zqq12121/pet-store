const { request } = require('../../utils/api')
const { load } = require('../../utils/page')
Page({
  data: { demo: require('../../config').demo, shop: null, loading: true, error: '', hasMap: false },
  onLoad() { this.reload() },
  reload() { return load(this, async () => { const shop = await request('GET', '/shop'); return { shop, hasMap: shop.coordinateSystem === 'gcj02' && typeof shop.latitude === 'number' && typeof shop.longitude === 'number' } }) },
  phone() { if (this.data.shop.phone) wx.makePhoneCall({ phoneNumber: this.data.shop.phone }) },
  wechat() { if (this.data.shop.wechat) wx.setClipboardData({ data: this.data.shop.wechat }) },
  map() { if (this.data.hasMap) wx.openLocation({ latitude: this.data.shop.latitude, longitude: this.data.shop.longitude, name: this.data.shop.name, address: this.data.shop.address }) }
})
