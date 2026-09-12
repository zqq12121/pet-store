<script setup lang="ts">
import {ref,onMounted} from 'vue';import {useRouter} from 'vue-router';import {buyer,clearAuth} from '../../../shared/api';import type {User} from '../../../shared/types';import {useLoad} from '../../../shared/useLoad'
const user=ref<User>(),nickname=ref(''),router=useRouter(),message=ref(''),{loading,error,run}=useLoad()
onMounted(()=>run(async()=>{user.value=await buyer('GET','/me');nickname.value=user.value!.nickname}))
const save=()=>run(async()=>{user.value=await buyer('PATCH','/me',{nickname:nickname.value.trim()});message.value='昵称已保存'})
const logout=()=>run(async()=>{await buyer('POST','/auth/logout');clearAuth('buyer');await router.replace('/')})
</script>
<template><div class="container page narrow"><p class="eyebrow">YOUR LITTLE WORLD</p><h1>你好，{{user?.nickname||'未来的家人'}}。</h1><p class="lead">{{user?.phoneMasked}}</p><div class="panel stack"><form class="stack" @submit.prevent="save"><label>昵称<input v-model="nickname" required maxlength="30"></label><button class="btn secondary" :disabled="loading||!nickname.trim()">保存昵称</button><p v-if="message" role="status">{{message}}</p></form><RouterLink to="/orders" class="profile-link">我的预约 →</RouterLink><RouterLink to="/ai" class="profile-link">AI 养宠助手 →</RouterLink><RouterLink to="/shop" class="profile-link">联系店主 →</RouterLink><button class="text-link" :disabled="loading" @click="logout">退出登录</button><p v-if="error" class="error-text" role="alert">{{error}}</p></div></div></template>
