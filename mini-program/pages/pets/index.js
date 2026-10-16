const { request } = require('../../utils/api')
const { pet } = require('../../utils/format')
const { load } = require('../../utils/page')
Page({
  data: { demo: require('../../config').demo, keyword: '', category: '', cheap: false, pets: [], total: 0, page: 0, loading: false, error: '' },
  onShow() { const search = getApp().globalData.petSearch; if (search) { this.setData(search); getApp().globalData.petSearch = null } this.reload() },
  input(e) { this.setData({ keyword: e.detail.value }) },
  category(e) { this.setData({ category: e.currentTarget.dataset.category }); this.reload() },
  filter() { this.setData({ cheap: !this.data.cheap }); this.reload() },
  reload() {
    // 新筛选先清空旧分页，失败后也不会把另一组结果继续拼进来。
    this.setData({ pets: [], total: 0, page: 0 })
    return this.fetch(1)
  },
  // 页码只有成功收到结果后才前进；失败重试不会跳过数据。
  fetch(page) {
    // 翻页沿用最近一次提交的筛选，不读取尚未搜索的输入框内容。
    if (page === 1) this.filters = { keyword: this.data.keyword.trim(), category: this.data.category, cheap: this.data.cheap }
    const d = this.filters
    return load(this, async () => { const r = await request('GET', '/pets', { page, pageSize: 12, ...(d.keyword ? { keyword: d.keyword } : {}), ...(d.category ? { category: d.category } : {}), ...(d.cheap ? { maxPriceAmount: 200000 } : {}) }); return { pets: page === 1 ? r.items.map(pet) : this.data.pets.concat(r.items.map(pet)), total: r.total, page } })
  },
  onReachBottom() { if (!this.data.loading && this.data.pets.length < this.data.total) return this.fetch(this.data.page + 1) }
})
