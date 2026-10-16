<script setup lang="ts">
import {ref,onUnmounted,watch} from 'vue'
import {useRouter} from 'vue-router'
import {buyer,saveAuth} from '../../../shared/api'
import type {LoginResult} from '../../../shared/types'
import {useGraphCaptcha} from '../../../shared/useGraphCaptcha'
const props=defineProps<{accepted:boolean;returnPath:string;disabled:boolean}>()
const busy=defineModel<boolean>('busy',{default:false})
const graph=useGraphCaptcha(),router=useRouter()
const image=ref(''),code=ref(''),error=ref(''),remaining=ref(0)
let ticket='',browserToken='',version=0,disposed=false,expiresAt=0
let timer:ReturnType<typeof setTimeout>|undefined
// 刷新、撤回同意或离开页面均注销旧码，旧异步响应不能改变当前身份。
function clear(){
 version++;clearTimeout(timer)
 const old=ticket,secret=browserToken
 ticket='';browserToken='';image.value='';code.value='';remaining.value=0
 if(old)void buyer('POST',`/auth/mini-wechat/qr/${old}/cancel`,{browserToken:secret}).catch(()=>{})
}
watch(()=>[props.accepted,props.disabled,props.returnPath],()=>clear())
async function poll(current:number){
 if(disposed||current!==version)return
 remaining.value=Math.max(0,Math.ceil((expiresAt-Date.now())/1000))
 if(!remaining.value){clear();error.value='二维码已过期，请刷新后重新扫码';return}
 try{
  const result=await buyer<{status:string;login?:LoginResult;returnPath?:string}>('POST',`/auth/mini-wechat/qr/${ticket}/poll`,{browserToken})
  if(disposed||current!==version||!props.accepted||props.disabled)return
  if(result.status==='authenticated'){
   if(!result.login?.accessToken||!result.login.user?.id||!(result.login.expiresIn>0))throw new Error('登录响应不完整，请重新扫码')
   saveAuth('buyer',result.login);ticket='';clear();sessionStorage.removeItem('warmpaw:return')
   await router.replace(props.returnPath);return
  }
  if(result.status!=='pending'){clear();error.value=result.status==='rejected'?'已在手机上取消登录，请重新扫码':'二维码已失效，请重新扫码';return}
  timer=setTimeout(()=>void poll(current),2000)
 }catch(e){if(!disposed&&current===version){clear();error.value=e instanceof Error?e.message:'登录连接中断，请重新扫码'}}
}
async function start(){
 if(!props.accepted||props.disabled||busy.value)return
 clear();const current=version;busy.value=true;error.value=''
 try{
  const proof=await graph.verify()
  if(!proof||disposed||current!==version||!props.accepted)return
  const result=await buyer<{ticket:string;browserToken:string;image:string;verificationCode:string;expiresIn:number}>('POST','/auth/mini-wechat/qr',{returnPath:props.returnPath,...proof})
  // 即使创建期间已离开，也尽力撤销服务端刚生成的旧码。
  if(disposed||current!==version||!props.accepted){void buyer('POST',`/auth/mini-wechat/qr/${result.ticket}/cancel`,{browserToken:result.browserToken}).catch(()=>{});return}
  ticket=result.ticket;browserToken=result.browserToken;image.value=result.image;code.value=result.verificationCode
  expiresAt=Date.now()+result.expiresIn*1000;remaining.value=result.expiresIn
  timer=setTimeout(()=>void poll(current),2000)
 }catch(e){if(!disposed&&current===version){clear();error.value=e instanceof Error?e.message:'小程序码暂不可用'}}
 finally{busy.value=false}
}
onUnmounted(()=>{disposed=true;clear()})
</script>
<template>
 <aside class="mini-qr-panel" aria-label="微信扫码登录">
  <h2>微信扫码登录</h2>
  <img v-if="image" class="mini-code" :src="image" alt="使用微信扫一扫，打开灵宠小珠确认登录">
  <div v-else class="placeholder" role="status">{{busy?'正在获取小程序码…':!accepted?'同意协议后获取小程序码':'微信扫一扫，打开灵宠小珠'}}</div>
  <p v-if="code" class="verification">核对码 <strong>{{code}}</strong> · {{remaining}} 秒</p>
  <p v-if="error" class="error-text" role="alert">{{error}}</p>
  <button type="button" class="text-link" :disabled="!accepted||disabled||busy" @click="start">{{busy?'请稍候…':image?'刷新小程序码':'获取登录小程序码'}}</button>
  <p class="note">在灵宠小珠中确认后，当前网页自动登录。<br>首次登录自动注册，无需手机号。</p>
 </aside>
</template>
<style scoped>
.mini-qr-panel{border:1px solid var(--line);background:var(--sand);border-radius:20px;padding:24px 16px 20px;text-align:center;min-height:302px}
h2{font-size:14px;font-weight:500;margin:0 0 20px}.mini-code{display:block;width:190px;height:190px;margin:0 auto 12px;max-width:100%;object-fit:contain}
.placeholder{width:190px;min-height:190px;max-width:100%;margin:0 auto 16px;border-radius:12px;background:var(--surface);display:grid;place-items:center;padding:16px;font-size:12px;color:var(--muted)}
.verification{font-size:12px;margin:8px 0 12px}.verification strong{font-size:18px;letter-spacing:3px}.text-link{font-size:13px}.note{font-size:11px;line-height:1.8;color:var(--muted);margin:12px 0 0}.error-text{font-size:12px;overflow-wrap:anywhere}
</style>
