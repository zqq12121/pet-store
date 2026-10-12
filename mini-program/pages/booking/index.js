const { request, mutationKey, requireLogin } = require('../../utils/api')
const { pet, money, beijing } = require('../../utils/format')
const { load } = require('../../utils/page')
Page({
  data: { demo: require('../../config').demo, loading: false, error: '', preview: null, agreement: null, accepted: false, submitting: false, contactName: '', contactPhone: '', date: '', time: '10:00', remark: '', minDate: '', maxDate: '' },
  onLoad(q) { this.petId = q.petId; this.key = mutationKey(); this.setData({ minDate: beijing(), maxDate: beijing(Date.now() + 7 * 86400000) }) },
  onShow() { if (requireLogin('/pages/booking/index?petId=' + encodeURIComponent(this.petId))) this.reload() },
  reload() { return load(this, async () => {
    const [p, a] = await Promise.all([request('POST', '/orders/preview', { petId: this.petId }), request('GET', '/agreements/current', { type: 'live_pet_trade' })])
    if (p.agreementContentHash !== a.contentHash) throw new Error('协议已更新，请刷新后重新阅读')
    return { preview: { ...p, product: pet(p.product), priceText: money(p.amount) }, agreement: a, accepted: false }
  }) },
  input(e) { this.setData({ [e.currentTarget.dataset.field]: e.detail.value }) },
  date(e) { this.setData({ date: e.detail.value }) },
  time(e) { this.setData({ time: e.detail.value }) },
  accept(e) { this.setData({ accepted: e.detail.value.includes('yes') }) },
  readAgreement() { wx.showModal({ title: this.data.agreement.title, content: this.data.agreement.content, showCancel: false, confirmText: '已阅读' }) },
  async submit() {
    if (this.data.submitting || !this.data.preview) return
    const d = this.data, p = d.preview
    if (!d.contactName.trim() || !/^1[3-9]\d{9}$/.test(d.contactPhone)) { this.setData({ error: '请填写联系人与到店联系号码' }); return }
    if (!d.date || !d.accepted || !p.purchaseAllowed) { this.setData({ error: '请选择到店时间，并阅读同意预约须知' }); return }
    const visitAt = d.date + 'T' + d.time + ':00+08:00'
    if (Date.parse(visitAt) <= Date.now() || Date.parse(visitAt) > Date.now() + 7 * 86400000) { this.setData({ error: '请选择未来 7 天内的到店时间' }); return }
    const body = { petId: this.petId, productVersion: p.productVersion, expectedAmount: p.amount, contactName: d.contactName.trim(), contactPhone: d.contactPhone, visitAt, remark: d.remark, agreementVersion: p.agreementVersion, agreementContentHash: p.agreementContentHash, agreementAccepted: d.accepted }
    this.setData({ submitting: true, error: '' })
    try {
      const o = await request('POST', '/orders', body, { 'Idempotency-Key': this.key('/orders', body) })
      wx.redirectTo({ url: '/pages/success/index?id=' + encodeURIComponent(o.id) })
    } catch (e) { this.setData({ error: e.message }); if (e.status === 401) requireLogin('/pages/booking/index?petId=' + encodeURIComponent(this.petId)) }
    finally { this.setData({ submitting: false }) }
  }
})
