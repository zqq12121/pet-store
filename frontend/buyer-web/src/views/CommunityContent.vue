<script setup lang="ts">
import {ref,watch} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {auth,buyer,date,mutationKey,safeUrl} from '../../../shared/api'
import {categoryLabel} from '../../../shared/communityTypes'
import type {CommunityContent} from '../../../shared/communityTypes'
import {useLoad} from '../../../shared/useLoad'
import LoadState from '../components/LoadState.vue'
import CommunityComments from '../../../shared/CommunityComments.vue'
const route=useRoute(),router=useRouter(),item=ref<CommunityContent>(),{loading,error,run}=useLoad(),reportDialog=ref<HTMLDialogElement>(),reason=ref(''),reported=ref(false),reportKey=mutationKey()
const load=()=>run(async()=>{item.value=await buyer('GET',`/community/contents/${route.params.id}`)
 // 隐藏内容的作者可以授权预览图片，公开链接仍不可访问。
 if(item.value!.status==='hidden')item.value!.images=await Promise.all(item.value!.images.map(async image=>({...image,url:(await buyer<{url:string}>('GET',`/files/${image.id}/access-url`)).url})))
})
watch(()=>route.params.id,load,{immediate:true})
const login=()=>router.push({path:'/login',query:{redirect:route.fullPath}})
const reaction=(type:'like'|'favorite')=>run(async()=>{if(!auth('buyer')){await login();return}item.value=await buyer('PUT',`/community/contents/${item.value!.id}/reactions/${type}`,{active:!(type==='like'?item.value!.liked:item.value!.favorited)})})
const remove=()=>{
 const warning=item.value!.type==='review'?'删除后，该订单不能再次评价。确认删除这条评价？':'确认删除这篇帖子？'
 if(!window.confirm(warning))return
 return run(async()=>{await buyer('DELETE',`/community/contents/${item.value!.id}`);await router.replace({path:'/community',query:{type:item.value!.type}})})
}
function openReport(){if(!auth('buyer')){void login();return}reason.value='';reported.value=false;reportDialog.value?.showModal()}
const report=()=>run(async()=>{const payload={targetType:'content',targetId:item.value!.id,reason:reason.value},path='/community/reports';await buyer('POST',path,payload,undefined,reportKey(path,payload));reported.value=true;reportDialog.value?.close()})
</script>
<template><div class="container page narrow"><RouterLink to="/community" class="text-link">返回宠友社区</RouterLink><LoadState :loading="loading" :error="error" @retry="load"/><template v-if="item"><article class="panel community-content"><div class="between"><small class="muted">{{item.authorName}} · {{date(item.createdAt)}}</small><span class="status">{{item.type==='post'?categoryLabel(item.category!):'店铺评价'}}</span></div><h1>{{item.title||'到店消费评价'}}</h1><p v-if="item.type==='review'" class="community-rating" :aria-label="`${item.rating}星评价`">{{'★'.repeat(item.rating!)}}{{'☆'.repeat(5-item.rating!)}} {{item.rating}}分</p><p v-if="item.status==='hidden'" class="notice">此内容已被管理员隐藏，修改后仍保持隐藏状态。</p><p class="community-body">{{item.body}}</p><div class="community-images"><a v-for="image in item.images" :key="image.id" :href="safeUrl(image.url)" target="_blank" rel="noopener noreferrer"><img :src="image.url" alt="社区内容配图"></a></div><div class="actions"><template v-if="item.type==='post'&&item.status==='published'"><button class="btn secondary" :disabled="loading" :aria-pressed="item.liked" @click="reaction('like')">{{item.liked?'已点赞':'点赞'}} · {{item.likeCount}}</button><button class="btn secondary" :disabled="loading" :aria-pressed="item.favorited" @click="reaction('favorite')">{{item.favorited?'已收藏':'收藏'}}</button></template><RouterLink v-if="item.isOwner" class="text-link" :to="`/community/${item.id}/edit`">编辑</RouterLink><button v-if="item.isOwner" class="text-link" :disabled="loading" @click="remove">删除</button><button v-if="item.status==='published'" class="text-link" :disabled="loading" @click="openReport">举报</button></div><p v-if="reported" role="status">举报已提交，等待管理员处理。</p></article><CommunityComments :content-id="item.id" :can-reply="item.status==='published'"/></template>
 <dialog ref="reportDialog" class="community-report-dialog"><form class="stack" @submit.prevent="report"><h2>举报内容</h2><label>举报原因<textarea v-model="reason" required maxlength="500" rows="4"></textarea></label><p v-if="error" class="error-text" role="alert">{{error}}</p><div class="actions"><button class="btn" :disabled="loading||!reason.trim()">提交举报</button><button class="btn secondary" type="button" :disabled="loading" @click="reportDialog?.close()">取消</button></div></form></dialog>
</div></template>
<style scoped>.community-content h1{overflow-wrap:anywhere;font-size:30px;margin:20px 0}.community-content .between{flex-wrap:wrap}.community-body{white-space:pre-wrap;overflow-wrap:anywhere}.community-rating{color:var(--brand);font-size:22px}.community-images{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin:24px 0}.community-images img{width:100%;aspect-ratio:1;object-fit:cover;border-radius:12px}.community-report-dialog{width:min(540px,calc(100% - 32px));border:1px solid var(--line);border-radius:20px;padding:24px;background:var(--surface);color:inherit}.community-report-dialog::backdrop{background:#0005}@media(max-width:760px){.community-content h1{font-size:25px}.community-images{gap:8px}}</style>
