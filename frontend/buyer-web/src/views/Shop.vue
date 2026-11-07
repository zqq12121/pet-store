<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { buyer } from '../../../shared/api';
import type { Shop } from '../../../shared/types';
import { useLoad } from '../../../shared/useLoad';
import LoadState from '../components/LoadState.vue';
import PageHead from '../components/PageHead.vue';
const shop = ref<Shop>(),
  copied = ref(false),
  { loading, error, run } = useLoad();
const load = () =>
  run(async () => {
    shop.value = await buyer('GET', '/shop');
  });
onMounted(load);
// 未配置位置、电话或微信时隐藏对应入口；地图仅传递公开地址。
const navigationUrl = () =>
  `https://uri.amap.com/search?keyword=${encodeURIComponent(shop.value!.address)}&callnative=1`;
const copy = () =>
  run(async () => {
    await navigator.clipboard.writeText(shop.value!.wechat);
    copied.value = true;
  });
</script>
<template>
  <PageHead
    title="店在哪，怎么来"
    lead="真实的相处比照片更能帮你作出决定。来之前在网上占个位，店长确认后会替你留着。"
    :photos="[
      '/images/pets/golden-lake.webp',
      '/images/pets/corgi-sit.webp',
      '/images/pets/tabby-basket.webp',
    ]"
  />
  <div class="container page">
    <LoadState :loading="loading" :error="error" @retry="load" />
    <div v-if="shop" class="shop-guide">
      <section class="panel shop-info">
        <h2>{{ shop.name }}</h2>
        <dl class="record">
          <div>
            <dt>地址</dt>
            <dd>{{ shop.address }}</dd>
          </div>
          <div>
            <dt>营业时间</dt>
            <dd>{{ shop.businessHours }}</dd>
          </div>
          <div v-if="shop.pickupInstructions">
            <dt>到店须知</dt>
            <dd>{{ shop.pickupInstructions }}</dd>
          </div>
          <div v-if="shop.phone">
            <dt>电话</dt>
            <dd>{{ shop.phone }}</dd>
          </div>
        </dl>
        <div class="actions">
          <RouterLink class="btn secondary" to="/community?type=review">查看店铺评价</RouterLink
          ><a
            v-if="shop.latitude != null && shop.longitude != null"
            class="btn secondary"
            :href="navigationUrl()"
            target="_blank"
            rel="noopener noreferrer"
            >打开地图导航</a
          ><a v-if="shop.phone" class="btn" :href="`tel:${shop.phone}`">拨打 {{ shop.phone }}</a
          ><button v-if="shop.wechat" class="btn secondary" @click="copy">
            {{ copied ? '微信号已复制' : '复制店主微信' }}
          </button>
        </div>
        <small v-if="shop.wechat" class="muted">微信号：{{ shop.wechat }}</small>
      </section>
      <section class="visit-steps" aria-labelledby="visit-steps-title">
        <h2 id="visit-steps-title">到店之后</h2>
        <ol>
          <li>
            <h3>查看档案与检疫证明</h3>
            <p>疫苗本、驱虫记录和检疫证明都摊开给你看，编号可以当场核对。</p>
          </li>
          <li>
            <h3>当面观察健康与性格</h3>
            <p>看精神、眼鼻、毛发和排便，抱一抱、逗一逗，看它和你合不合得来。</p>
          </li>
          <li>
            <h3>确认后付款交付</h3>
            <p>满意了现场付款，在手机上完成健康确认，就能接它回家。</p>
          </li>
        </ol>
      </section>
    </div>
  </div>
</template>
