<script setup lang="ts">
import {ref,onMounted} from 'vue';import {buyer} from '../../../shared/api';import type {Shop} from '../../../shared/types';import {useLoad} from '../../../shared/useLoad';import LoadState from '../components/LoadState.vue'
const shop=ref<Shop>(),copied=ref(false),{loading,error,run}=useLoad();const load=()=>run(async()=>{shop.value=await buyer('GET','/shop')});onMounted(load)
// 地图仅传递门店公开地址，不请求用户定位权限。
const navigationUrl=()=>`https://uri.amap.com/search?keyword=${encodeURIComponent(shop.value!.address)}&callnative=1`
const copy=()=>run(async()=>{await navigator.clipboard.writeText(shop.value!.wechat);copied.value=true})
</script>
<template><div class="container page"><p class="eyebrow">COME SAY HELLO</p><h1>来店里，见一面。</h1><p class="lead">我们相信，真实的相处比照片更能帮你作出决定。</p><LoadState :loading="loading" :error="error" @retry="load"/><div v-if="shop" class="shop-layout"><img src="/images/golden.jpg" alt="期待与你见面的小伙伴"><section class="panel stack"><h2>{{shop.name}}</h2><p>{{shop.address}}</p><p>{{shop.businessHours}}</p><p>{{shop.pickupInstructions}}</p><hr><h3>到店流程</h3><p>01 查看档案与检疫证明</p><p>02 当面观察健康与性格</p><p>03 买家确认后完成交付</p><div class="actions"><a class="btn secondary" :href="navigationUrl()" target="_blank" rel="noopener noreferrer">打开地图导航</a><a class="btn" :href="`tel:${shop.phone}`">拨打 {{shop.phone}}</a><button class="btn secondary" @click="copy">{{copied?'微信号已复制':'复制店主微信'}}</button></div><small class="muted">微信号：{{shop.wechat}}</small></section></div></div></template>
