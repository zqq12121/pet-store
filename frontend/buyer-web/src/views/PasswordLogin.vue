<script setup lang="ts">
import {ref} from 'vue'
import {useRouter,useRoute} from 'vue-router'
import {buyer,saveAuth} from '../../../shared/api'
import type {LoginResult} from '../../../shared/types'
import {useLoad} from '../../../shared/useLoad'
import {useGraphCaptcha} from '../../../shared/useGraphCaptcha'
import LoginConsent from '../components/LoginConsent.vue'
import BuyerLoginLayout from '../components/BuyerLoginLayout.vue'
import WechatQrLogin from '../components/WechatQrLogin.vue'
const graph=useGraphCaptcha()
const accepted=ref(false)
const wechatBusy=ref(false)
const router=useRouter(),route=useRoute(),account=ref(''),password=ref(''),{loading,error,run}=useLoad()
const destination=()=>{const target=route.query.redirect;return typeof target==='string'&&target.startsWith('/')&&!target.startsWith('//')&&!/[\\\r\n]|%2f|%5c/i.test(target)?target:'/profile'}
async function submit(){
 // 按钮和 Enter 共用校验，未同意时不加载图形认证或提交账号密码。
 if(!accepted.value){error.value='请先阅读并同意《用户服务协议》和《隐私政策》';return}
 if(loading.value||wechatBusy.value)return;await run(async()=>{
 // 点击或按 Enter 提交后验证，通过前不请求密码登录接口。
 const proof=await graph.verify()
 if(!proof)return
 try{
  const result=await buyer<LoginResult>('POST','/auth/password-login',{account:account.value,password:password.value,...proof})
  saveAuth('buyer',result)
  // 仅允许站内跳转，兼容从预约页面进入登录。
  await router.replace(destination())
 }catch(e){password.value='';throw e}
})}
</script>
<template><BuyerLoginLayout><form @submit.prevent="submit"><h1 class="auth-title">密码登录</h1>
<p v-if="route.query.changed" class="notice">密码已更新，请重新登录。</p>
<label class="auth-field"><input v-model="account" aria-label="手机号或用户名" placeholder="请输入手机号或用户名" :readonly="loading||wechatBusy" required maxlength="32" autocomplete="username"></label>
<label class="auth-field"><input v-model="password" aria-label="密码" placeholder="请输入密码" :readonly="loading||wechatBusy" type="password" required maxlength="128" autocomplete="current-password"></label>
<LoginConsent v-model="accepted" :disabled="loading||wechatBusy"/>
<p v-if="error" class="error-text" role="alert">{{error}}</p><button class="btn full auth-submit" :disabled="loading||wechatBusy||!accepted">{{loading?'请稍候…':'登录'}}</button>
<div class="auth-links"><RouterLink :to="{path:'/login',query:{redirect:destination()}}">验证码登录</RouterLink><span aria-hidden="true">|</span><RouterLink to="/password-reset">忘记密码</RouterLink></div>
</form><template #wechat><WechatQrLogin v-model:busy="wechatBusy" :accepted="accepted" :return-path="destination()" :disabled="loading"/></template></BuyerLoginLayout></template>
