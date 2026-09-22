<script setup lang="ts">
import { computed,ref,onMounted,onUnmounted } from 'vue'
import { vReveal } from '../reveal'
import {buyer} from '../../../shared/api'
import {useLoad} from '../../../shared/useLoad'
import type {Banner,HomeData} from '../../../shared/types'
import PetCard from '../components/PetCard.vue';import ShopCard from '../components/ShopCard.vue';import LoadState from '../components/LoadState.vue'
const data=ref<HomeData>(),slide=ref(0),hovered=ref(false),{loading,error,run}=useLoad()
let timer:ReturnType<typeof setInterval>
// 门店尚未配置轮播或在售宠物时，使用配图保持首页完整，不生成虚构商品链接。
const welcomeBanners:Banner[]=[
  {id:'welcome-cat',title:'黑白猫咪，把好奇写进每一天。',imageUrl:'/images/cat.jpg',linkType:'none',petId:null,noticeText:null},
  {id:'welcome-pug',title:'巴哥的小表情，藏着大大的可爱。',imageUrl:'/images/puppy.jpg',linkType:'none',petId:null,noticeText:null},
  {id:'welcome-ginger',title:'蓬松橘猫，陪你慢慢享受日常。',imageUrl:'/images/ginger.jpg',linkType:'none',petId:null,noticeText:null},
  {id:'welcome-corgi',title:'遇见柯基，让快乐迈着小短腿跑来。',imageUrl:'/images/corgi.jpg',linkType:'none',petId:null,noticeText:null},
  {id:'welcome-kitten',title:'花色猫咪，一次轻轻的亲近。',imageUrl:'/images/kitten.jpg',linkType:'none',petId:null,noticeText:null},
  {id:'welcome-golden',title:'金毛的温柔，是陪伴的模样。',imageUrl:'/images/golden.jpg',linkType:'none',petId:null,noticeText:null}
]
const featuredPets=computed(()=>data.value?.recommendedPets.length?data.value.recommendedPets:data.value?.latestPets??[])
// 有业务数据时仍优先展示配置和真实宠物，保持图片与详情链接对应。
const banners=computed<Banner[]>(()=>{
  const configured=data.value?.banners??[]
  const pets=featuredPets.value.filter(pet=>pet.status==='on_sale'&&!configured.some(banner=>banner.petId===pet.id||banner.imageUrl===pet.coverUrl))
  const items=[...configured,...pets.slice(0,Math.max(0,6-configured.length)).map(pet=>({id:`pet-${pet.id}`,title:`遇见${pet.name}，让陪伴走进日常。`,imageUrl:pet.coverUrl,linkType:'pet' as const,petId:pet.id,noticeText:null}))]
  // 真实轮播不足六张时用不同猫狗配图补齐，配图不指向商品详情。
  const defaults=welcomeBanners.filter(banner=>!items.some(item=>item.imageUrl===banner.imageUrl))
  return [...items,...defaults.slice(0,Math.max(0,6-items.length))]
})
const load=()=>run(async()=>{data.value=await buyer<HomeData>('GET','/home');slide.value=0})
function startTimer(){
  clearInterval(timer)
  timer=setInterval(()=>{
    // 仅鼠标悬停图片时暂停；按钮焦点和系统减少动画设置不阻止轮播。
    if(!hovered.value&&banners.value.length>1)slide.value=(slide.value+1)%banners.value.length
  },5000)
}
function selectSlide(index:number){slide.value=index;startTimer()}
onMounted(()=>{void load();startTimer()})
onUnmounted(()=>clearInterval(timer))
</script>
<template><div class="container"><LoadState :loading="loading" :error="error" @retry="load"/><template v-if="data"><section class="hero"><div class="hero-copy"><p v-reveal class="eyebrow">A LITTLE PAW. A LOT OF LOVE.</p><h1 v-reveal="50">一个小小的相遇，<br>让日常多一点温柔。</h1><p v-reveal="100" class="lead">发现与你合拍的猫咪与狗狗。<br>看见真实档案，在门店安心相遇。</p><div v-reveal="150" class="actions"><RouterLink class="btn" to="/pets">去认识它们 →</RouterLink><RouterLink class="btn secondary" to="/ai">问问 AI 怎么养</RouterLink></div><p v-reveal="180" class="hero-trust">一宠一档 / 检疫证明可查 / 到店当面核验</p></div><!-- 固定图片区域，交叉淡入淡出时保持卡片高度稳定。 -->
<div v-if="banners.length" v-reveal="100" class="hero-media" role="region" aria-roledescription="轮播" aria-label="认识宠物">
  <div class="hero-photo" @pointerenter="hovered=$event.pointerType==='mouse'" @pointerleave="hovered=false" @pointercancel="hovered=false"><Transition name="hero-photo"><img :key="banners[slide]?.id" :src="banners[slide]?.imageUrl" :alt="banners[slide]?.title" width="580" height="510"></Transition><span class="hero-photo-disclaimer">仅供参考，具体以实物为准</span></div>
  <div class="hero-caption"><strong>{{banners[slide]?.title}}</strong><RouterLink v-if="banners[slide]?.linkType==='pet'" :to="`/pets/${banners[slide]?.petId}`" class="btn secondary">认识我 →</RouterLink><p v-else-if="banners[slide]?.noticeText">{{banners[slide]?.noticeText}}</p></div>
  <div v-if="banners.length>1" class="carousel-controls"><button v-for="(banner,index) in banners" :key="banner.id" :class="{active:index===slide}" :aria-label="`查看第 ${index+1} 张照片`" :aria-pressed="index===slide" @click="selectSlide(index)"></button></div>
