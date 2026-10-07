<script setup lang="ts">
import {reactive} from 'vue';import {useRouter,useRoute} from 'vue-router';import {admin,saveAuth,isDemo} from '../../../shared/api';import {useLoad} from '../../../shared/useLoad'
import {useGraphCaptcha} from '../../../shared/useGraphCaptcha'
const graph=useGraphCaptcha()
const router=useRouter(),route=useRoute(),form=reactive({username:'',password:''}),{loading,error,run}=useLoad()
async function submit(){if(loading.value)return;await run(async()=>{
 // 点击和 Enter 共用验证流程，取消验证不请求登录；每次重试使用新的凭据。
 const proof=await graph.verify()
 if(!proof)return
 const result=await admin<{accessToken:string;expiresIn:number}>('POST','/admin/auth/login',{...form,...proof})
 saveAuth('admin',result)
 const next=String(route.query.redirect||'/')
 await router.replace(next.startsWith('/')&&!next.startsWith('//')?next:'/')
})}
</script>
<template>
<!-- 登录只保留一个表单，输入框按 Enter 与点击按钮走同一提交逻辑。 -->
<div class="login-page"><el-form class="login-panel" label-position="top" @submit.prevent="submit"><span class="brand">暖爪</span><p class="eyebrow">STORE ACCESS</p><h1>欢迎回来，店主。</h1><p>照顾好每一只，也照顾好每一份托付。</p><el-alert v-if="isDemo" title="阿里云人机验证请连接 Java 服务进行联调。" type="info" :closable="false"/><el-form-item label="管理员账号"><el-input v-model="form.username" :readonly="loading" required autocomplete="username" maxlength="32"/></el-form-item><el-form-item label="密码"><el-input v-model="form.password" :readonly="loading" required type="password" show-password autocomplete="current-password"/></el-form-item><el-alert v-if="error" :title="error" type="error" :closable="false"/><el-button type="primary" native-type="submit" :loading="loading" size="large">登录工作台</el-button></el-form></div></template>
