<script setup lang="ts">
import {ref,watch,onMounted,onUnmounted} from 'vue'
import {useRouter} from 'vue-router'
import {buyer,clearAuth} from '../../../shared/api'
import {useLoad} from '../../../shared/useLoad'
const router=useRouter(),phone=ref(''),smsCode=ref(''),smsRequestId=ref(''),captchaCode=ref(''),password=ref(''),confirm=ref(''),notice=ref(''),count=ref(0)
const captcha=ref<{captchaId:string;imageBase64:string}>(),{loading,error,run}=useLoad()
let timer:ReturnType<typeof setInterval>
async function refresh(){captcha.value=await buyer('GET','/auth/captchas',undefined,{purpose:'sms'});captchaCode.value=''}
watch(phone,()=>{smsCode.value='';smsRequestId.value='';notice.value=''})
async function send(){await run(async()=>{
 if(!/^1[3-9]\d{9}$/.test(phone.value))throw new Error('请输入正确的手机号')
 try{
  const result=await buyer<{smsRequestId:string;retryAfter:number;status:string}>('POST','/auth/sms-codes',{phone:phone.value,purpose:'password_reset',captchaId:captcha.value?.captchaId,captchaCode:captchaCode.value})
  smsRequestId.value=result.smsRequestId;count.value=result.retryAfter
  notice.value=result.status==='simulated'?'模拟验证码已生成，请查看本地开发收件箱。':'短信平台已受理，请填写手机收到的验证码，5分钟内有效。'
 }finally{captcha.value=undefined;try{await refresh()}catch{}}
})}
async function submit(){await run(async()=>{
 if(password.value!==confirm.value)throw new Error('两次密码不一致')
 // 验证码可能已被服务端消费；失败后要求重新获取，不能重复提交旧凭据。
 try{await buyer('POST','/auth/password-reset',{phone:phone.value,smsRequestId:smsRequestId.value,smsCode:smsCode.value,newPassword:password.value})}
 catch(e){smsRequestId.value='';smsCode.value='';throw e}
 clearAuth('buyer');await router.replace('/password-login?changed=1')
})}
onMounted(()=>{void run(refresh);timer=setInterval(()=>{if(count.value>0)count.value--},1000)})
onUnmounted(()=>clearInterval(timer))
</script>
<template><div class="container page narrow"><form class="panel stack" @submit.prevent="submit"><h1>设置或重置密码</h1>
<p class="muted">验证已绑定的手机号。密码设置、修改和重置共用7天限制；成功后需重新登录。新用户请先短信登录。</p>
<label>手机号<input v-model="phone" :readonly="loading" type="tel" required pattern="1[3-9][0-9]{9}" maxlength="11" autocomplete="tel"></label>
<label>图片验证码<div class="captcha-row"><input v-model="captchaCode" maxlength="6"><button type="button" class="captcha" aria-label="刷新图片验证码" @click="run(refresh)"><img v-if="captcha" :src="captcha.imageBase64" alt="图片验证码"><span v-else>加载验证码</span></button></div></label>
<label>短信验证码<div class="captcha-row"><input v-model="smsCode" required pattern="[0-9]{6}" maxlength="6" autocomplete="one-time-code"><button type="button" class="btn secondary" :disabled="loading||count>0||!captcha||!captchaCode" @click="send">{{count?count+'秒后重发':'获取验证码'}}</button></div></label>
<label>新密码<input v-model="password" type="password" required minlength="12" maxlength="128" pattern="(?=.*[a-zA-Z])(?=.*[0-9]).{12,128}" autocomplete="new-password"></label>
<p class="small muted">12至128位，包含字母和数字。</p>
<label>确认新密码<input v-model="confirm" type="password" required minlength="12" maxlength="128" autocomplete="new-password"></label>
<p v-if="notice" role="status">{{notice}}</p><p v-if="error" class="error-text" role="alert">{{error}}</p>
<button class="btn" :disabled="loading||!smsRequestId">确认设置密码</button><RouterLink to="/login">返回登录</RouterLink>
</form></div></template>
