import {onUnmounted} from 'vue'
import {buyer} from './api'

export type GraphProof={lot_number:string;captcha_output:string;pass_token:string;gen_time:string}
type Captcha={
 appendTo(selector:string):Captcha
 onReady(callback:()=>void):Captcha
 onSuccess(callback:()=>void):Captcha
 onClose(callback:()=>void):Captcha
 onError(callback:()=>void):Captcha
 getValidate():GraphProof|false
 showBox():void
 destroy():void
}
declare global {
 interface Window {
  initAlicom4?:(config:{captchaId:string;product:string;https:boolean;language:string;onError:()=>void;offlineCb:()=>void},callback:(captcha:Captcha)=>void)=>void
 }
}
let sdk:Promise<void>|undefined
// 延迟加载官方 SDK；多个入口共享脚本，加载失败后允许重新点击重试。
function loadSdk() {
 if(window.initAlicom4)return Promise.resolve()
 if(!sdk)sdk=new Promise<void>((resolve,reject)=>{
  const script=document.createElement('script')
  const timer=setTimeout(fail,15000)
  function fail(){clearTimeout(timer);script.remove();reject(new Error('图形验证加载失败，请重试'))}
  script.src='https://static.alicaptcha.com/v4/ct4.js';script.async=true
  script.onerror=fail
  script.onload=()=>{if(!window.initAlicom4){fail();return}clearTimeout(timer);resolve()}
  document.head.appendChild(script)
 }).catch(e=>{sdk=undefined;throw e})
 return sdk
}

/** 每次提交使用新的图形凭据；取消和离开页面不会继续发送业务请求。 */
export function useGraphCaptcha() {
 let cancel:(()=>void)|undefined,disposed=false
 onUnmounted(()=>{disposed=true;cancel?.()})
 async function verify():Promise<GraphProof|null> {
  const {captchaId}=await buyer<{captchaId:string}>('GET','/auth/graph-captcha')
  if(disposed)return null
  await loadSdk()
  if(disposed)return null
  return new Promise((resolve,reject)=>{
   const host=document.createElement('div')
   host.id=`graph-captcha-${crypto.randomUUID()}`;document.body.appendChild(host)
   let instance:Captcha|undefined,finished=false
   // 只限制初始化耗时；用户操作图形验证时不计入这个超时。
   const timer=setTimeout(()=>finish(undefined,new Error('图形验证加载超时，请重试')),15000)
   function finish(proof?:GraphProof,error?:Error){
    if(finished)return
    finished=true;clearTimeout(timer);cancel=undefined
    // SDK 成功回调返回后再销毁，避免打断它自己的关闭动画与事件处理。
    queueMicrotask(()=>{instance?.destroy();host.remove()})
    if(error)reject(error);else resolve(proof||null)
   }
   const fail=()=>finish(undefined,new Error('图形验证暂不可用，请重试'))
   cancel=()=>finish()
   try {
    window.initAlicom4!({captchaId,product:'bind',https:true,language:'zho',onError:fail,offlineCb:fail},ct=>{
     if(finished){ct.destroy();return}
     instance=ct
     ct.onReady(()=>{if(!finished){clearTimeout(timer);ct.showBox()}})
      .onSuccess(()=>{
       const proof=ct.getValidate()
       if(!proof||!proof.lot_number||!proof.captcha_output||!proof.pass_token||!proof.gen_time){fail();return}
       // 显式白名单，避免 SDK 附带 captcha_id 等字段被业务接口拒绝。
       finish({lot_number:proof.lot_number,captcha_output:proof.captcha_output,pass_token:proof.pass_token,gen_time:proof.gen_time})
      }).onClose(()=>finish()).onError(fail).appendTo(`#${host.id}`)
    })
   }catch{fail()}
  })
 }
 return {verify}
}
