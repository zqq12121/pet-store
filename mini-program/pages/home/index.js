const { request } = require('../../utils/api')
const { pet } = require('../../utils/format')
const { load, switchTab } = require('../../utils/page')
Page({
  data: { demo: require('../../config').demo, loading: true, error: '', pets: [], hero: null },
  onLoad() { this.reload() },
  reload() { return load(this, async () => { const h = await request('GET', '/home'); const pets = h.recommendedPets.map(pet); return { pets, hero: pets[0] || (h.latestPets[0] && pet(h.latestPets[0])) } }) },
  browse() { switchTab('/pages/pets/index') },
  favorites() { switchTab('/pages/favorites/index') }
})
