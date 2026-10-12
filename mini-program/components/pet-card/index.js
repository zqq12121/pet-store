// 每张卡片使用自己的宠物 ID，避免原型示例跳转到同一只宠物。
Component({ properties: { pet: Object }, methods: { open() { wx.navigateTo({ url: '/pages/detail/index?id=' + encodeURIComponent(this.data.pet.id) }) } } })
