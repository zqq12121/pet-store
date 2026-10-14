export interface WechatQrOptions {
 id:string;appid:string;scope:'snsapi_login';redirect_uri:string;state:string
 self_redirect:false;fast_login:0;style:'black';href:string;onReady?:()=>void;onCleanup?:()=>void
}
declare global {interface Window {WxLogin?:new(options:WechatQrOptions)=>unknown}}
let sdk:Promise<void>|undefined

/** 只接受后端返回的官方网站扫码地址，并确保回调回到当前网站。 */
export function wechatQrOptions(authorizeUrl:string,origin:string,id:string):WechatQrOptions {
 const url=new URL(authorizeUrl),query=url.searchParams
 const callback=new URL(query.get('redirect_uri')||'')
 if(url.origin!=='https://open.weixin.qq.com'||url.pathname!=='/connect/qrconnect'||url.username||url.password
   ||query.get('scope')!=='snsapi_login'||!query.get('appid')||!query.get('state')
   ||callback.protocol!=='https:'||callback.origin!==origin||callback.pathname!=='/login'||callback.hash||callback.username||callback.password)
  throw new Error('微信扫码配置不正确，请联系网站维护者')
 return {id,appid:query.get('appid')!,scope:'snsapi_login',state:query.get('state')!,
  // 固定展示扫码，避免已登录的桌面微信将面板替换为快速登录界面。
  redirect_uri:encodeURIComponent(callback.href),self_redirect:false,fast_login:0,style:'black',
  href:encodeURIComponent(new URL('/wechat-login.css',origin).href)}
}

/** 同意协议且主动发起扫码后才加载微信 SDK；失败允许重新尝试。 */
export function loadWechatQr():Promise<void> {
 if(window.WxLogin)return Promise.resolve()
 if(sdk)return sdk
 sdk=new Promise<void>((resolve,reject)=>{
  const script=document.createElement('script')
  const timer=setTimeout(()=>fail(),15000)
  function fail(){clearTimeout(timer);script.remove();reject(new Error('微信二维码加载失败，请重试'))}
  script.src='https://res.wx.qq.com/connect/zh_CN/htmledition/js/wxLogin.js';script.async=true
  script.onload=()=>{clearTimeout(timer);if(window.WxLogin)resolve();else fail()}
  script.onerror=fail;document.head.appendChild(script)
 }).catch(error=>{sdk=undefined;throw error})
 return sdk
}
