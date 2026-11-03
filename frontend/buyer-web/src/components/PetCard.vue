<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue';
import type { Pet } from '../../../shared/types';
import { money } from '../../../shared/api';
import { scrambleDigits } from '../motion';
import { vReveal, vWipe } from '../reveal';
const props = defineProps<{ pet: Pet }>();
const price = computed(() => money(props.pet.priceAmount).replace(/\.00$/, ''));
// 悬停时价签数字乱跳一下再停在真实价格。
const shown = ref('');
let stop = () => {};
function onEnter() {
  stop();
  stop = scrambleDigits(price.value, (text) => (shown.value = text === price.value ? '' : text));
}
onUnmounted(() => stop());
</script>
<template>
  <RouterLink
    v-reveal
    :to="`/pets/${pet.id}`"
    class="pet-card"
    data-cursor="看看"
    @mouseenter="onEnter"
  >
    <div v-wipe class="pet-cover">
      <img
        :src="pet.coverUrl"
        :alt="`${pet.name}，${pet.breed}`"
        loading="lazy"
        width="580"
        height="504"
      /><span class="pet-tag"
        ><span>{{ pet.breed }} {{ pet.gender === 'male' ? '弟弟' : '妹妹' }}</span
        ><b>{{ shown || price }}</b></span
      ><span v-if="pet.status !== 'on_sale'" class="pet-availability">已预订</span>
    </div>
    <div class="pet-card-body">
      <h3>
        {{ pet.name }} <span>· {{ pet.breed }}</span>
      </h3>
      <p>{{ pet.gender === 'male' ? '弟弟' : '妹妹' }} · {{ pet.ageMonths }} 个月</p>
      <small>{{ pet.status === 'on_sale' ? '在售，可以占位' : '已被预订' }} · 到店自提</small>
    </div>
  </RouterLink>
</template>
