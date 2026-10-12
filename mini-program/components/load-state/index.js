Component({ properties: { loading: Boolean, error: String }, methods: { retry() { this.triggerEvent('retry') } } })
