<script setup lang="ts">
import {ref,onMounted} from 'vue'
import {useRouter,useRoute} from 'vue-router'
import {buyer,saveAuth} from '../../../shared/api'
import type {LoginResult} from '../../../shared/types'
import {useLoad} from '../../../shared/useLoad'
const router=useRouter(),route=useRoute(),account=ref(''),password=ref(''),captchaCode=ref('')
const captcha=ref<{captchaId:string;imageBase64:string}>(),{loading,error,run}=useLoad()
async function refresh(){captcha.value=await buyer('GET','/auth/captchas',undefined,{purpose:'password_login'});captchaCode.value=''}
async function submit(){await run(async()=>{
 try{
  const result=await buyer<LoginResult>('POST','/auth/password-login',{account:account.value,password:password.value,captchaId:captcha.value?.captchaId,captchaCode:captchaCode.value})
  saveAuth('buyer',result)
  // 仅允许站内跳转，兼容从预约页面进入登录。
  const target=route.query.redirect
  await router.replace(typeof target==='string'&&target.startsWith('/')&&!target.startsWith('//')?target:'/profile')
 }catch(e){password.value='';try{await refresh()}catch{}throw e}
})}
onMounted(()=>run(refresh))
</script>
<template><div class="container page narrow"><form class="panel stack" @submit.prevent="submit"><h1>密码登录</h1>
<p v-if="route.query.changed" class="notice">密码已更新，请重新登录。</p>
<label>手机号或用户名<input v-model="account" required maxlength="32" autocomplete="username"></label>
<label>密码<input v-model="password" type="password" required minlength="8" maxlength="128" autocomplete="current-password"></label>
<label>图片验证码<div class="captcha-row"><input v-model="captchaCode" required maxlength="6"><button type="button" class="captcha" aria-label="刷新图片验证码" @click="run(refresh)"><img v-if="captcha" :src="captcha.imageBase64" alt="图片验证码"><span v-else>加载验证码</span></button></div></label>
<p v-if="error" class="error-text" role="alert">{{error}}</p><button class="btn" :disabled="loading||!captcha">登录</button>
<RouterLink :to="{path:'/password-reset'}">忘记密码／首次设置密码</RouterLink><RouterLink :to="{path:'/login',query:route.query}">使用短信验证码登录</RouterLink>
</form></div></template>
