const config = require('./config')
App({
  globalData: { config, petSearch: null },
  onLaunch() {
    // 缓存只有未过期的买家会话；验证码与图形认证凭据不落盘。
    const auth = wx.getStorageSync('rongrong:auth')
    if (auth && auth.expiresAt <= Date.now()) wx.removeStorageSync('rongrong:auth')
  }
})
