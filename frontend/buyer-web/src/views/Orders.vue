<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { buyer, date, money } from '../../../shared/api';
import type { Page, Order } from '../../../shared/types';
import { useLoad } from '../../../shared/useLoad';
import LoadState from '../components/LoadState.vue';
import PageHead from '../components/PageHead.vue';
const result = ref<Page<Order>>(),
  tab = ref('all'),
  { loading, error, run } = useLoad();
const tabs = [
  ['all', '全部'],
  ['pending_confirmation', '待确认'],
  ['reservation_confirmed', '待到店'],
  ['pending_paid', '历史待付款'],
  ['pending_pickup', '待自提'],
  ['completed', '已完成'],
  ['after_sale', '售后'],
];
const load = (page = 1) =>
  run(async () => {
    result.value = await buyer('GET', '/orders', undefined, { tab: tab.value, page, pageSize: 10 });
  });
onMounted(() => load());
</script>
<template>
  <PageHead
    compact
    title="预约与订单"
    lead="线上占位不扣款。店长确认后按约定时间到店，看过满意再付款。"
  />
  <div class="container page">
    <div class="tabs order-tabs">
      <button
        v-for="item in tabs"
        :key="item[0]"
        :class="{ active: tab === item[0] }"
        @click="
          tab = item[0];
          load();
        "
      >
        {{ item[1] }}
      </button>
    </div>
    <LoadState :loading="loading" :error="error" @retry="load()" />
    <div v-if="result && !loading" class="stack">
      <article v-for="order in result.items" :key="order.id" class="panel order-list-item">
        <RouterLink :to="`/orders/${order.id}`" class="order-photo"
          ><img :src="order.product.coverUrl" :alt="order.product.name"
        /></RouterLink>
        <div class="order-main">
          <span class="status">{{ order.statusText }}</span>
          <h3>
            <RouterLink :to="`/orders/${order.id}`">{{ order.product.name }}</RouterLink
            ><small>{{ order.product.breed }}</small>
          </h3>
          <p v-if="order.appointment">到店时间：{{ date(order.appointment.visitAt) }}</p>
          <p class="small muted">订单号 {{ order.orderNo }}，{{ date(order.createdAt) }} 提交</p>
        </div>
        <div class="order-side">
          <strong class="price">{{ money(order.amount) }}</strong>
          <div class="actions">
            <RouterLink class="btn secondary" :to="`/orders/${order.id}`">{{
              order.appointment ? '预约详情' : '订单详情'
            }}</RouterLink
            ><RouterLink
              v-if="order.availableActions.includes('pay')"
              class="btn"
              :to="`/orders/${order.id}/payment`"
              >继续支付</RouterLink
            ><RouterLink
              v-if="order.availableActions.includes('view_pickup')"
              class="btn"
              :to="`/orders/${order.id}/pickup`"
              >查看自提凭证</RouterLink
            >
          </div>
        </div>
      </article>
      <div v-if="!result.items.length" class="empty">
        <h2>还没有这里的订单</h2>
        <p>去认识一位合拍的小伙伴吧。</p>
        <RouterLink class="btn" to="/pets">去找猫狗</RouterLink>
      </div>
      <div v-if="result.total > 10" class="pagination">
        <button class="btn secondary" :disabled="result.page === 1" @click="load(result.page - 1)">
          上一页</button
        ><span>{{ result.page }}</span
        ><button
          class="btn secondary"
          :disabled="result.page * 10 >= result.total"
          @click="load(result.page + 1)"
        >
          下一页
        </button>
      </div>
    </div>
  </div>
</template>
