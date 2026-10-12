const { request } = require('../../utils/api')
const { pet } = require('../../utils/format')
const { load, open, switchTab } = require('../../utils/page')
Page({
  data: { demo: require('../../config').demo, loading: true, error: '', pets: [], hero: null, shop: null },
  onLoad() { this.reload() },
  reload() { return load(this, async () => { const h = await request('GET', '/home'); const pets = h.recommendedPets.map(pet); return { pets, hero: pets[0] || (h.latestPets[0] && pet(h.latestPets[0])), shop: h.shop } }) },
  browse() { switchTab('/pages/pets/index') },
  assistant() { open('/pages/ai/index') },
  shop() { open('/pages/shop/index') }
})
