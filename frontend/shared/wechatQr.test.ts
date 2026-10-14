import {describe,it,expect} from 'vitest'
import {wechatQrOptions} from './wechatQr'

const origin='https://shop.example.test'
function authorize(callback=origin+'/login') {
 const query=new URLSearchParams({appid:'website-app',scope:'snsapi_login',state:'single-use-state',redirect_uri:callback})
 return 'https://open.weixin.qq.com/connect/qrconnect?'+query
}
describe('微信网站扫码参数',()=>{
 it('使用官方扫码端点、本站 HTTPS 回调，并在原窗口完成登录',()=>{
  const options=wechatQrOptions(authorize(),origin,'qr-host')
  expect(options).toMatchObject({id:'qr-host',appid:'website-app',scope:'snsapi_login',state:'single-use-state',self_redirect:false,fast_login:0})
  expect(decodeURIComponent(options.redirect_uri)).toBe(origin+'/login')
  expect(decodeURIComponent(options.href)).toBe(origin+'/wechat-login.css')
 })
 it.each([
  authorize().replace('https://open.weixin.qq.com','http://open.weixin.qq.com'),
  authorize().replace('open.weixin.qq.com','open.weixin.qq.com.evil.test'),
  authorize().replace('/connect/qrconnect','/connect/oauth2/authorize'),
  authorize().replace('snsapi_login','snsapi_base'),
  authorize('https://another.example.test/login'),authorize('http://shop.example.test/login'),
  authorize(origin+'/profile'),authorize(origin+'/login#fragment'),
  authorize().replace('state=single-use-state','state='),
 ])('拒绝混用公众号、错误回调或不可信地址：%s',value=>{
  expect(()=>wechatQrOptions(value,origin,'qr-host')).toThrow()
 })
})
