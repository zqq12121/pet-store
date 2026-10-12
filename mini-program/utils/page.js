// 页面统一呈现可重试错误；加载顺序变化时丢弃旧筛选请求的结果。
async function load(page, action) {
  const version = page._loadVersion = (page._loadVersion || 0) + 1
  page.setData({ loading: true, error: '' })
  try { const data = await action(); if (version === page._loadVersion && data) page.setData(data) }
  catch (e) { if (version === page._loadVersion) page.setData({ error: e.message }) }
  finally { if (version === page._loadVersion) page.setData({ loading: false }) }
}
function open(url) { wx.navigateTo({ url }) }
function switchTab(url) { wx.switchTab({ url }) }
module.exports = { load, open, switchTab }
