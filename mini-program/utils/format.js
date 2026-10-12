const config = require('../config')
const money = amount => '¥ ' + (Number(amount) / 100).toFixed(2).replace(/\.00$/, '').replace(/\B(?=(\d{3})+(?!\d))/g, ',')
const pad = value => String(value).padStart(2, '0')
// 所有预约时刻按北京时间解释，与后端 +08:00 约定保持一致。
function beijing(value = Date.now()) {
  const d = new Date(new Date(value).getTime() + 8 * 3600000)
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`
}
function visitText(value) {
  if (!value) return '未安排'
  const d = new Date(Date.parse(value) + 8 * 3600000)
  return `${d.getUTCMonth() + 1}月${d.getUTCDate()}日 ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`
}
function imageUrl(url) {
  if (!url) return ''
  if (url.startsWith('/assets/')) return url
  if (/^https?:\/\//.test(url)) return url
  return config.assetOrigin + (url.startsWith('/') ? url : '/' + url)
}
function pet(p) {
  return { ...p, coverUrl: imageUrl(p.coverUrl), priceText: money(p.priceAmount), genderText: p.gender === 'male' ? '弟弟' : '妹妹' }
}
function order(o) {
  return { ...o, product: { ...o.product, coverUrl: imageUrl(o.product.coverUrl) }, priceText: money(o.amount), visitText: visitText(o.appointment && o.appointment.visitAt), canCancel: (o.availableActions || []).includes('cancel') }
}
module.exports = { money, beijing, visitText, imageUrl, pet, order }
