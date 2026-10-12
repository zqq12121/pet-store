const config = require('../config')
// 与旧短信会话分开保存，并隔离演示和真实模式。
const AUTH = 'rongrong:wechat-auth'
function auth() {
  const value = wx.getStorageSync(AUTH)
  if (value && value.demo === config.demo && value.expiresAt > Date.now()) return value
  wx.removeStorageSync(AUTH)
  return null
}
function saveAuth(value) { wx.setStorageSync(AUTH, { ...value, demo: config.demo, expiresAt: Date.now() + value.expiresIn * 1000 }) }
function clearAuth() { wx.removeStorageSync(AUTH) }
function headers() { const value = auth(); return value ? { Authorization: 'Bearer ' + value.accessToken } : {} }
function request(method, path, data, extra = {}) {
  if (config.demo) return require('./demo').request(method, path, data, extra)
  return new Promise((resolve, reject) => wx.request({
    url: config.apiBase + path, method, data, timeout: 15000,
    header: { 'Content-Type': 'application/json', ...headers(), ...extra },
    success(res) {
      if (res.statusCode === 401) clearAuth()
      if (res.statusCode >= 200 && res.statusCode < 300 && res.data.code === 'OK') resolve(res.data.data)
      else { const e = new Error(res.data.message || '请求失败，请重试'); e.status = res.statusCode; e.code = res.data.code; reject(e) }
    },
    fail() { reject(new Error('连接暂时中断，请重试。你的表单已保留。')) }
  }))
}
function uniqueId() { return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) + '-' + Math.random().toString(36).slice(2) }
// 内容未变时重试必须沿用原幂等键，避免超时后创建重复预约。
function mutationKey() {
  let fingerprint = '', key = ''
  return (path, body) => { const next = path + JSON.stringify(body); if (next !== fingerprint) { fingerprint = next; key = uniqueId() } return key }
}
function requireLogin(target) {
  if (auth()) return true
  wx.navigateTo({ url: '/pages/login/index?redirect=' + encodeURIComponent(target) })
  return false
}
module.exports = { auth, saveAuth, clearAuth, headers, request, mutationKey, uniqueId, requireLogin }
