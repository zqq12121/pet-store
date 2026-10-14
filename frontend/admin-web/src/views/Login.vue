<script setup lang="ts">
import {reactive} from 'vue';import {useRouter,useRoute} from 'vue-router';import {admin,saveAuth} from '../../../shared/api';import {useLoad} from '../../../shared/useLoad'
const router=useRouter(),route=useRoute(),form=reactive({username:'',password:''}),{loading,error,run}=useLoad()
async function submit(){if(loading.value)return;await run(async()=>{
 // 后台只提交账号密码，点击和 Enter 共用提交路径；角色由服务器识别。
 const result=await admin<{accessToken:string;expiresIn:number}>('POST','/admin/auth/login',{...form})
 saveAuth('admin',result)
 const next=String(route.query.redirect||'/')
 await router.replace(next.startsWith('/')&&!next.startsWith('//')?next:'/')
})}
</script>
<template>
<!-- 登录只保留一个表单，输入框按 Enter 与点击按钮走同一提交逻辑。 -->
<div class="login-page"><el-form class="login-panel" label-position="top" @submit.prevent="submit"><span class="brand">暖爪</span><p class="eyebrow">STORE ACCESS</p><h1>欢迎回到暖爪。</h1><p>店长与员工共用入口，登录后进入各自的工作台。</p><el-form-item label="后台账号"><el-input v-model="form.username" :readonly="loading" required autocomplete="username" maxlength="32"/></el-form-item><el-form-item label="密码"><el-input v-model="form.password" :readonly="loading" required type="password" show-password autocomplete="current-password"/></el-form-item><el-alert v-if="error" :title="error" type="error" :closable="false"/><el-button type="primary" native-type="submit" :loading="loading" size="large">登录工作台</el-button></el-form></div></template>
