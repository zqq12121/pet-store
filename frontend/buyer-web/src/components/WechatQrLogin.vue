<script setup lang="ts">
import {ref,onMounted,onUnmounted,watch,nextTick} from 'vue'
import {buyer,isDemo,randomId} from '../../../shared/api'
import {useGraphCaptcha} from '../../../shared/useGraphCaptcha'
import {loadWechatQr,wechatQrOptions} from '../../../shared/wechatQr'
import MiniWechatQr from './MiniWechatQr.vue'
const props=defineProps<{accepted:boolean;returnPath:string;disabled:boolean}>()
const busy=defineModel<boolean>('busy',{default:false})
const enabled=ref(false),checking=ref(true),error=ref(''),active=ref(false),remaining=ref(0)
const miniEnabled=ref(false)
const graph=useGraphCaptcha(),id='wechat-qr-'+randomId()
let disposed=false,version=0,expiresAt=0,timer:ReturnType<typeof setInterval>,widgetTimer:ReturnType<typeof setTimeout>,cleanup:(()=>void)|undefined
function clear(){version++;active.value=false;remaining.value=0;clearTimeout(widgetTimer);cleanup?.();cleanup=undefined;document.getElementById(id)?.replaceChildren()}
watch(()=>props.accepted,value=>{if(!value)clear()})
async function checkCapability(){
 checking.value=true;error.value=''
 try{const result=await buyer<{wechatQrLogin?:boolean;wechatMiniQrLogin?:boolean}>('GET','/auth/capabilities');if(!disposed){enabled.value=!isDemo&&result.wechatQrLogin===true;miniEnabled.value=!isDemo&&result.wechatMiniQrLogin===true}}
 catch{if(!disposed){enabled.value=false;error.value='暂时无法连接登录服务，请重试'}}
 finally{if(!disposed)checking.value=false}
}
async function start(){
 if(!props.accepted||props.disabled||busy.value||!enabled.value)return
 clear();const current=version;busy.value=true;error.value=''
 try{
  const proof=await graph.verify()
  if(!proof||disposed||current!==version||!props.accepted)return
  const result=await buyer<{authorizeUrl:string;expiresIn:number}>('POST','/auth/wechat/authorize-url',{mode:'web',scene:'login',returnPath:props.returnPath,...proof})
  if(disposed||current!==version||!props.accepted)return
  expiresAt=Date.now()+result.expiresIn*1000
  const options=wechatQrOptions(result.authorizeUrl,location.origin,id)
  await loadWechatQr()
  if(disposed||current!==version||!props.accepted)return
  // 回调在原窗口处理，保留站内目的页与同意状态，不把登录令牌传入 iframe。
  sessionStorage.setItem('warmpaw:return',props.returnPath)
  sessionStorage.setItem('warmpaw:wechat-consent','accepted')
  active.value=true;remaining.value=Math.max(0,Math.ceil((expiresAt-Date.now())/1000))
  await nextTick()
  options.onReady=()=>clearTimeout(widgetTimer)
  widgetTimer=setTimeout(()=>{if(current===version&&!disposed){clear();error.value='微信二维码加载超时，请刷新重试'}},15000)
  new window.WxLogin!(options)
  cleanup=options.onCleanup
  const frame=document.querySelector<HTMLIFrameElement>(`#${id} iframe`)
  if(frame)frame.title='微信扫码登录二维码'
 }catch(e){if(!disposed&&current===version){clear();error.value=e instanceof Error?e.message:'微信扫码暂不可用'}}
 finally{busy.value=false}
}
onMounted(()=>{void checkCapability();timer=setInterval(()=>{if(!active.value)return;remaining.value=Math.max(0,Math.ceil((expiresAt-Date.now())/1000));if(!remaining.value){clear();error.value='二维码已过期，请刷新后重新扫码'}},1000)})
onUnmounted(()=>{disposed=true;clear();clearInterval(timer)})
</script>
<template>
 <MiniWechatQr v-if="miniEnabled" v-model:busy="busy" :accepted="accepted" :return-path="returnPath" :disabled="disabled"/>
 <aside v-else class="wechat-panel" aria-label="微信扫码登录">
  <h2><svg class="wechat-mark" viewBox="0 0 32 28" aria-hidden="true"><path fill="currentColor" d="M13 2C6.4 2 1 6.2 1 11.4c0 3 1.8 5.7 4.5 7.4l-1.1 3.4 4-2.1c1.4.4 3 .6 4.6.6h.8a8.7 8.7 0 0 1-1.2-4.4c0-5 4.7-9 10.5-9h.7C22 4.2 17.8 2 13 2Z"/><path fill="currentColor" d="M23 9c-4.9 0-9 3.3-9 7.3s4.1 7.3 9 7.3c1.2 0 2.4-.2 3.5-.5l3 1.7-.8-2.7c2-1.3 3.3-3.4 3.3-5.8C32 12.3 28 9 23 9Z"/><g fill="white"><circle cx="9" cy="9" r="1.2"/><circle cx="17" cy="9" r="1.2"/><circle cx="20" cy="15" r="1"/><circle cx="26" cy="15" r="1"/></g></svg>微信扫码登录</h2>
  <div v-show="active" :id="id" class="wechat-widget" />
  <div v-if="!active" class="wechat-placeholder">
   <svg viewBox="0 0 48 48" class="scan-mark" aria-hidden="true"><path d="M17 6H9a3 3 0 0 0-3 3v8m25-11h8a3 3 0 0 1 3 3v8M6 31v8a3 3 0 0 0 3 3h8m14 0h8a3 3 0 0 0 3-3v-8" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
   <p role="status">{{checking?'正在检查登录服务…':busy?'正在获取二维码…':!enabled?'微信扫码暂未开放':!accepted?'同意协议后获取二维码':'使用微信扫一扫登录'}}</p>
  </div>
  <p v-if="error" class="qr-error" role="alert">{{error}}</p>
  <button v-if="error&&!enabled" type="button" class="text-link" :disabled="checking" @click="checkCapability">重新检查</button>
  <button v-else-if="enabled" type="button" class="text-link" :disabled="!accepted||disabled||busy" @click="start">{{active?'刷新二维码':busy?'请稍候…':'获取登录二维码'}}</button>
  <p v-else-if="!checking&&!error" class="qr-note">请使用左侧手机号或密码登录</p>
  <p v-if="enabled" class="qr-note">首次扫码自动注册，无需手机号</p>
 </aside>
</template>
<style scoped>
.wechat-panel{border:1px solid var(--line);background:var(--sand);border-radius:20px;padding:24px 16px 20px;text-align:center;min-height:302px}
h2{display:flex;align-items:center;justify-content:center;gap:8px;font-size:14px;font-weight:500;margin:0 0 20px}
.wechat-mark{width:20px;height:18px;color:#07b337;flex-shrink:0}
.wechat-placeholder{width:174px;min-height:174px;background:var(--surface);border-radius:12px;margin:0 auto 18px;display:flex;flex-direction:column;justify-content:center;align-items:center;gap:16px;padding:16px 10px}
.scan-mark{width:42px;height:42px;color:#a99c90}
p{margin:0;font-size:12px;line-height:1.8;color:var(--muted)}
.text-link{font-size:13px}.qr-note{font-size:11px;margin-top:10px}.qr-error{color:var(--brand);margin-bottom:12px;overflow-wrap:anywhere}
.wechat-widget{height:238px;margin-bottom:8px}.wechat-widget :deep(iframe){width:100%;height:238px;display:block;border:0}
</style>
