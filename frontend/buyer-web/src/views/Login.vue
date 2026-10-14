<script setup lang="ts">
import {ref,watch,onMounted,onUnmounted} from 'vue';import {useRoute,useRouter} from 'vue-router';import {buyer,saveAuth,isDemo,isJavaLocal} from '../../../shared/api';import type {LoginResult} from '../../../shared/types';import {useLoad} from '../../../shared/useLoad'
import {useGraphCaptcha} from '../../../shared/useGraphCaptcha'
import LoginConsent from '../components/LoginConsent.vue'
import BuyerLoginLayout from '../components/BuyerLoginLayout.vue'
import WechatQrLogin from '../components/WechatQrLogin.vue'
const graph=useGraphCaptcha()
const accepted=ref(false)
// 在图形认证和短信请求前确认同意，避免未勾选时已向第三方发送信息。
function requireConsent(){if(accepted.value)return true;error.value='请先阅读并同意《用户服务协议》和《隐私政策》';return false}
const route=useRoute(),router=useRouter(),phone=ref(''),smsCode=ref(''),smsRequestId=ref(''),count=ref(0),bindTicket=ref(''),smsNotice=ref(''),{loading,error,run}=useLoad();let timer:ReturnType<typeof setInterval>
const wechatBusy=ref(false)
const validPath=(value:unknown)=>typeof value==='string'&&value.startsWith('/')&&!value.startsWith('//')&&!/[\\\r\n]|%2f|%5c/i.test(value)?value:'/'
const destination=()=>validPath(route.query.redirect||sessionStorage.getItem('warmpaw:return'))
// 手机号变化后清除上一个号码的验证码凭据，避免提交到错误账号。
watch(phone,()=>{smsRequestId.value='';smsCode.value='';smsNotice.value=''})
async function sendCode(){
 if(!requireConsent())return
 if(!/^1[3-9]\d{9}$/.test(phone.value)){error.value='请输入正确的手机号';return}
 if(loading.value||wechatBusy.value||count.value>0)return
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
async function login(){if(!requireConsent()||loading.value||wechatBusy.value||!smsRequestId.value)return;await run(async()=>{const body={phone:phone.value,smsRequestId:smsRequestId.value,smsCode:smsCode.value};const result=await buyer<LoginResult>('POST',bindTicket.value?'/auth/wechat/bind-phone':'/auth/sms-login',bindTicket.value?{...body,bindTicket:bindTicket.value}:body);saveAuth('buyer',result);const target=destination();sessionStorage.removeItem('warmpaw:return');await router.replace(target)})}
onMounted(()=>{
 timer=setInterval(()=>{if(count.value>0)count.value--},1000)
 if(route.query.state)void run(async()=>{
  // 先保存目的页再移除一次性凭据，避免刷新重放以及丢失预约等原入口。
  const target=destination(),code=route.query.code,state=String(route.query.state)
  accepted.value=sessionStorage.getItem('warmpaw:wechat-consent')==='accepted'
  sessionStorage.removeItem('warmpaw:wechat-consent')
  await router.replace({path:'/login',query:target==='/'?{}:{redirect:target}})
  if(!code)throw new Error('微信授权已取消，可以重新扫码或使用其他方式登录')
  const result=await buyer<{status:string;login?:LoginResult;bindTicket?:string}>('POST','/auth/wechat/login',{code:String(code),state})
  if(result.login){saveAuth('buyer',result.login);sessionStorage.removeItem('warmpaw:return');await router.replace(target)}
  else if(result.status==='wechat_ready')await router.replace(target)
  else if(result.bindTicket)bindTicket.value=result.bindTicket
  else throw new Error('微信登录未完成，请重新扫码')
 })
})
onUnmounted(()=>clearInterval(timer))
</script>
<template>
 <BuyerLoginLayout>
  <form @submit.prevent="login">
   <h1 class="auth-title">{{bindTicket?'绑定手机号':'登录暖爪'}}</h1>
   <p v-if="bindTicket" class="notice">请绑定手机号，完成本次微信网页授权。</p>
   <p v-if="isJavaLocal" class="notice">本地联调：短信保存在开发收件箱，不发送真实短信。</p>
   <p v-else-if="isDemo" class="notice">当前为演示环境，不发送真实短信。</p>
   <label class="auth-field"><span class="phone-prefix" aria-hidden="true">+86</span><input v-model="phone" aria-label="手机号" type="tel" :readonly="loading||wechatBusy" autocomplete="tel" pattern="1[3-9][0-9]{9}" required maxlength="11" placeholder="请输入手机号"></label>
   <label class="auth-field"><input v-model="smsCode" aria-label="短信验证码" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" required maxlength="6" :readonly="loading||wechatBusy" placeholder="请输入验证码"><button type="button" class="send-code" :disabled="loading||wechatBusy||count>0" @click="sendCode">{{count?`${count}秒后重发`:'发送验证码'}}</button></label>
   <p v-if="smsNotice" class="notice" role="status">{{smsNotice}}</p>
   <div><LoginConsent v-model="accepted" :disabled="loading||wechatBusy"/><p class="auth-caption">未注册的手机号将自动注册</p></div>
   <p v-if="error" class="error-text" role="alert">{{error}}</p>
   <button class="btn full auth-submit" :disabled="loading||wechatBusy||!smsRequestId||!accepted">{{loading?'请稍候…':'登录'}}</button>
   <div v-if="!bindTicket" class="auth-links"><RouterLink :to="{path:'/password-login',query:{redirect:destination()}}">密码登录</RouterLink><span aria-hidden="true">|</span><RouterLink to="/password-reset">忘记密码</RouterLink></div>
  </form>
  <template #wechat><WechatQrLogin v-if="!bindTicket" v-model:busy="wechatBusy" :accepted="accepted" :return-path="destination()" :disabled="loading"/></template>
 </BuyerLoginLayout>
</template>
