<script setup lang="ts">
import type { Pet } from '../../../shared/types'
import { money } from '../../../shared/api'
defineProps<{pet:Pet}>()
</script>
<template><RouterLink :to="`/pets/${pet.id}`" class="pet-card"><div class="pet-cover"><img :src="pet.coverUrl" :alt="`${pet.name}，${pet.breed}`" loading="lazy" width="580" height="504"><span v-if="pet.status!=='on_sale'" class="pet-availability">已预订</span></div><div class="pet-card-body"><h3>{{pet.name}} <span>· {{pet.breed}}</span></h3><p>{{pet.gender==='male'?'弟弟':'妹妹'}} · {{pet.ageMonths}}个月</p><strong>{{money(pet.priceAmount)}}</strong><small>{{pet.status==='on_sale'?'在售':'已预订'}} · 到店自提</small></div></RouterLink></template>
