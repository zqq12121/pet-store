const { request } = require('../../utils/api')
const { pet, imageUrl } = require('../../utils/format')
const { load, open } = require('../../utils/page')
Page({
  data: { demo: require('../../config').demo, pet: null, loading: true, error: '' },
  onLoad(query) { this.id = query.id; this.reload() },
  reload() { return load(this, async () => ({ pet: pet(await request('GET', '/pets/' + encodeURIComponent(this.id))) })) },
  book() { open('/pages/booking/index?petId=' + encodeURIComponent(this.id)) },
  assistant() { open('/pages/ai/index?petId=' + encodeURIComponent(this.id)) },
  photos() { const p = this.data.pet; wx.previewImage({ urls: (p.images || []).map(i => imageUrl(i.url)).filter(Boolean), current: p.coverUrl }) },
  archive() { const p = this.data.pet; wx.showModal({ title: '成长档案', content: [p.healthDescription, p.vaccineStatus, p.dewormStatus, p.feedingNotes, p.quarantine && p.quarantine.certificateNo].filter(Boolean).join('\n'), showCancel: false }) }
})
