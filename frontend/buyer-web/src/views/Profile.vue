<script setup lang="ts">
import {ref,onMounted} from 'vue'
import {useRouter} from 'vue-router'
import {buyer,clearAuth,date} from '../../../shared/api'
import type {User} from '../../../shared/types'
import {useLoad} from '../../../shared/useLoad'
const user=ref<User>(),nickname=ref(''),username=ref(''),router=useRouter(),message=ref(''),{loading,error,run}=useLoad()
const oldPassword=ref(''),newPassword=ref(''),confirm=ref('')
onMounted(()=>run(async()=>{user.value=await buyer('GET','/me');nickname.value=user.value!.nickname;username.value=user.value!.username||''}))
const save=()=>run(async()=>{user.value=await buyer('PATCH','/me',{nickname:nickname.value.trim()});message.value='昵称已保存'})
const saveUsername=()=>run(async()=>{user.value=await buyer('PUT','/me/username',{username:username.value});username.value=user.value!.username!;message.value='用户名已保存'})
const logout=()=>run(async()=>{await buyer('POST','/auth/logout');clearAuth('buyer');await router.replace('/')})
const changePassword=()=>run(async()=>{
 if(newPassword.value!==confirm.value)throw new Error('两次密码不一致')
 await buyer('PUT','/me/password',{oldPassword:oldPassword.value,newPassword:newPassword.value})
 // 服务端撤销所有会话，本机同步清理并重新登录。
 clearAuth('buyer');await router.replace('/password-login?changed=1')
})
</script>
<template><div class="container page narrow"><p class="eyebrow">YOUR LITTLE WORLD</p><h1>你好，{{user?.nickname||'未来的家人'}}。</h1><p class="lead">{{user?.phoneMasked}}</p><div class="panel stack">
<form class="stack" @submit.prevent="save"><label>昵称<input v-model="nickname" required maxlength="30"></label><button class="btn secondary" :disabled="loading||!user||!nickname.trim()">保存昵称</button></form>
<form class="stack" @submit.prevent="saveUsername"><h2>登录用户名</h2><label>用户名<input v-model="username" required pattern="[a-zA-Z][a-zA-Z0-9_]{2,31}" minlength="3" maxlength="32" autocomplete="username"></label>
<p class="small muted">3至32位，以字母开头，可含数字和下划线，不区分大小写；每3天只能修改一次。</p>
<p v-if="user?.usernameChangeAvailableAt">下次可修改时间：{{date(user.usernameChangeAvailableAt)}}</p><button class="btn secondary" :disabled="loading||!user">保存用户名</button></form>
<form v-if="user?.hasPassword" class="stack" @submit.prevent="changePassword"><h2>修改密码</h2><p>每7天只能修改或重置一次，成功后所有设备需重新登录。</p>
<p v-if="user.passwordChangeAvailableAt">下次可修改时间：{{date(user.passwordChangeAvailableAt)}}</p>
<label>原密码<input v-model="oldPassword" type="password" required autocomplete="current-password"></label>
<label>新密码<input v-model="newPassword" type="password" required minlength="12" maxlength="128" pattern="(?=.*[a-zA-Z])(?=.*[0-9]).{12,128}" autocomplete="new-password"></label><p class="small muted">12至128位，包含字母和数字。</p>
<label>确认新密码<input v-model="confirm" type="password" required minlength="12" maxlength="128" autocomplete="new-password"></label><button class="btn secondary" :disabled="loading">修改密码</button></form>
<RouterLink to="/password-reset">{{user?.hasPassword?'忘记密码？通过手机号重置':'首次设置密码（验证手机号）'}}</RouterLink>
<p v-if="message" role="status">{{message}}</p><p v-if="error" class="error-text" role="alert">{{error}}</p>
<RouterLink to="/orders" class="profile-link">我的预约 →</RouterLink><RouterLink to="/ai" class="profile-link">AI 养宠助手 →</RouterLink><RouterLink to="/shop" class="profile-link">联系店主 →</RouterLink><button class="text-link" :disabled="loading" @click="logout">退出登录</button>
</div></div></template>
