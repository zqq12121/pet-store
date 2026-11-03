<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import { buyer, money } from '../../../shared/api';
import { useLoad } from '../../../shared/useLoad';
import type { Pet } from '../../../shared/types';
import LoadState from '../components/LoadState.vue';
import ShopCard from '../components/ShopCard.vue';
const route = useRoute(),
  pet = ref<Pet>(),
  photo = ref(0),
  lightbox = ref(false),
  certificate = ref(false),
  { loading, error, run } = useLoad();
const load = () =>
  run(async () => {
    pet.value = await buyer<Pet>('GET', `/pets/${route.params.petId}`);
  });
onMounted(load);
</script>
<template>
  <div class="container page">
    <nav class="breadcrumb">
      <RouterLink to="/">首页</RouterLink> / <RouterLink to="/pets">找猫狗</RouterLink> /
      {{ pet?.name || '详情' }}
    </nav>
    <LoadState :loading="loading" :error="error" @retry="load" /><template v-if="pet"
      ><div class="detail-grid">
        <div>
          <button class="image-button" aria-label="放大宠物照片" @click="lightbox = true">
            <img
              class="detail-photo"
              :src="pet.images[photo]?.url || pet.coverUrl"
              :alt="pet.name"
            />
          </button>
          <div class="thumbnails">
            <button
              v-for="(image, index) in pet.images"
              :key="image.fileId"
              :class="{ selected: index === photo }"
              :aria-label="`查看照片 ${index + 1}`"
              @click="photo = index"
            >
              <img :src="image.url" :alt="pet.name" />
            </button>
          </div>
          <video
            v-if="pet.videoUrl"
            :src="pet.videoUrl"
            controls
            preload="metadata"
            class="detail-video"
          ></video>
        </div>
        <section class="detail-info">
          <span class="status" :class="{ orange: pet.status !== 'on_sale' }"
            >{{
              pet.status === 'on_sale' ? '在售' : pet.status === 'reserved' ? '已预订' : '已售'
            }}
            · 到店自提</span
          >
          <h1>{{ pet.name }}</h1>
          <p class="muted">
            {{ pet.breed }} / {{ pet.gender === 'male' ? '弟弟' : '妹妹' }} /
            {{ pet.ageMonths }}个月 / {{ pet.weightKg }} kg
          </p>
          <span class="pet-tag static"
            ><span>到店付</span><b>{{ money(pet.priceAmount).replace(/\.00$/, '') }}</b></span
          >
          <p>{{ pet.description }}</p>
          <div class="tags">
            <span v-for="tag in pet.personalityTags" :key="tag" class="status">{{ tag }}</span>
          </div>
          <div class="panel health">
            <h3>健康档案</h3>
            <dl class="record">
              <div>
                <dt>疫苗</dt>
                <dd>{{ pet.vaccineStatus || '见检疫证明' }}</dd>
              </div>
              <div>
                <dt>驱虫</dt>
                <dd>{{ pet.dewormStatus || '到店可问店长' }}</dd>
              </div>
              <div v-if="pet.healthDescription">
                <dt>健康情况</dt>
                <dd>{{ pet.healthDescription }}</dd>
              </div>
            </dl>
            <button class="btn secondary" @click="certificate = true">查看检疫证明</button>
          </div>
          <div class="actions purchase-actions">
            <RouterLink v-if="pet.purchaseAllowed" class="btn" :to="`/checkout/${pet.id}`"
              >预约到店</RouterLink
            ><button v-else class="btn" disabled>
              {{ pet.purchaseBlockedReason || '暂不可预约' }}</button
            ><RouterLink class="btn secondary" :to="{ path: '/ai', query: { petId: pet.id } }"
              >问问 AI 怎么养</RouterLink
            >
          </div>
          <small class="muted">线上预约不扣款 · 店长确认后到店付款接宠</small>
        </section>
      </div>
      <section class="feeding">
        <h2>喂养和习惯</h2>
        <p>{{ pet.feedingNotes }}</p>
      </section>
      <ShopCard :shop="pet.shop" />
      <div
        v-if="lightbox || certificate"
        class="modal-layer"
        @click.self="
          lightbox = false;
          certificate = false;
        "
        @keydown.esc="
          lightbox = false;
          certificate = false;
        "
      >
        <section class="modal" role="dialog" aria-modal="true" aria-label="宠物图片与证明">
          <button
            class="icon-button close"
            aria-label="关闭图片"
            @click="
              lightbox = false;
              certificate = false;
            "
          >
            ×</button
          ><img
            v-if="lightbox"
            class="lightbox"
            :src="pet.images[photo]?.url || pet.coverUrl"
            :alt="pet.name"
          /><template v-else
            ><h2>{{ pet.name }}的检疫证明</h2>
            <template v-if="pet.quarantine?.images.length"
              ><p>证明编号：{{ pet.quarantine.certificateNo }}</p>
              <img
                v-for="image in pet.quarantine.images"
                :key="image.fileId"
                class="lightbox"
                :src="image.url"
                alt="经营者上传的检疫证明"
            /></template>
            <p v-else class="empty">暂无公开证明图片。请联系店主查看有效材料。</p></template
          >
        </section>
      </div></template
    >
  </div>
</template>
