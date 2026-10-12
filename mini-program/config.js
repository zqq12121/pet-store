// 演示与真实接口显式切换；真实请求失败时绝不回退演示数据。
module.exports = {
  demo: true,
  apiBase: 'http://127.0.0.1:8090/api/v1',
  assetOrigin: 'http://127.0.0.1:8090'
}
