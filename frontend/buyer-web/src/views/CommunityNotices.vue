<script setup lang="ts">
import {ref,onMounted} from 'vue'
import {useRouter} from 'vue-router'
import {buyer,date} from '../../../shared/api'
import type {CommunityNotice,CommunityNotices} from '../../../shared/communityTypes'
import {useLoad} from '../../../shared/useLoad'
import LoadState from '../components/LoadState.vue'
const result=ref<CommunityNotices>(),router=useRouter(),{loading,error,run}=useLoad()
const load=()=>run(async()=>{result.value=await buyer('GET','/community/notices')});onMounted(load)
// 已失效的通知仍可标为已读，但不跳转到已删除或隐藏的讨论。
const open=(notice:CommunityNotice)=>run(async()=>{await buyer('PUT',`/community/notices/${notice.id}/read`);notice.read=true;result.value!.unreadCount=result.value!.items.filter(n=>!n.read).length;window.dispatchEvent(new Event('community:notice-read'));if(notice.available)await router.push(`/community/${notice.contentId}#comment-${notice.commentId}`)})
</script>
<template><div class="container page narrow"><RouterLink to="/community" class="text-link">返回宠友社区</RouterLink><h1>社区消息</h1><p v-if="result" class="lead">{{result.unreadCount}} 条未读消息</p><LoadState :loading="loading" :error="error" @retry="load"/><template v-if="result"><p v-if="!result.items.length" class="panel muted">还没有互动消息。</p><div class="stack"><button v-for="notice in result.items" :key="notice.id" class="panel community-notice" :disabled="loading" @click="open(notice)"><div class="between"><strong>{{notice.actorName}} {{notice.message}} <span v-if="!notice.read" class="status">未读</span></strong><small>{{date(notice.createdAt)}}</small></div><p>{{notice.available?'点击查看讨论':'原内容已删除或隐藏'}}{{!notice.available&&!notice.read?'，点击标为已读':''}}</p></button></div></template></div></template>
<style scoped>.community-notice{width:100%;text-align:left;color:inherit;border:1px solid var(--line)}.community-notice p{margin:12px 0 0;color:var(--muted);font-size:14px}.community-notice .between{flex-wrap:wrap}</style>
