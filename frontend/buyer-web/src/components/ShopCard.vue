<script setup lang="ts">import type {Shop} from '../../../shared/types';// 未设置公开电话时不显示拨号入口。
defineProps<{shop:Shop}>()</script>
<template><section class="shop-card"><h3>{{shop.name}}</h3><p>到店看见真实的它，再开始一段陪伴。</p><p>{{shop.address}}</p><p>{{shop.businessHours}} · 仅到店自提</p><div class="actions"><RouterLink to="/shop" class="btn secondary">查看到店指南</RouterLink><a v-if="shop.phone" :href="`tel:${shop.phone}`" class="text-link">联系店主</a></div></section></template>