</div></section><section v-reveal class="home-pets"><div class="actions" style="margin-bottom:32px"><RouterLink v-for="category in data.categories" :key="category.code" :to="`/pets?category=${category.code}`" class="btn secondary">认识{{category.name}} →</RouterLink></div><div class="section-heading"><div><p class="eyebrow">MEET YOUR COMPANION</p><h2>每一只，都值得认真认识。</h2></div><RouterLink to="/pets" class="text-link">查看全部 →</RouterLink></div><div v-if="featuredPets.length" class="pet-grid"><PetCard v-for="pet in featuredPets.slice(0,4)" :key="pet.id" :pet="pet"/></div><div v-else class="home-empty"><div><h3>下一次相遇，值得期待。</h3><p>目前暂无可展示的在售宠物。可以先了解门店，联系店主咨询。</p></div><RouterLink to="/shop" class="btn secondary">了解门店 →</RouterLink></div></section><section v-if="data.recommendedPets.length&&data.latestPets.length" v-reveal class="home-pets"><div class="section-heading"><div><p class="eyebrow">NEW LITTLE FRIENDS</p><h2>新来的小伙伴</h2></div><RouterLink to="/pets" class="text-link">发现更多 →</RouterLink></div><div class="pet-grid"><PetCard v-for="pet in data.latestPets.slice(0,4)" :key="pet.id" :pet="pet"/></div></section><ShopCard v-reveal :shop="data.shop"/></template></div></template>

<style scoped>
.hero-caption{flex-wrap:wrap}
.hero-caption p{flex-basis:100%;margin:0;color:var(--muted);font-size:12px;line-height:1.6}
.home-empty{display:flex;justify-content:space-between;align-items:center;gap:24px;padding:32px;border:1px solid var(--line);border-radius:24px;background:var(--sand)}
.home-empty h3{margin-bottom:8px}
.home-empty p{margin:0;color:var(--muted);font-size:14px}
.home-empty .btn{flex-shrink:0}
@media(max-width:760px){.home-empty{align-items:flex-start;flex-direction:column;padding:24px;gap:18px}.hero-caption p{font-size:10px}}
/* 两张照片在同一区域交叠过渡，避免先淡出产生空白。 */
.hero-photo{position:relative;aspect-ratio:580/510;overflow:hidden;border-radius:32px}
/* 提示固定覆盖每一张配图，不随淡入淡出消失。 */
.hero-photo-disclaimer{position:absolute;right:14px;bottom:14px;z-index:2;padding:6px 10px;border-radius:8px;background:rgba(0,0,0,.58);color:#fff;font-size:12px;line-height:1.5;pointer-events:none}
.hero-photo img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
.hero-photo-enter-active,.hero-photo-leave-active{transition:opacity .65s ease,transform .65s ease}
.hero-photo-enter-active{z-index:1}
.hero-photo-enter-from{opacity:0;transform:scale(1.035)}
.hero-photo-leave-to{opacity:0}
@media(max-width:760px){.hero-photo{aspect-ratio:350/330;border-radius:24px}}
@media(prefers-reduced-motion:reduce){.hero-photo-enter-active,.hero-photo-leave-active{transition:none}}
</style>
