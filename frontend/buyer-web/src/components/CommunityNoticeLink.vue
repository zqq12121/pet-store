<script setup lang="ts">
import {ref,watch,onMounted,onBeforeUnmount} from 'vue'
import {useRoute} from 'vue-router'
import {auth,buyer} from '../../../shared/api'
import type {CommunityNotices} from '../../../shared/communityTypes'
const route=useRoute(),count=ref(0),logged=ref(false)
// 路由变化时同步登录状态和未读数；完整错误与重试在消息页展示。
async function refresh(){logged.value=!!auth('buyer');if(!logged.value){count.value=0;return}try{count.value=(await buyer<CommunityNotices>('GET','/community/notices')).unreadCount}catch{count.value=0}}
watch(()=>route.fullPath,refresh);onMounted(()=>{void refresh();window.addEventListener('community:notice-read',refresh)})
onBeforeUnmount(()=>window.removeEventListener('community:notice-read',refresh))
</script>
<template><RouterLink v-if="logged" to="/community/notices" class="text-link community-notice-link">消息<span v-if="count" class="unread" :aria-label="`${count}条未读消息`">{{count>99?'99+':count}}</span></RouterLink></template>
<style scoped>.community-notice-link{white-space:nowrap}.unread{display:inline-block;min-width:20px;background:var(--brand);color:white;border-radius:20px;padding:1px 5px;margin-left:5px;font-size:11px;text-align:center}</style>
