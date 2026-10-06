<script setup lang="ts">
import {ref,watch,onMounted,onUnmounted} from 'vue';import {useRoute,useRouter} from 'vue-router';import {buyer,saveAuth,isDemo,isJavaLocal} from '../../../shared/api';import type {LoginResult} from '../../../shared/types';import {useLoad} from '../../../shared/useLoad'
import {useGraphCaptcha} from '../../../shared/useGraphCaptcha'
const graph=useGraphCaptcha()
const route=useRoute(),router=useRouter(),phone=ref(''),smsCode=ref(''),smsRequestId=ref(''),count=ref(0),bindTicket=ref(''),smsNotice=ref(''),{loading,error,run}=useLoad();let timer:ReturnType<typeof setInterval>
// 能力检查失败时保持短信登录可用，不展示无法完成的微信入口。
const wechatEnabled=ref(false)
const validPath=(value:unknown)=>typeof value==='string'&&value.startsWith('/')&&!value.startsWith('//')?value:'/'
const destination=()=>validPath(route.query.redirect||sessionStorage.getItem('warmpaw:return'))
// 手机号变化后清除上一个号码的验证码凭据，避免提交到错误账号。
watch(phone,()=>{smsRequestId.value='';smsCode.value='';smsNotice.value=''})
async function sendCode(){
 if(!/^1[3-9]\d{9}$/.test(phone.value)){error.value='请输入正确的手机号';return}
 if(loading.value||count.value>0)return
 const targetPhone=phone.value
 smsNotice.value=''
 await run(async()=>{
  // 用户点击获取时才弹出；关闭弹窗不会发送短信。
  const proof=await graph.verify()
  if(!proof)return
  const result=await buyer<{smsRequestId:string;retryAfter:number;phoneMasked:string;status?:string}>('POST','/auth/sms-codes',{phone:targetPhone,purpose:bindTicket.value?'wechat_bind':'login',...proof,...(bindTicket.value?{bindTicket:bindTicket.value}:{})})
  count.value=result.retryAfter
  if(phone.value===targetPhone){smsRequestId.value=result.smsRequestId;smsNotice.value=result.status==='simulated'||isDemo?'模拟验证码已生成，请按页面提示获取。':`短信平台已受理，请查看 ${result.phoneMasked} 收到的验证码，5分钟内有效。`}
 })
}
async function login(){if(loading.value||!smsRequestId.value)return;await run(async()=>{const body={phone:phone.value,smsRequestId:smsRequestId.value,smsCode:smsCode.value};const result=await buyer<LoginResult>('POST',bindTicket.value?'/auth/wechat/bind-phone':'/auth/sms-login',bindTicket.value?{...body,bindTicket:bindTicket.value}:body);saveAuth('buyer',result);const target=destination();sessionStorage.removeItem('warmpaw:return');await router.replace(target)})}
async function wechat(){if(loading.value)return;await run(async()=>{const proof=await graph.verify();if(!proof)return;sessionStorage.setItem('warmpaw:return',destination());const result=await buyer<{authorizeUrl:string}>('POST','/auth/wechat/authorize-url',{scene:'login',returnPath:destination(),...proof});const url=new URL(result.authorizeUrl);if(url.protocol!=='https:'||url.hostname!=='open.weixin.qq.com')throw new Error('微信授权地址无效');location.assign(url.href)})}
onMounted(()=>{void buyer<{wechatLogin:boolean}>('GET','/auth/capabilities').then(result=>{wechatEnabled.value=result.wechatLogin}).catch(()=>{wechatEnabled.value=false});timer=setInterval(()=>{if(count.value>0)count.value--},1000);if(route.query.code&&route.query.state)void run(async()=>{const body={code:String(route.query.code),state:String(route.query.state)};await router.replace('/login');const result=await buyer<{status:string;login?:LoginResult;bindTicket?:string}>('POST','/auth/wechat/login',body);if(result.login){saveAuth('buyer',result.login);await router.replace(destination())}else if(result.status==='wechat_ready')await router.replace(destination());else bindTicket.value=result.bindTicket||''})})
onUnmounted(()=>clearInterval(timer))
</script>
<template><div class="container page login-layout"><img class="login-photo" src="/images/cat.jpg" alt="安静陪伴的猫咪"><form class="login-form panel" @submit.prevent="login"><p class="eyebrow">WELCOME HOME</p><h1>{{bindTicket?'绑定你的手机号':'你好，未来的家人。'}}</h1><p class="muted">登录后可预约到店、查看预约和交付记录。</p><p v-if="isJavaLocal" class="notice">本地联调：短信验证码保存在本机开发收件箱，不会发送真实短信。图形验证需配置阿里云认证方案。</p><p v-else-if="isDemo" class="notice">当前为演示环境，不发送真实短信；阿里云图形验证请连接 Java 服务进行联调。</p><label>手机号<input v-model="phone" type="tel" :readonly="loading" autocomplete="tel" pattern="1[3-9][0-9]{9}" required maxlength="11" placeholder="请输入手机号"></label><label>短信验证码<div class="captcha-row"><input v-model="smsCode" inputmode="numeric" pattern="[0-9]{6}" required maxlength="6" placeholder="输入6位验证码"><button type="button" class="btn secondary" :disabled="loading||count>0" @click="sendCode">{{count?`${count}秒后重发`:'获取验证码'}}</button></div></label><p v-if="smsNotice" class="notice" role="status">{{smsNotice}}</p><p v-if="error" class="error-text" role="alert">{{error}}</p><button class="btn full" :disabled="loading||!smsRequestId">{{loading?'请稍候…':'登录并继续'}}</button><button v-if="!isDemo&&!bindTicket&&wechatEnabled" type="button" class="btn secondary full" :disabled="loading" @click="wechat">微信授权快捷登录</button><RouterLink v-if="!bindTicket" :to="{path:'/password-login',query:{redirect:destination()}}">使用密码登录</RouterLink><RouterLink v-if="!bindTicket" to="/password-reset">忘记密码／首次设置密码</RouterLink><p class="small muted">获取短信验证码前需完成图形验证。手机号仅用于本人身份核验与门店交付联系。</p></form></div></template>
