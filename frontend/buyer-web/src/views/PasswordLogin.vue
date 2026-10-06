<script setup lang="ts">
import {ref} from 'vue'
import {useRouter,useRoute} from 'vue-router'
import {buyer,saveAuth} from '../../../shared/api'
import type {LoginResult} from '../../../shared/types'
import {useLoad} from '../../../shared/useLoad'
import {useGraphCaptcha} from '../../../shared/useGraphCaptcha'
const graph=useGraphCaptcha()
const router=useRouter(),route=useRoute(),account=ref(''),password=ref(''),{loading,error,run}=useLoad()
async function submit(){if(loading.value)return;await run(async()=>{
 // 点击或按 Enter 提交后验证，通过前不请求密码登录接口。
 const proof=await graph.verify()
 if(!proof)return
 try{
  const result=await buyer<LoginResult>('POST','/auth/password-login',{account:account.value,password:password.value,...proof})
  saveAuth('buyer',result)
  // 仅允许站内跳转，兼容从预约页面进入登录。
  const target=route.query.redirect
  await router.replace(typeof target==='string'&&target.startsWith('/')&&!target.startsWith('//')?target:'/profile')
 }catch(e){password.value='';throw e}
})}
</script>
<template><div class="container page narrow"><form class="panel stack" @submit.prevent="submit"><h1>密码登录</h1>
<p v-if="route.query.changed" class="notice">密码已更新，请重新登录。</p>
<label>手机号或用户名<input v-model="account" :readonly="loading" required maxlength="32" autocomplete="username"></label>
<label>密码<input v-model="password" :readonly="loading" type="password" required maxlength="128" autocomplete="current-password"></label>
<p class="small muted">点击登录后，请完成图形验证。</p>
<p v-if="error" class="error-text" role="alert">{{error}}</p><button class="btn" :disabled="loading">登录</button>
<RouterLink :to="{path:'/password-reset'}">忘记密码／首次设置密码</RouterLink><RouterLink :to="{path:'/login',query:route.query}">使用短信验证码登录</RouterLink>
</form></div></template>
