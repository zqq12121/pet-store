const { request, saveAuth } = require('../../utils/api')
const config = require('../../config')
Page({
  data: { demo: config.demo, accepted: false, busy: false, error: '' },
  onLoad(q) {
    // 微信保留 URL 编码；解码后仅允许项目内目的页。
    let target = '/pages/my/index'
    try { target = decodeURIComponent(q.redirect || target) } catch (_) { /* 损坏参数使用默认目的页。 */ }
    this.target = /^\/pages\/(detail|favorites|my)\/index(?:\?|$)/.test(target) ? target : '/pages/my/index'
  },
  onUnload() { this.disposed = true },
  accept(e) { this.setData({ accepted: e.detail.value.includes('yes') }) },
  privacy() { wx.showModal({ title: '微信登录与隐私说明', content: '本项目仅供个人练习。微信身份用于登录和保存本人动物收藏，首次登录会自动创建账号。不读取微信手机号、头像或昵称。真实模式收藏保存在账号中，可在其他设备登录查看。本地演示使用模拟身份，收藏仅保存在本机。', showCancel: false }) },
  async login() {
    if (this.data.busy) return
    if (!this.data.accepted) { this.setData({ error: '请先阅读并同意微信登录与隐私说明' }); return }
    this.setData({ busy: true, error: '' })
    try {
      let code
      // 演示不申请真实凭证；真实模式只在用户点击后调用 wx.login。
      if (!config.demo) code = await new Promise((resolve, reject) => wx.login({
        timeout: 10000,
        success: r => r.code ? resolve(r.code) : reject(new Error('未获取到微信登录凭证，请重试')),
        fail: () => reject(new Error('微信登录暂不可用，请重试'))
      }))
      if (this.disposed) return
      const r = await request('POST', '/auth/mini-wechat/login', config.demo ? {} : { code })
      // 离开页面后的异步响应不得覆盖身份或触发跳转。
      if (this.disposed) return
      saveAuth(r)
      // 自动注册由 Java 后端判定，只有项目会话保存成功才显示成功提示。
      wx.showToast({ title: config.demo ? '演示登录成功' : (r.isNewUser ? '注册并登录成功' : '登录成功'), icon: 'success' })
      const pages = getCurrentPages(), previous = pages[pages.length - 2]
      if (previous && '/' + previous.route === this.target.split('?')[0]) wx.navigateBack()
      else if (/^\/pages\/(my|favorites)\/index$/.test(this.target)) wx.switchTab({ url: this.target })
      else wx.redirectTo({ url: this.target })
    } catch (e) { if (!this.disposed) this.setData({ error: e.message }) }
    finally { if (!this.disposed) this.setData({ busy: false }) }
  }
})
