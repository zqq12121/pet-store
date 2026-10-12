<script setup lang="ts">
import {ref,computed,onMounted} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {buyer,mutationKey} from '../../../shared/api'
import {communityCategories,communityLength} from '../../../shared/communityTypes'
import type {CommunityContent,CommunityImage,ReviewOrder} from '../../../shared/communityTypes'
import {useLoad} from '../../../shared/useLoad'
import CommunityImages from '../components/CommunityImages.vue'
const route=useRoute(),router=useRouter(),editing=!!route.params.id,type=ref<'post'|'review'>(route.query.type==='review'?'review':'post'),title=ref(''),body=ref(''),category=ref('experience'),rating=ref(5),orderId=ref(''),orders=ref<ReviewOrder[]>([]),images=ref<CommunityImage[]>([]),uploading=ref(false),ready=ref(false),{loading,error,run}=useLoad(),key=mutationKey()
const limit=computed(()=>type.value==='post'?5000:1000),eligible=computed(()=>orders.value.filter(o=>!o.reviewed))
const load=()=>run(async()=>{
 if(editing){const item=await buyer<CommunityContent>('GET',`/community/contents/${route.params.id}`);if(!item.isOwner)throw new Error('只能编辑自己的内容');type.value=item.type;title.value=item.title||'';body.value=item.body;category.value=item.category||'experience';rating.value=item.rating||5
  // 原图片通过本人授权预览，隐藏状态下也不公开图片。
  images.value=await Promise.all(item.images.map(async image=>({...image,url:(await buyer<{url:string}>('GET',`/files/${image.id}/access-url`)).url})))
 }else if(type.value==='review'){orders.value=await buyer('GET','/community/eligible-orders');if(typeof route.query.orderId==='string')orderId.value=route.query.orderId}
 ready.value=true
})
onMounted(load)
const save=()=>run(async()=>{
 if(!body.value.trim()||communityLength(body.value)>limit.value)throw new Error(`正文不能为空且最多${limit.value}个字符`)
 if(type.value==='post'&&(!title.value.trim()||communityLength(title.value)>80))throw new Error('标题不能为空且最多80个字符')
 if(type.value==='review'&&!editing&&!eligible.value.some(o=>o.id===orderId.value))throw new Error('请选择尚未评价的已完成订单')
 const fields={body:body.value,imageFileIds:images.value.map(i=>i.id),...(type.value==='post'?{title:title.value,category:category.value}:{rating:rating.value})}
 const path=editing?`/community/contents/${route.params.id}`:'/community/contents',payload=editing?fields:{...fields,type:type.value,...(type.value==='review'?{orderId:orderId.value}:{})}
 const result=await buyer<CommunityContent>(editing?'PUT':'POST',path,payload,undefined,editing?undefined:key(path,payload));await router.replace(`/community/${result.id}`)
})
</script>
<template><div class="container page narrow"><RouterLink :to="{path:'/community',query:{type}}" class="text-link">返回宠友社区</RouterLink><h1>{{editing?'编辑':type==='post'?'发布':'发表'}}{{type==='post'?'养宠帖子':'店铺评价'}}</h1><p v-if="loading" role="status">正在处理…</p><p v-if="error" class="error-text" role="alert">{{error}} <button v-if="!ready" class="text-link" :disabled="loading" @click="load">重新加载</button></p><form v-if="ready" class="panel stack" @submit.prevent="save"><template v-if="type==='post'"><label>标题<input v-model="title" required maxlength="160"><small>{{communityLength(title)}} / 80</small></label><label>分类<select v-model="category"><option v-for="item in communityCategories" :key="item.value" :value="item.value">{{item.label}}</option></select></label></template><template v-else><template v-if="!editing"><label>已完成交付的订单<select v-model="orderId" required><option value="" disabled>请选择订单</option><option v-for="order in eligible" :key="order.id" :value="order.id">{{order.petName}} · {{order.orderNo}}</option></select></label><p v-if="!eligible.length" class="notice">暂无可评价订单。完成到店交付后才能评价，已评价的订单不能再次评价。</p><RouterLink v-if="!eligible.length" class="text-link" to="/orders">查看我的预约与订单</RouterLink></template><label>总体评分<select v-model.number="rating"><option v-for="n in 5" :key="n" :value="n">{{n}} 星</option></select></label></template><label>{{type==='post'?'正文':'评价内容'}}<textarea v-model="body" rows="9" required :maxlength="limit*2"></textarea><small>{{communityLength(body)}} / {{limit}}</small></label><CommunityImages v-model="images" @busy="uploading=$event"/><p v-if="type==='review'" class="small muted">每个已完成订单只能评价一次；删除评价后也不能重新评价。</p><div class="actions"><button class="btn" :disabled="loading||uploading||!body.trim()||communityLength(body)>limit||(type==='post'&&(!title.trim()||communityLength(title)>80))||(type==='review'&&!editing&&!eligible.length)">{{editing?'保存修改':'发布'}}</button><RouterLink class="btn secondary" :to="editing?`/community/${route.params.id}`:'/community'">取消</RouterLink></div></form></div></template>
