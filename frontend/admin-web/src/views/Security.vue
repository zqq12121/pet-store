<script setup lang="ts">
import {ref,onMounted} from 'vue'
import {useRouter} from 'vue-router'
import {admin,clearAuth,date} from '../../../shared/api'
import {useLoad} from '../../../shared/useLoad'
import {ElMessage} from 'element-plus'
const router=useRouter(),oldPassword=ref(''),newPassword=ref(''),confirm=ref('')
const security=ref<{username:string;passwordChangeAvailableAt:string|null}>(),{loading,error,run}=useLoad()
onMounted(()=>run(async()=>{security.value=await admin('GET','/admin/auth/security')}))
async function submit(){await run(async()=>{
 if(newPassword.value!==confirm.value)throw new Error('两次密码不一致')
 await admin('PUT','/admin/auth/password',{oldPassword:oldPassword.value,newPassword:newPassword.value})
 // 服务端已撤销全部管理员会话，清除本机凭证并返回登录页。
 clearAuth('admin');ElMessage.success('密码已修改，请重新登录');await router.replace('/login')
})}
</script>
<template><section class="panel"><h2>修改管理员密码</h2><p>账号：{{security?.username}}</p><p>密码每7天只能修改一次，修改后所有设备需重新登录。</p>
<p v-if="security?.passwordChangeAvailableAt">下次可修改时间：{{date(security.passwordChangeAvailableAt)}}</p>
<form class="stack" style="max-width:480px" @submit.prevent="submit">
<label>原密码<el-input v-model="oldPassword" type="password" show-password required autocomplete="current-password"/></label>
<label>新密码<el-input v-model="newPassword" type="password" show-password required minlength="12" maxlength="128" autocomplete="new-password"/></label>
<p>新密码须为12至128位，包含字母和数字。</p>
<label>确认新密码<el-input v-model="confirm" type="password" show-password required autocomplete="new-password"/></label>
<el-alert v-if="error" :title="error" type="error" :closable="false"/>
<el-button type="primary" native-type="submit" :loading="loading" :disabled="!security">修改密码</el-button>
</form></section></template>
