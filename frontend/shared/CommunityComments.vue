<script setup lang="ts">
import {ref,computed,watch,nextTick} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {buyer,admin,auth,date,mutationKey} from './api'
import {communityLength} from './communityTypes'
import type {CommunityComment} from './communityTypes'
import {useLoad} from './useLoad'
const props=withDefaults(defineProps<{contentId:string;moderation?:boolean;canReply?:boolean}>(),{moderation:false,canReply:true})
const route=useRoute(),router=useRouter(),entries=ref<CommunityComment[]>([]),body=ref(''),replyTo=ref<CommunityComment>(),reportTarget=ref<CommunityComment>(),reason=ref(''),reportDialog=ref<HTMLDialogElement>()
const {loading,error,run}=useLoad(),key=mutationKey(),reportKey=mutationKey()
const client=props.moderation?admin:buyer,prefix=props.moderation?'/admin/community':'/community'
const logged=computed(()=>!!auth(props.moderation?'admin':'buyer')),roots=computed(()=>entries.value.filter(c=>!c.rootId))
const thread=(root:CommunityComment)=>[root,...entries.value.filter(c=>c.rootId===root.id)]
async function scrollToReply(){await nextTick();if(route.hash.startsWith('#comment-'))document.getElementById(route.hash.slice(1))?.scrollIntoView({block:'center'})}
const load=()=>run(async()=>{entries.value=await client('GET',`${prefix}/contents/${props.contentId}/comments`);await scrollToReply()})
watch(()=>props.contentId,load,{immediate:true});watch(()=>route.hash,scrollToReply)
const login=()=>router.push({path:'/login',query:{redirect:route.fullPath}})
const submit=()=>run(async()=>{
 if(!auth(props.moderation?'admin':'buyer')){await login();return}
 if(!body.value.trim()||communityLength(body.value)>500)throw new Error('评论不能为空且最多500个字符')
 const path=`${prefix}/contents/${props.contentId}/comments`,payload={body:body.value,...(replyTo.value?{replyToId:replyTo.value.id}:{})}
 await client('POST',path,payload,undefined,key(path,payload));body.value='';replyTo.value=undefined
 entries.value=await client('GET',`${prefix}/contents/${props.contentId}/comments`)
})
const remove=(entry:CommunityComment)=>{
 if(!window.confirm(entry.rootId?'删除这条回复？':'删除主评论后，下方整个讨论串都将不再展示。确认删除？'))return
 return run(async()=>{await client('DELETE',`${prefix}/comments/${entry.id}`);entries.value=await client('GET',`${prefix}/contents/${props.contentId}/comments`);if(replyTo.value?.id===entry.id)replyTo.value=undefined})
}
const hide=(entry:CommunityComment)=>run(async()=>{await admin('POST',`/admin/community/comment/${entry.id}/hide`);entries.value=await admin('GET',`${prefix}/contents/${props.contentId}/comments`)})
function openReport(entry:CommunityComment){if(!logged.value){void login();return}reportTarget.value=entry;reason.value='';reportDialog.value?.showModal()}
const report=()=>run(async()=>{const payload={targetType:'comment',targetId:reportTarget.value!.id,reason:reason.value},path='/community/reports';await buyer('POST',path,payload,undefined,reportKey(path,payload));reportDialog.value?.close()})
</script>
<template><section class="community-comments"><h2>讨论</h2><p v-if="loading" role="status">正在处理…</p><p v-if="error" class="error-text" role="alert">{{error}} <button class="text-link" :disabled="loading" @click="load">重试加载</button></p><p v-if="!loading&&!entries.length" class="muted">还没有评论，分享你的想法吧。</p>
 <div v-for="root in roots" :key="root.id" class="comment-thread"><article v-for="entry in thread(root)" :id="`comment-${entry.id}`" :key="entry.id" class="community-comment" :class="{reply:entry.rootId}"><div class="between"><strong>{{entry.authorName}} <span v-if="entry.isShop" class="status">店铺回复</span></strong><small class="muted">{{date(entry.createdAt)}}<span v-if="entry.status==='hidden'"> · 已隐藏</span></small></div><small v-if="entry.replyToId" class="muted">{{entry.replyToName==='原回复已删除'?'原回复已删除':`回复 ${entry.replyToName}`}}</small><p>{{entry.body}}</p><div class="actions"><button v-if="logged&&canReply&&entry.status==='published'" class="text-link" :disabled="loading" @click="replyTo=entry">回复</button><button v-if="entry.isOwner" class="text-link" :disabled="loading" @click="remove(entry)">删除</button><button v-if="moderation&&entry.status==='published'" class="text-link" :disabled="loading" @click="hide(entry)">隐藏</button><button v-if="!moderation&&logged" class="text-link" :disabled="loading" @click="openReport(entry)">举报</button></div></article></div>
 <form v-if="logged&&canReply" class="stack comment-compose" @submit.prevent="submit"><p v-if="replyTo">正在回复 {{replyTo.authorName}} <button type="button" class="text-link" @click="replyTo=undefined">取消回复</button></p><label>{{moderation?'店铺回复':'发表评论'}}<textarea v-model="body" rows="3" required maxlength="1000" :disabled="loading"></textarea><small>{{communityLength(body)}} / 500</small></label><button class="btn" :disabled="loading||!body.trim()||communityLength(body)>500">{{moderation?'发布店铺回复':'发布评论'}}</button></form><RouterLink v-else-if="!logged&&!moderation" class="btn secondary" :to="{path:'/login',query:{redirect:route.fullPath}}">登录后参与讨论</RouterLink>
 <dialog ref="reportDialog" class="community-report-dialog"><form class="stack" @submit.prevent="report"><h2>举报评论</h2><label>举报原因<textarea v-model="reason" required maxlength="500" rows="3"></textarea></label><p v-if="error" class="error-text" role="alert">{{error}}</p><div class="actions"><button class="btn" :disabled="loading||!reason.trim()">提交举报</button><button type="button" class="btn secondary" :disabled="loading" @click="reportDialog?.close()">取消</button></div></form></dialog>
