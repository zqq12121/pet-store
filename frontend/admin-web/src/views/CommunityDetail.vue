<script setup lang="ts">
import {ref,watch} from 'vue'
import {useRoute} from 'vue-router'
import {admin,date,safeUrl} from '../../../shared/api'
import {categoryLabel} from '../../../shared/communityTypes'
import type {CommunityContent} from '../../../shared/communityTypes'
import {useLoad} from '../../../shared/useLoad'
import CommunityComments from '../../../shared/CommunityComments.vue'
const route=useRoute(),item=ref<CommunityContent>(),{loading,error,run}=useLoad()
const load=()=>run(async()=>{item.value=await admin('GET',`/admin/community/contents/${route.params.id}`);item.value!.images=await Promise.all(item.value!.images.map(async image=>({...image,url:(await admin<{url:string}>('GET',`/files/${image.id}/access-url`)).url})))})
watch(()=>route.params.id,load,{immediate:true})
const hide=()=>{if(!window.confirm('确认隐藏该内容及公开讨论？'))return;return run(async()=>{await admin('POST',`/admin/community/content/${item.value!.id}/hide`);item.value!.status='hidden'})}
</script>
<template><div class="community-admin-detail"><RouterLink to="/community"><el-button>返回社区管理</el-button></RouterLink><el-alert v-if="error" :title="error" type="error" :closable="false"/><el-button v-if="error" :disabled="loading" @click="load">重新加载</el-button><article v-if="item" class="panel"><div class="section-title"><h2>{{item.title||'店铺评价'}}</h2><el-tag>{{item.status==='published'?'公开':item.status==='hidden'?'已隐藏':'已删除'}}</el-tag></div><p>{{item.authorName}} · {{date(item.createdAt)}} · {{item.type==='post'?categoryLabel(item.category!):`${item.rating} 星评价`}}</p><p class="community-admin-body">{{item.body}}</p><div class="asset-grid"><a v-for="image in item.images" :key="image.id" :href="safeUrl(image.url)" target="_blank" rel="noopener noreferrer"><img :src="image.url" alt="社区内容配图"></a></div><el-button v-if="item.status==='published'" type="danger" :disabled="loading" @click="hide">隐藏内容</el-button></article><div v-if="item" class="panel"><CommunityComments :content-id="item.id" moderation :can-reply="item.type==='review'&&item.status==='published'"/></div></div></template>
<style scoped>.community-admin-detail>.el-button,.community-admin-detail>a{display:inline-block;margin-bottom:20px}.community-admin-body{white-space:pre-wrap;overflow-wrap:anywhere}</style>
