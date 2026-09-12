<script setup lang="ts">
import {useRoute} from 'vue-router'
const route=useRoute()
// 旧收藏链接仍可打开，但不再发起微信或模拟支付。
</script>
<template><div class="container page narrow"><h1>已改为预约到店付款</h1><div class="panel stack"><p>线上不再收款。请查看预约信息，等待店长确认后按时到店。</p><p>如果这是历史待付款订单，可取消后重新预约，或联系门店处理。</p><RouterLink class="btn" :to="`/orders/${route.params.orderId}`">查看预约或订单</RouterLink><RouterLink class="text-link" to="/shop">联系门店</RouterLink></div></div></template>