</section></template>
<style scoped>.community-comments{margin-top:40px;--brand:#b9512b;--line:#e4dbd0;--sand:#f2ece2;--surface:#fff;--muted:#746b63}.community-comments .btn{background:var(--brand);color:#fff;border:1px solid var(--brand);border-radius:12px;padding:12px 24px;min-height:44px;font-size:14px}.community-comments .text-link{border:0;background:none;color:var(--brand);padding:8px 0;font-size:14px}.community-comments label{display:flex;flex-direction:column;gap:8px}.community-comments textarea{width:100%;padding:12px;border:1px solid var(--line);border-radius:12px;font:inherit;resize:vertical}.community-comments .between{display:flex;justify-content:space-between;gap:12px}.community-comments .error-text{color:#aa3020}.comment-thread{border-bottom:1px solid var(--line);padding:16px 0}.community-comment{scroll-margin-top:30px;overflow-wrap:anywhere}.community-comment p{white-space:pre-wrap;margin:8px 0}.community-comment.reply{margin:12px 0 0 20px;padding:14px;border-left:2px solid var(--line);background:var(--sand);border-radius:0 12px 12px 0}.community-comment .between{align-items:flex-start}.community-comment .status{font-size:11px}.comment-compose{margin-top:24px}.comment-compose>.btn{align-self:start}.community-report-dialog{width:min(540px,calc(100% - 32px));border:1px solid var(--line);border-radius:20px;padding:24px;color:inherit;background:var(--surface)}.community-report-dialog::backdrop{background:#0005}@media(max-width:760px){.community-comment.reply{margin-left:12px}.community-comment .between{flex-wrap:wrap;gap:4px}}</style>
