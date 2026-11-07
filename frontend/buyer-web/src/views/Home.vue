<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { buyer, money } from '../../../shared/api';
import { useLoad } from '../../../shared/useLoad';
import type { Banner, HomeData, Pet } from '../../../shared/types';
import LoadState from '../components/LoadState.vue';
import { scrambleDigits, smoothScrollTo, stopSmoothScroll } from '../motion';

/*
 * 首页：全屏大图 + 滚动动效（参考 onform.studio 的节奏），只用原生 JS 和 CSS 实现，不引入第三方动效库。
 * 动效只在电脑（宽屏 + 鼠标）上开启钉住和横向滑动；手机保持普通上下排列；系统开启“减少动画”时全部关闭。
 */

const data = ref<HomeData>(),
  slide = ref(0),
  { loading, error, run } = useLoad();

// 门店尚未配置轮播或在售宠物时，用配图补齐首屏，不生成虚构商品链接（沿用原首页规则）。
const welcomeBanners: Banner[] = [
  {
    id: 'welcome-corgi',
    title: '遇见柯基，让快乐迈着小短腿跑来。',
    imageUrl: '/images/pets/corgi-sit.webp',
    linkType: 'none',
    petId: null,
    noticeText: null,
  },
  {
    id: 'welcome-golden',
    title: '金毛的温柔，是陪伴的模样。',
    imageUrl: '/images/pets/golden-ball.webp',
    linkType: 'none',
    petId: null,
    noticeText: null,
  },
  {
    id: 'welcome-ginger',
    title: '蓬松长毛猫，陪你慢慢享受日常。',
    imageUrl: '/images/pets/ginger-longhair.webp',
    linkType: 'none',
    petId: null,
    noticeText: null,
  },
  {
    id: 'welcome-collie',
    title: '边牧的认真眼神，藏着大大的聪明。',
    imageUrl: '/images/pets/border-collie-sit.webp',
    linkType: 'none',
    petId: null,
    noticeText: null,
  },
  {
    id: 'welcome-siamese',
    title: '蓝眼暹罗，把好奇写进每一天。',
    imageUrl: '/images/pets/siamese-garden.webp',
    linkType: 'none',
    petId: null,
    noticeText: null,
  },
  {
    id: 'welcome-pomeranian',
    title: '蓬松博美，一次轻轻的亲近。',
    imageUrl: '/images/pets/pomeranian.webp',
    linkType: 'none',
    petId: null,
    noticeText: null,
  },
];

// 后端 latestPets 只含在售宠物；为空时各区块显示空状态。
const pets = computed<Pet[]>(() => data.value?.latestPets ?? []);
const featuredPets = computed(() =>
  data.value?.recommendedPets.length ? data.value.recommendedPets : pets.value
);
const banners = computed<Banner[]>(() => {
  const configured = data.value?.banners ?? [];
  const fromPets = featuredPets.value
    .filter(
      (p) =>
        p.status === 'on_sale' &&
        !configured.some((b) => b.petId === p.id || b.imageUrl === p.coverUrl)
    )
    .slice(0, Math.max(0, 6 - configured.length))
    .map((p) => ({
      id: `pet-${p.id}`,
      title: `${p.name} · ${p.breed} · ${p.ageMonths} 个月`,
      imageUrl: p.coverUrl,
      linkType: 'pet' as const,
      petId: p.id,
      noticeText: null,
    }));
  const items = [...configured, ...fromPets];
  const fill = welcomeBanners.filter((b) => !items.some((i) => i.imageUrl === b.imageUrl));
  return [...items, ...fill.slice(0, Math.max(0, 4 - items.length))];
});
// 页面里要用到照片的地方（段落小图、步骤大图、菜单预览），优先用真实宠物照片。
const photos = computed(() => {
  const list = [...pets.value.map((p) => p.coverUrl), ...welcomeBanners.map((b) => b.imageUrl)];
  return [...new Set(list)];
});
const photo = (i: number) => photos.value[i % photos.value.length];

const shopName = computed(() => data.value?.shop.name ?? '');
const catCount = computed(() => pets.value.filter((p) => p.category === 'cat').length);
const dogCount = computed(() => pets.value.filter((p) => p.category === 'dog').length);
const shortMoney = (amount: number) => money(amount).replace(/\.00$/, '');
const gender = (p: Pet) => (p.gender === 'male' ? '弟弟' : '妹妹');

// 按品种汇总在售宠物：数量、最低价、月龄范围，用于“按品种看”。
const breeds = computed(() => {
  const map = new Map<string, { breed: string; category: string; pets: Pet[] }>();
  for (const p of pets.value) {
    const item = map.get(p.breed) ?? { breed: p.breed, category: p.category, pets: [] };
    item.pets.push(p);
    map.set(p.breed, item);
  }
  return [...map.values()].map(({ breed, category, pets: list }) => {
    const ages = list.map((p) => p.ageMonths);
    const min = Math.min(...ages),
      max = Math.max(...ages);
    return {
      breed,
      category,
      count: list.length,
      price: shortMoney(Math.min(...list.map((p) => p.priceAmount))),
      many: list.length > 1,
      ages: min === max ? `${min} 个月` : `${min}–${max} 个月`,
      cover: list[0].coverUrl,
    };
  });
});

// 大标题拆成单字，配合 CSS 依次升起。
const heroTitle = '看中了先占个位';
// “店主的话”拆成单字，滚动时逐字变成绿色；中间穿插三张小圆图。
type Part = { kind: 'char'; text: string } | { kind: 'img'; src: string };
const statementParts = computed<Part[]>(() => {
  const chunks: (string | number)[] = [
    `${shopName.value}是一家只卖猫和狗的小店`,
    0,
    '。每一只都有疫苗、驱虫记录和检疫证明',
    1,
    '，来了可以当面看、当面问。线上只占位，不扣款，店长确认后它就替你留着',
    2,
    '。到店看过，满意了再付钱、签字、带走。',
  ];
  return chunks.flatMap<Part>((c) =>
    typeof c === 'number'
      ? [{ kind: 'img', src: photo(c) }]
      : Array.from(c).map((text) => ({ kind: 'char', text }))
  );
});
const statementText = computed(() =>
  statementParts.value.map((p) => (p.kind === 'char' ? p.text : '')).join('')
);

const load = () =>
  run(async () => {
    data.value = await buyer<HomeData>('GET', '/home');
    slide.value = 0;
  });

/* ---------------- 动效状态 ---------------- */
const reduce = window.matchMedia('(prefers-reduced-motion: reduce)');
const desktop = window.matchMedia('(min-width: 900px) and (pointer: fine)');
const finePointer = window.matchMedia('(pointer: fine)').matches;
const pinMode = ref(desktop.matches && !reduce.matches);

// 开场幕布每次会话只播一次，避免来回切页时反复遮挡。
let curtainSeen = false;
try {
  curtainSeen = sessionStorage.getItem('home-curtain') === '1';
  sessionStorage.setItem('home-curtain', '1');
} catch {
  /* 无痕模式等场景读写失败时照常播放 */
}
const showCurtain = ref(!reduce.matches && !curtainSeen);
const titleDelay = showCurtain.value ? 1.5 : 0.15;

const scrolled = ref(false),
  litCount = ref(0),
  stepIndex = ref(-1),
  stripHeight = ref(''),
  menuOpen = ref(false),
  peekSrc = ref(''),
  peekOn = ref(false);
const scrambled = reactive<Record<string, string>>({});

const heroEl = ref<HTMLElement>(),
  heroMedia = ref<HTMLElement>(),
  heroFoot = ref<HTMLElement>(),
  statementEl = ref<HTMLElement>(),
  stepsEl = ref<HTMLElement>(),
  stepsHead = ref<HTMLElement>(),
  frameEl = ref<HTMLElement>(),
  firstShot = ref<HTMLElement>(),
  stripEl = ref<HTMLElement>(),
  trackEl = ref<HTMLElement>(),
  peekEl = ref<HTMLElement>(),
  closeBtn = ref<HTMLButtonElement>(),
  menuBtn = ref<HTMLButtonElement>();

const clamp = (v: number, a = 0, b = 1) => Math.min(b, Math.max(a, v));
const ease = (t: number) => t * t * (3 - 2 * t);

/* 每帧根据滚动位置更新各区块；只在数值变化时写 DOM。 */
let mx = 0,
  my = 0,
  px = 0,
  py = 0,
  raf = 0,
  lastY = -1,
  dirty = true,
  stripDist = 0;
function measureStrip() {
  const track = trackEl.value;
  if (!track) return;
  stripDist = pinMode.value ? Math.max(0, track.scrollWidth - window.innerWidth) : 0;
  stripHeight.value = pinMode.value ? `calc(100vh + ${stripDist}px)` : '';
}
function frame() {
  raf = requestAnimationFrame(frame);
  // 品种行悬停时的照片跟随鼠标（“看看”提示由全站 CursorBubble 负责）。
  if (finePointer) {
    px += (mx - px) * 0.12;
    py += (my - py) * 0.12;
    if (peekEl.value) peekEl.value.style.transform = `translate3d(${px + 40}px, ${py - 150}px, 0)`;
  }
  const y = window.scrollY;
  if (y === lastY && !dirty) return;
  lastY = y;
  dirty = false;
  const vh = window.innerHeight;
  scrolled.value = y > vh * 0.6;

  // 首屏：照片下沉、文字上移变淡。
  if (heroEl.value && heroMedia.value && heroFoot.value && !reduce.matches) {
    const p = clamp(y / heroEl.value.offsetHeight);
    heroMedia.value.style.transform = `translate3d(0, ${p * 14}%, 0)`;
    heroFoot.value.style.transform = `translate3d(0, ${-p * 40}%, 0)`;
    heroFoot.value.style.opacity = String(1 - p * 0.85);
  }

  // 店主的话：段落从视口 78% 处进入到底部离开视口中线，逐字点亮。
  if (statementEl.value) {
    const total = statementParts.value.length;
    if (reduce.matches) litCount.value = total;
    else {
      const r = statementEl.value.getBoundingClientRect();
      const start = vh * 0.78,
        end = vh * 0.5;
      const p = clamp((start - r.top) / (start - end + r.height));
      litCount.value = Math.round(p * total);
    }
  }

  // 买一只要几步：钉住后小卡片展开到全屏，再依次出现三步。
  if (pinMode.value && stepsEl.value && frameEl.value && firstShot.value && stepsHead.value) {
    const r = stepsEl.value.getBoundingClientRect();
    const t = clamp(-r.top / (r.height - vh)) * 4.2;
    const e = ease(clamp(t));
    const k = 1 - e;
    frameEl.value.style.clipPath = `inset(${24 * k}% ${30 * k}% ${14 * k}% ${30 * k}% round ${6 * k}px)`;
    firstShot.value.style.transform = `scale(${1.25 - 0.25 * e})`;
    const h = clamp((t - 0.45) / 0.5);
    stepsHead.value.style.opacity = String(1 - h);
    stepsHead.value.style.transform = `translate3d(0, ${-40 * h}px, 0)`;
    stepIndex.value = t < 1 ? -1 : t < 2.05 ? 0 : t < 3.05 ? 1 : 2;
  }

  // 现在在店的：钉住绿色区块，竖向滚动换成卡片横向移动。
  if (pinMode.value && stripEl.value && trackEl.value) {
    const r = stripEl.value.getBoundingClientRect();
    const p = clamp(-r.top / Math.max(1, r.height - vh));
    trackEl.value.style.transform = `translate3d(${-p * stripDist}px, 0, 0)`;
  }
}

function onResize() {
  measureStrip();
  dirty = true;
}
function onMove(e: MouseEvent) {
  mx = e.clientX;
  my = e.clientY;
}
// 宽屏和鼠标条件变化（如拖动窗口）时切换布局，并清掉钉住模式写入的行内样式。
function onModeChange() {
  pinMode.value = desktop.matches && !reduce.matches;
  if (!pinMode.value) {
    for (const el of [frameEl.value, firstShot.value, stepsHead.value, trackEl.value])
      el?.removeAttribute('style');
  }
  nextTick(onResize);
}

/* 价格数字乱跳几下后停在真实价格。 */
const stops = new Map<string, () => void>();
function scramble(key: string, final: string) {
  stops.get(key)?.();
  stops.set(
    key,
    scrambleDigits(final, (text) => {
      if (text === final) delete scrambled[key];
      else scrambled[key] = text;
    })
  );
}
function enterRow(b: { breed: string; price: string; cover: string }) {
  scramble(b.breed, b.price);
  peekSrc.value = b.cover;
  peekOn.value = true;
}

/* 菜单：打开时锁住页面滚动，Esc 关闭，焦点在菜单内外切换。 */
const menuPreview = ref('');
function setMenu(open: boolean) {
  menuOpen.value = open;
  document.documentElement.style.overflow = open ? 'hidden' : '';
  stopSmoothScroll();
  if (open) {
    menuPreview.value = photo(0);
    nextTick(() => closeBtn.value?.focus());
  } else menuBtn.value?.focus();
}
function menuScrollTop() {
  setMenu(false);
  setTimeout(() => smoothScrollTo(0), 300);
}

/* 首屏轮播：每 5 秒切换；鼠标悬停在首屏时暂停。 */
let slideTimer: ReturnType<typeof setInterval>;
const heroHover = ref(false);
// 轮播只给已轮到的图和下一张设置 src，避免首屏同时下载全部大图。
const heroLoaded = ref(1);
watch(slide, (i) => (heroLoaded.value = Math.max(heroLoaded.value, i + 1)));
function startSlides() {
  clearInterval(slideTimer);
  slideTimer = setInterval(() => {
    if (!heroHover.value && banners.value.length > 1)
      slide.value = (slide.value + 1) % banners.value.length;
  }, 5000);
}

// 数据渲染完成后再量尺寸（横向滑动距离依赖卡片宽度）。
watch(data, () => nextTick(onResize));

let curtainTimer: ReturnType<typeof setTimeout>;
onMounted(() => {
  void load();
  startSlides();
  window.addEventListener('resize', onResize);
  window.addEventListener('load', onResize);
  if (finePointer) window.addEventListener('mousemove', onMove, { passive: true });
  desktop.addEventListener('change', onModeChange);
  reduce.addEventListener('change', onModeChange);
  document.fonts?.ready.then(onResize);
  if (showCurtain.value) curtainTimer = setTimeout(() => (showCurtain.value = false), 2300);
  raf = requestAnimationFrame(frame);
});
onUnmounted(() => {
  cancelAnimationFrame(raf);
  clearInterval(slideTimer);
  clearTimeout(curtainTimer);
  stops.forEach((stop) => stop());
  window.removeEventListener('resize', onResize);
  window.removeEventListener('load', onResize);
  window.removeEventListener('mousemove', onMove);
  desktop.removeEventListener('change', onModeChange);
  reduce.removeEventListener('change', onModeChange);
  document.documentElement.style.overflow = '';
});
</script>

<template>
  <div class="hm" :class="{ 'is-pin': pinMode }">
    <div v-if="showCurtain" class="hm-curtain" aria-hidden="true">
      <b v-if="shopName">{{ shopName }}</b
      ><span>只卖猫狗 · 到店自提</span>
    </div>

    <header class="hm-top" :class="{ scrolled }">
      <div class="hm-wrap hm-top-row">
        <RouterLink class="hm-logo" to="/">{{ shopName }}</RouterLink>
        <span class="hm-top-mid">只卖猫狗 · 到店自提</span>
        <button
          ref="menuBtn"
          class="hm-pill-btn"
          type="button"
          aria-controls="hm-menu"
          :aria-expanded="menuOpen"
          @click="setMenu(true)"
        >
          菜单<i aria-hidden="true"></i>
        </button>
      </div>
    </header>

    <section v-if="!data" class="hm-boot">
      <LoadState :loading="loading" :error="error" @retry="load" />
    </section>

    <template v-else>
      <!-- 首屏：全屏轮播 + 底部超大标题，下边缘为弧形 -->
      <section
        ref="heroEl"
        class="hm-hero"
        aria-label="店里的猫狗"
        @pointerenter="heroHover = $event.pointerType === 'mouse'"
        @pointerleave="heroHover = false"
      >
        <div ref="heroMedia" class="hm-hero-media">
          <img
            v-for="(b, i) in banners"
            :key="b.id"
            :class="['hm-shot', { on: i === slide }]"
            :src="i <= heroLoaded ? b.imageUrl : undefined"
            :fetchpriority="i === 0 ? 'high' : undefined"
            :alt="b.title"
            :aria-hidden="i !== slide"
          />
        </div>
        <span class="hm-disclaimer">照片仅供参考，以到店实物为准</span>
        <div ref="heroFoot" class="hm-hero-foot">
          <div class="hm-wrap">
            <div class="hm-hero-row">
              <p class="hm-hero-intro">
                <img
                  class="hm-dot"
                  :src="photo(1)"
                  alt=""
                />线上先占个位，不扣款。到店看过、问清楚，满意了再付钱接它回家。
              </p>
              <div class="hm-pills">
                <RouterLink
                  v-if="banners[slide]?.linkType === 'pet'"
                  class="hm-pill hm-pill-link"
                  :to="`/pets/${banners[slide]?.petId}`"
                  >{{ banners[slide]?.title }} →</RouterLink
                >
                <span v-else class="hm-pill">{{
                  banners[slide]?.noticeText || banners[slide]?.title
                }}</span>
                <span class="hm-pill"
                  ><span class="hm-live" aria-hidden="true"></span>营业时间
                  {{ data.shop.businessHours }}</span
                >
              </div>
            </div>
            <h1 class="hm-title" :aria-label="heroTitle">
              <!-- 手机上在“看中了”后换行，避免断成“先占 / 个位” -->
              <template v-for="(c, i) in heroTitle" :key="i">
                <br v-if="i === 3" class="hm-br" />
                <span
                  class="hm-ch"
                  aria-hidden="true"
                  :style="{ animationDelay: `${titleDelay + i * 0.06}s` }"
                  >{{ c }}</span
                >
              </template>
            </h1>
          </div>
        </div>
      </section>

      <!-- 店主的话：滚动时逐字变绿 -->
      <section class="hm-statement hm-wrap" aria-labelledby="hm-st">
        <p id="hm-st" class="hm-eyebrow">店主的话</p>
        <p ref="statementEl" class="hm-big">
          <span class="hm-sr">{{ statementText }}</span>
          <template v-for="(part, i) in statementParts" :key="i">
            <img
              v-if="part.kind === 'img'"
              class="hm-inl"
              :class="{ on: i < litCount }"
              :src="part.src"
              alt=""
              loading="lazy"
              aria-hidden="true"
            />
            <span v-else :class="{ on: i < litCount }" aria-hidden="true">{{ part.text }}</span>
          </template>
        </p>
      </section>

      <!-- 买一只要几步：电脑上钉住展开，手机上普通排列 -->
      <section ref="stepsEl" class="hm-steps" aria-labelledby="hm-steps-h">
        <div class="hm-steps-stage">
          <div ref="stepsHead" class="hm-steps-head hm-wrap">
            <h2 id="hm-steps-h" class="hm-h2">买一只要几步</h2>
            <p>三步，第一步不扣款。每个账号同时只能有 1 个有效预约。</p>
          </div>
          <div class="hm-wrap hm-steps-body">
            <div ref="frameEl" class="hm-frame" aria-hidden="true">
              <img ref="firstShot" class="hm-fs" :src="photo(0)" alt="" loading="lazy" />
              <img
                class="hm-fs"
                :class="{ on: stepIndex >= 1 }"
                :src="photo(1)"
                alt=""
                loading="lazy"
              />
              <img
                class="hm-fs"
                :class="{ on: stepIndex >= 2 }"
                :src="photo(2)"
                alt=""
                loading="lazy"
              />
            </div>
            <ol class="hm-caps">
              <li class="hm-cap" :class="{ on: stepIndex === 0 }">
                <span class="hm-n">1</span>
                <h3>线上占个位</h3>
                <p>选好想看的那只，填姓名、手机和到店时间。线上不扣款，提交后别人就订不了它。</p>
              </li>
              <li class="hm-cap" :class="{ on: stepIndex === 1 }">
                <span class="hm-n">2</span>
                <h3>等店长确认</h3>
                <p>店长会在 24 小时内、并且在你到店之前确认。确认后一直保留到约定时间后 2 小时。</p>
              </li>
              <li class="hm-cap" :class="{ on: stepIndex === 2 }">
                <span class="hm-n">3</span>
                <h3>到店看、付钱、带走</h3>
                <p>当面看精神、眼鼻和毛发，核对检疫证明。满意了现场付款、签字接走。</p>
              </li>
            </ol>
            <div class="hm-ticks" aria-hidden="true">
              <span
                v-for="n in 3"
                :key="n"
                :class="{ on: Math.max(0, stepIndex) === n - 1 }"
              ></span>
            </div>
          </div>
        </div>
      </section>

      <!-- 现在在店的：电脑上竖向滚动驱动横向移动 -->
      <section
        ref="stripEl"
        class="hm-strip"
        :style="{ height: stripHeight }"
        aria-labelledby="hm-strip-h"
      >
        <div class="hm-strip-stage">
          <div class="hm-strip-head hm-wrap">
            <div>
              <h2 id="hm-strip-h" class="hm-h2">现在在店的</h2>
              <p v-if="pets.length">
                在售 {{ pets.length }} 只：猫 {{ catCount }} 只，狗 {{ dogCount }} 只。
              </p>
            </div>
            <RouterLink class="hm-pill-link-lg" to="/pets"
              >看全部 <span aria-hidden="true">↗</span></RouterLink
            >
          </div>
          <div v-if="pets.length" ref="trackEl" class="hm-track">
            <RouterLink
              v-for="p in pets.slice(0, 8)"
              :key="p.id"
              class="hm-pet"
              :to="`/pets/${p.id}`"
              data-cursor="看看"
            >
              <div class="hm-pic">
                <img
                  :src="p.coverUrl"
                  :alt="`${p.name}，${p.breed}`"
                  loading="lazy"
                  @load="onResize"
                />
                <div class="hm-tag">
                  <span>{{ p.breed }} {{ gender(p) }}</span
                  ><b>{{ shortMoney(p.priceAmount) }}</b>
                </div>
                <div v-if="p.status !== 'on_sale'" class="hm-stamp">已预订</div>
              </div>
              <div class="hm-pet-meta">
                <strong>{{ p.name }}</strong
                ><span>{{ p.ageMonths }} 个月</span>
              </div>
              <p v-if="p.vaccineStatus">疫苗：{{ p.vaccineStatus }}</p>
            </RouterLink>
          </div>
          <div v-else class="hm-wrap hm-empty">
            <p>目前没有在售的猫狗。可以先看看到店指南，或者联系店主问问最近会不会有新来的。</p>
            <RouterLink class="hm-pill-link-lg" to="/shop">到店指南 →</RouterLink>
          </div>
        </div>
      </section>

      <!-- 按品种看：悬停时价格乱跳、照片跟随鼠标 -->
      <section v-if="breeds.length" class="hm-breeds" aria-labelledby="hm-br-h">
        <div class="hm-wrap">
          <h2 id="hm-br-h" class="hm-h2">按品种看</h2>
          <p class="hm-sub">点一行，直接看这个品种在店的那几只。</p>
          <div class="hm-rows">
            <RouterLink
              v-for="b in breeds"
              :key="b.breed"
              class="hm-row"
              :to="{ path: '/pets', query: { category: b.category, keyword: b.breed } }"
              @mouseenter="enterRow(b)"
              @mouseleave="peekOn = false"
            >
              <span class="hm-nm">{{ b.breed }}</span>
              <span class="hm-info"
                >在店 {{ b.count }} 只<small>{{ b.ages }}</small></span
              >
              <span class="hm-pr"
                >{{ scrambled[b.breed] ?? b.price }}<em v-if="b.many">起</em></span
              >
            </RouterLink>
          </div>
          <div class="hm-breeds-foot">
            <p>疫苗、驱虫和检疫证明，每只的详情页里都能查。</p>
            <RouterLink class="hm-pill-link-lg" to="/ai"
              >问问 AI 怎么养 <span aria-hidden="true">↗</span></RouterLink
            >
          </div>
        </div>
      </section>
      <img
        v-if="finePointer"
        ref="peekEl"
        class="hm-peek"
        :class="{ on: peekOn }"
        :src="peekSrc || photo(0)"
        alt=""
        aria-hidden="true"
      />

      <!-- 页脚：深色背景、弧形上边缘、超大店名 -->
      <footer class="hm-foot" :class="{ 'no-breeds': !breeds.length }">
        <div class="hm-wrap">
          <div class="hm-foot-grid">
            <div>
              <p class="hm-eyebrow">有想看的？</p>
              <h2 class="hm-foot-h">先来店里<br />坐一坐。</h2>
            </div>
            <div class="hm-foot-side">
              <p>
                {{ data.shop.pickupInstructions || '来之前先在网上占个位，店长确认后会替你留着。' }}
              </p>
              <dl>
                <dt>地址</dt>
                <dd>{{ data.shop.address }}</dd>
                <dt>营业</dt>
                <dd>{{ data.shop.businessHours }}</dd>
                <template v-if="data.shop.phone">
                  <dt>电话</dt>
                  <dd>{{ data.shop.phone }}</dd>
                </template>
              </dl>
              <RouterLink class="hm-pill-link-lg" to="/shop"
                >到店指南 <span aria-hidden="true">↗</span></RouterLink
              >
            </div>
          </div>
          <div class="hm-foot-bar">
            <span>线上只占位不扣款 · 到店付款接宠</span>
            <nav aria-label="页脚">
              <RouterLink to="/pets">找猫狗</RouterLink>
              <RouterLink to="/ai">问问怎么养</RouterLink>
              <RouterLink to="/community">宠友社区</RouterLink>
              <RouterLink to="/terms">用户协议</RouterLink>
              <RouterLink to="/privacy">隐私政策</RouterLink>
              <button type="button" @click="smoothScrollTo(0)">回到顶部 ↑</button>
            </nav>
          </div>
        </div>
        <div class="hm-giant" aria-hidden="true" :style="{ '--n': Math.max(2, shopName.length) }">
          {{ shopName }}
        </div>
      </footer>
    </template>

    <!-- 全屏菜单：绿色百叶窗落下，悬停链接切换右侧照片 -->
    <div
      id="hm-menu"
      class="hm-menu"
      :class="{ open: menuOpen }"
      role="dialog"
      aria-modal="true"
      aria-label="网站菜单"
      :aria-hidden="!menuOpen"
      @keydown.esc="setMenu(false)"
    >
      <div class="hm-shutters" aria-hidden="true"><i v-for="n in 5" :key="n"></i></div>
      <div class="hm-menu-in hm-wrap">
        <div class="hm-top-row">
          <span class="hm-logo">{{ shopName }}</span>
          <button
            ref="closeBtn"
            class="hm-pill-btn"
            type="button"
            :tabindex="menuOpen ? 0 : -1"
            @click="setMenu(false)"
          >
            关闭<i class="x" aria-hidden="true"></i>
          </button>
        </div>
        <div class="hm-menu-body">
          <ul class="hm-menu-links">
            <li>
              <button
                type="button"
                :tabindex="menuOpen ? 0 : -1"
                @mouseenter="menuPreview = photo(0)"
                @focus="menuPreview = photo(0)"
                @click="menuScrollTop"
              >
                首页
              </button>
            </li>
            <li
              v-for="(l, i) in [
                { to: '/pets', text: '找猫狗' },
                { to: '/ai', text: '问问怎么养' },
                { to: '/community', text: '宠友社区' },
                { to: '/shop', text: '到店怎么走' },
                { to: '/orders', text: '我的预约' },
              ]"
              :key="l.to"
            >
              <RouterLink
                :to="l.to"
                :tabindex="menuOpen ? 0 : -1"
                @mouseenter="menuPreview = photo(i + 1)"
                @focus="menuPreview = photo(i + 1)"
                @click="setMenu(false)"
                >{{ l.text }}</RouterLink
              >
            </li>
          </ul>
          <img
            v-if="menuPreview"
            class="hm-menu-prev"
            :src="menuPreview"
            alt=""
            aria-hidden="true"
          />
        </div>
        <div class="hm-menu-foot">
          <span>{{ data?.shop.address }}</span
          ><span>营业时间 {{ data?.shop.businessHours }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 标题字体 HomeDisplay 在 style.css 中全局声明 */
.hm {
  --g: #3f7d2c;
  --g-deep: #346a24;
  --y: #f5cf7a;
  --paper: #fffbf3;
  --ink: #3d2f22;
  --ink2: #5a4a3a;
  --muted: #7a6a58;
  --faint: #d9cdb8;
  --line: #ece3d2;
  --red: #b5362a;
  --night: #3d2f22;
  --white: #ffffff;
  --display: 'HomeDisplay', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  --hand: 'Kaiti SC', 'STKaiti', 'KaiTi', serif;
  --gut: max(16px, env(safe-area-inset-left, 0px));
  --gut-r: max(16px, env(safe-area-inset-right, 0px));
  --curve: clamp(28px, 6vw, 96px);
  /* 用 clip 而不是 hidden，避免破坏下方 sticky 钉住效果 */
  overflow-x: clip;
  background: var(--paper);
  color: var(--ink);
  line-height: 1.7;
  margin-bottom: -28px;
}
@media (min-width: 900px) {
  .hm {
    --gut: max(40px, env(safe-area-inset-left, 0px));
    --gut-r: max(40px, env(safe-area-inset-right, 0px));
  }
}
.hm-wrap {
  max-width: 1600px;
  margin: 0 auto;
  padding-left: var(--gut);
  padding-right: var(--gut-r);
}
.hm-sr {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}
.hm :focus-visible {
  outline: 3px solid var(--y);
  outline-offset: 3px;
}

/* 开场幕布：纯 CSS 动画，脚本异常时也会自动拉开 */
.hm-curtain {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: var(--g);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  pointer-events: none;
  animation: hm-up 0.9s cubic-bezier(0.76, 0, 0.24, 1) 1.15s forwards;
}
.hm-curtain b {
  font: 900 clamp(48px, 9vw, 120px) / 1 var(--display);
  color: var(--y);
  animation: hm-rise 0.7s cubic-bezier(0.2, 0.8, 0.2, 1) 0.1s both;
}
.hm-curtain span {
  color: var(--white);
  font-size: 14px;
  letter-spacing: 0.3em;
  opacity: 0.8;
  animation: hm-rise 0.7s cubic-bezier(0.2, 0.8, 0.2, 1) 0.25s both;
}
@keyframes hm-up {
  to {
    transform: translateY(-100%);
  }
}
@keyframes hm-rise {
  from {
    transform: translateY(40px);
    opacity: 0;
  }
  to {
    transform: none;
    opacity: 1;
  }
}

/* 顶部：首屏上显示店名，滚过首屏后只留菜单按钮 */
.hm-top {
  position: fixed;
  left: 0;
  right: 0;
  top: 0;
  z-index: 50;
  padding-top: calc(18px + env(safe-area-inset-top, 0px));
  pointer-events: none;
}
.hm-top-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.hm-logo {
  pointer-events: auto;
  font: 900 26px / 1 var(--display);
  color: var(--y);
  transition:
    opacity 0.4s,
    transform 0.4s;
}
.hm-top-mid {
  color: var(--white);
  font-size: 12px;
  letter-spacing: 0.32em;
  transition: opacity 0.4s;
}
.hm-top.scrolled .hm-logo,
.hm-top.scrolled .hm-top-mid {
  opacity: 0;
  transform: translateY(-8px);
  pointer-events: none;
}
.hm-pill-btn {
  pointer-events: auto;
  display: inline-flex;
  align-items: center;
  gap: 14px;
  min-height: 46px;
  padding: 0 18px 0 22px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.28);
  background: rgba(52, 106, 36, 0.75);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  color: var(--white);
  font-size: 13px;
  letter-spacing: 0.2em;
}
.hm-pill-btn:hover {
  background: var(--g);
}
.hm-pill-btn i {
  display: block;
  width: 22px;
  height: 8px;
  border-top: 1.5px solid currentColor;
  border-bottom: 1.5px solid currentColor;
}
.hm-pill-btn i.x {
  height: 0;
  border-bottom: none;
}
@media (max-width: 640px) {
  .hm-top-mid {
    display: none;
  }
}

.hm-boot {
  min-height: 100svh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--g-deep);
  padding: 100px var(--gut-r) 40px var(--gut);
}

/* 首屏 */
.hm-hero {
  position: relative;
  height: 100svh;
  min-height: 620px;
  overflow: hidden;
  color: var(--white);
  background: var(--g-deep);
  border-radius: 0 0 50% 50% / 0 0 var(--curve) var(--curve);
}
.hm-hero-media {
  position: absolute;
  inset: -4% 0 0;
  will-change: transform;
}
.hm-shot {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0;
  transform: scale(1.08);
  transition:
    opacity 1.4s ease,
    transform 7s ease-out;
}
.hm-shot.on {
  opacity: 1;
  transform: scale(1);
}
/* 上下压暗，保证白字可读 */
.hm-hero::before {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 1;
  background: linear-gradient(
    to bottom,
    rgba(61, 47, 34, 0.45) 0%,
    rgba(61, 47, 34, 0) 22%,
    rgba(61, 47, 34, 0) 48%,
    rgba(61, 47, 34, 0.62) 100%
  );
}
.hm-disclaimer {
  position: absolute;
  right: var(--gut-r);
  top: calc(84px + env(safe-area-inset-top, 0px));
  z-index: 2;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.75);
}
.hm-hero-foot {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 2;
}
.hm-hero-row {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px 32px;
  margin-bottom: clamp(8px, 1.4vw, 20px);
}
.hm-hero-intro {
  display: flex;
  align-items: center;
  gap: 14px;
  max-width: 460px;
  margin: 0;
  font-size: 15px;
  line-height: 1.65;
}
.hm-dot {
  flex: 0 0 52px;
  width: 52px;
  height: 52px;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid rgba(255, 255, 255, 0.8);
}
.hm-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.hm-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 36px;
  padding: 0 14px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.4);
  background: rgba(61, 47, 34, 0.35);
  font-size: 13px;
  color: var(--white);
}
.hm-pill-link:hover {
  background: var(--y);
  color: var(--ink);
  border-color: var(--y);
}
.hm-live {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #7ee0a1;
  box-shadow: 0 0 0 4px rgba(126, 224, 161, 0.2);
}
.hm-title {
  margin: 0;
  font: 900 clamp(52px, 12.4vw, 220px) / 0.92 var(--display);
  letter-spacing: -0.02em;
  white-space: nowrap;
  /* 只让两侧的字被弧线轻微裁到，中间完整露出 */
  margin-bottom: calc(var(--curve) * 0.45);
}
.hm-br {
  display: none;
}
.hm-ch {
  display: inline-block;
  animation: hm-rise 1s cubic-bezier(0.2, 0.8, 0.2, 1) both;
}
@media (max-width: 640px) {
  .hm-title {
    white-space: normal;
    font-size: clamp(52px, 17vw, 96px);
    margin-bottom: 0.15em;
  }
  .hm-br {
    display: inline;
  }
}

/* 店主的话 */
.hm-statement {
  padding-top: clamp(72px, 12vw, 180px);
  padding-bottom: clamp(64px, 10vw, 140px);
}
.hm-eyebrow {
  margin: 0 0 28px;
  font-size: 13px;
  letter-spacing: 0.24em;
  font-weight: 700;
  color: var(--g);
}
.hm-big {
  margin: 0;
  max-width: 1180px;
  font-size: clamp(24px, 3.3vw, 50px);
  line-height: 1.42;
  font-weight: 700;
  color: var(--faint);
}
.hm-big span {
  transition: color 0.25s;
}
.hm-big span.on {
  color: var(--g);
}
.hm-inl {
  display: inline-block;
  width: 1.9em;
  height: 0.92em;
  border-radius: 999px;
  object-fit: cover;
  vertical-align: -0.08em;
  margin: 0 0.12em;
  transform: scale(0.4);
  opacity: 0.4;
  transition:
    transform 0.6s cubic-bezier(0.34, 1.56, 0.64, 1),
    opacity 0.4s;
}
.hm-inl.on {
  transform: none;
  opacity: 1;
}

/* 标题字号 */
.hm-h2 {
  margin: 0;
  font: 900 clamp(38px, 6vw, 96px) / 1.02 var(--display);
  letter-spacing: -0.01em;
}

/* 买一只要几步：默认（手机）为普通排列 */
.hm-steps {
  padding-bottom: 96px;
}
.hm-steps-head {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: flex-end;
  gap: 12px;
  margin-bottom: 28px;
}
.hm-steps-head p {
  margin: 0;
  max-width: 360px;
  color: var(--muted);
}
.hm-frame {
  position: relative;
  aspect-ratio: 16 / 9;
  max-width: 100%;
  overflow: hidden;
  background: var(--g);
}
.hm-fs {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: opacity 0.5s;
}
.hm-fs + .hm-fs {
  opacity: 0;
}
.hm-fs.on {
  opacity: 1;
}
.hm-frame::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(to top, rgba(61, 47, 34, 0.7), rgba(61, 47, 34, 0) 55%);
}
.hm-caps {
  list-style: none;
  margin: 28px 0 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 28px;
}
.hm-cap {
  border-top: 2px solid var(--ink);
  padding-top: 14px;
}
.hm-n {
  font: 400 56px / 1 var(--hand);
  color: var(--g);
}
.hm-cap h3 {
  margin: 8px 0 6px;
  font-size: 22px;
}
.hm-cap p {
  margin: 0;
  color: var(--ink2);
  max-width: 42ch;
}
.hm-ticks {
  display: none;
}
/* 电脑：钉住整屏，卡片展开后三步轮流出现 */
.is-pin .hm-steps {
  height: 420vh;
  padding: 0;
}
.is-pin .hm-steps-stage {
  position: sticky;
  top: 0;
  height: 100vh;
  overflow: hidden;
}
.is-pin .hm-steps-head {
  position: absolute;
  left: 0;
  right: 0;
  top: 12vh;
  z-index: 3;
}
.is-pin .hm-frame {
  position: absolute;
  inset: 0;
  aspect-ratio: auto;
  clip-path: inset(24% 30% 14% 30% round 6px);
}
.is-pin .hm-caps {
  position: absolute;
  left: var(--gut);
  bottom: 9vh;
  z-index: 4;
  margin: 0;
  display: block;
  width: min(620px, 80vw);
  height: 300px;
  color: var(--white);
}
.is-pin .hm-cap {
  position: absolute;
  left: 0;
  bottom: 0;
  width: 100%;
  border-top-color: rgba(255, 255, 255, 0.6);
  opacity: 0;
  transform: translateY(40px);
  transition:
    opacity 0.45s,
    transform 0.6s cubic-bezier(0.2, 0.8, 0.2, 1);
}
.is-pin .hm-cap.on {
  opacity: 1;
  transform: none;
}
.is-pin .hm-n {
  color: var(--y);
  font-size: 88px;
}
.is-pin .hm-cap h3 {
  font-size: clamp(32px, 3.6vw, 56px);
  line-height: 1.15;
  margin: 6px 0 10px;
}
.is-pin .hm-cap p {
  color: rgba(255, 255, 255, 0.88);
  font-size: 17px;
}
.is-pin .hm-ticks {
  display: flex;
  flex-direction: column;
  gap: 10px;
  position: absolute;
  right: var(--gut-r);
  bottom: 9vh;
  z-index: 4;
}
.hm-ticks span {
  width: 3px;
  height: 40px;
  background: rgba(255, 255, 255, 0.3);
  transition: background 0.3s;
}
.hm-ticks span.on {
  background: var(--y);
}

/* 现在在店的 */
.hm-strip {
  background: var(--g);
  color: var(--white);
}
.hm-strip-stage {
  padding: clamp(72px, 9vw, 120px) 0;
}
.is-pin .hm-strip-stage {
  position: sticky;
  top: 0;
  height: 100vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 0;
}
.hm-strip-head {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: flex-end;
  gap: 16px;
  width: 100%;
  margin-bottom: clamp(24px, 3vw, 40px);
}
.hm-strip-head p {
  margin: 10px 0 0;
  font-size: 15px;
  color: rgba(255, 255, 255, 0.82);
}
.hm-track {
  display: flex;
  gap: clamp(16px, 2vw, 28px);
  overflow-x: auto;
  scroll-snap-type: x mandatory;
  padding: 0 var(--gut-r) 8px var(--gut);
  scrollbar-width: none;
}
.hm-track::-webkit-scrollbar {
  display: none;
}
.is-pin .hm-track {
  overflow: visible;
  will-change: transform;
}
.hm-pet {
  flex: 0 0 clamp(240px, 24vw, 380px);
  scroll-snap-align: start;
  color: var(--white);
}
.hm-pic {
  position: relative;
  aspect-ratio: 4 / 5;
  overflow: hidden;
  background: var(--g-deep);
}
.is-pin .hm-pic {
  height: min(52vh, 475px);
  aspect-ratio: auto;
}
.hm-pic img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.9s cubic-bezier(0.2, 0.8, 0.2, 1);
}
.hm-pet:hover .hm-pic img {
  transform: scale(1.05);
}
.hm-tag {
  position: absolute;
  left: 14px;
  top: 14px;
  background: var(--y);
  color: var(--ink);
  font-family: var(--hand);
  padding: 8px 14px 6px 24px;
  transform: rotate(-4deg);
  box-shadow: 0 1px 0 rgba(0, 0, 0, 0.18);
  line-height: 1.1;
}
.hm-tag::before {
  content: '';
  position: absolute;
  left: 8px;
  top: 50%;
  width: 7px;
  height: 7px;
  margin-top: -4px;
  border-radius: 50%;
  background: var(--paper);
  border: 1px solid rgba(0, 0, 0, 0.3);
}
.hm-tag span {
  font-size: 16px;
}
.hm-tag b {
  display: block;
  font-size: 28px;
}
.hm-stamp {
  position: absolute;
  right: 16px;
  top: 20px;
  border: 3px solid var(--red);
  color: var(--red);
  font-weight: 900;
  font-size: 19px;
  letter-spacing: 4px;
  padding: 3px 10px;
  transform: rotate(-10deg);
  background: rgba(255, 255, 255, 0.92);
}
.hm-pet-meta {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 12px;
  padding: 14px 0 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.25);
}
.hm-pet-meta strong {
  font-size: 22px;
}
.hm-pet-meta span {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.75);
}
.hm-pet p {
  margin: 8px 0 0;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.82);
}
.hm-pill-link-lg {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  min-height: 48px;
  padding: 0 22px;
  border-radius: 999px;
  border: 1px solid currentColor;
  font-size: 15px;
  transition:
    background 0.3s,
    color 0.3s,
    border-color 0.3s;
}
.hm-strip .hm-pill-link-lg:hover,
.hm-foot .hm-pill-link-lg:hover {
  background: var(--y);
  color: var(--ink);
  border-color: var(--y);
}
.hm-empty {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}
.hm-empty p {
  margin: 0;
  max-width: 560px;
  color: rgba(255, 255, 255, 0.85);
}

/* 按品种看 */
.hm-breeds {
  position: relative;
  z-index: 2;
  padding: clamp(80px, 10vw, 140px) 0 clamp(96px, 12vw, 180px);
  background: var(--paper);
  border-radius: 0 0 50% 50% / 0 0 var(--curve) var(--curve);
}
.hm-breeds .hm-h2 {
  color: var(--g);
}
.hm-sub {
  margin: 12px 0 48px;
  color: var(--muted);
}
.hm-rows {
  border-top: 1px solid var(--line);
}
.hm-row {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px 24px;
  padding: clamp(18px, 2.4vw, 30px) 0;
  border-bottom: 1px solid var(--line);
  color: var(--ink);
}
.hm-nm {
  font-size: clamp(28px, 3.8vw, 56px);
  font-weight: 700;
  line-height: 1.1;
  color: var(--g);
  transition: transform 0.5s cubic-bezier(0.2, 0.8, 0.2, 1);
}
.hm-row:hover .hm-nm,
.hm-row:focus-visible .hm-nm {
  transform: translateX(14px);
}
.hm-info {
  font-size: 15px;
  color: var(--ink2);
}
.hm-info small {
  display: block;
  font-size: 13px;
  color: var(--muted);
}
.hm-pr {
  font-size: clamp(24px, 3.4vw, 52px);
  font-weight: 700;
  color: var(--g);
  font-variant-numeric: tabular-nums;
  text-align: right;
  white-space: nowrap;
}
.hm-pr em {
  font-style: normal;
  font-size: 0.42em;
  font-weight: 500;
  color: var(--muted);
  margin-left: 4px;
}
@media (max-width: 640px) {
  .hm-row {
    grid-template-columns: minmax(0, 1fr) auto;
  }
  .hm-info {
    grid-column: 1 / -1;
    order: 3;
  }
}
.hm-breeds-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 40px;
}
.hm-breeds-foot p {
  margin: 0;
  color: var(--muted);
}
.hm-breeds .hm-pill-link-lg {
  color: var(--g);
}
.hm-breeds .hm-pill-link-lg:hover {
  background: var(--g);
  color: var(--white);
  border-color: var(--g);
}
.hm-peek {
  position: fixed;
  left: 0;
  top: 0;
  z-index: 40;
  width: 240px;
  height: 300px;
  object-fit: cover;
  pointer-events: none;
  opacity: 0;
  scale: 0.85;
  transition:
    opacity 0.3s,
    scale 0.3s;
}
.hm-peek.on {
  opacity: 1;
  scale: 1;
}

/* 页脚：按品种区块的弧形下边缘压在深色页脚上 */
.hm-foot {
  background: var(--night);
  color: var(--white);
  margin-top: calc(-1 * var(--curve));
  padding-top: calc(var(--curve) + clamp(64px, 8vw, 120px));
  padding-bottom: 28px;
  overflow: hidden;
}
.hm-foot.no-breeds {
  margin-top: 0;
  padding-top: clamp(64px, 8vw, 120px);
}
.hm-foot .hm-eyebrow {
  color: rgba(255, 255, 255, 0.7);
}
.hm-foot-grid {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 40px;
}
.hm-foot-h {
  margin: 0;
  font-size: clamp(44px, 7vw, 112px);
  line-height: 1.08;
  font-weight: 700;
}
.hm-foot-side {
  flex: 0 1 380px;
  display: flex;
  flex-direction: column;
  gap: 18px;
  font-size: 15px;
  color: rgba(255, 255, 255, 0.85);
}
.hm-foot-side p {
  margin: 0;
}
.hm-foot-side dl {
  margin: 0;
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 6px 18px;
}
.hm-foot-side dt {
  color: rgba(255, 255, 255, 0.55);
}
.hm-foot-side dd {
  margin: 0;
}
.hm-foot .hm-pill-link-lg {
  align-self: flex-start;
}
.hm-foot-bar {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 12px 24px;
  margin-top: clamp(56px, 7vw, 96px);
  padding-top: 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.18);
  font-size: 13px;
  color: rgba(255, 255, 255, 0.65);
}
.hm-foot-bar nav {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 20px;
}
.hm-foot-bar a,
.hm-foot-bar button {
  color: inherit;
  background: none;
  border: 0;
  padding: 0;
  font-size: 13px;
}
.hm-foot-bar a:hover,
.hm-foot-bar button:hover {
  color: var(--y);
}
/* 店名按字数自动缩放，铺满一行 */
.hm-giant {
  font: 900 min(23vw, calc(90vw / var(--n))) / 1 var(--display);
  color: var(--y);
  text-align: center;
  white-space: nowrap;
  margin-top: clamp(24px, 4vw, 56px);
  user-select: none;
}

/* 菜单 */
.hm-menu {
  position: fixed;
  inset: 0;
  z-index: 80;
  visibility: hidden;
  color: var(--white);
  transition: visibility 0s linear 0.7s;
}
.hm-menu.open {
  visibility: visible;
  transition: none;
}
.hm-shutters {
  position: absolute;
  inset: 0;
  display: flex;
}
.hm-shutters i {
  flex: 1;
  background: var(--g);
  transform: scaleY(0);
  transform-origin: top;
  transition: transform 0.6s cubic-bezier(0.76, 0, 0.24, 1);
}
.hm-menu.open .hm-shutters i {
  transform: scaleY(1);
}
.hm-shutters i:nth-child(2) {
  transition-delay: 0.05s;
}
.hm-shutters i:nth-child(3) {
  transition-delay: 0.1s;
}
.hm-shutters i:nth-child(4) {
  transition-delay: 0.15s;
}
.hm-shutters i:nth-child(5) {
  transition-delay: 0.2s;
}
.hm-menu-in {
  position: relative;
  height: 100%;
  display: flex;
  flex-direction: column;
  padding-top: calc(18px + env(safe-area-inset-top, 0px));
  padding-bottom: calc(32px + env(safe-area-inset-bottom, 0px));
  opacity: 0;
  transition: opacity 0.25s;
}
.hm-menu.open .hm-menu-in {
  opacity: 1;
  transition-delay: 0.45s;
}
.hm-menu-body {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 40px;
}
.hm-menu-links {
  list-style: none;
  margin: 0;
  padding: 0;
}
.hm-menu-links a,
.hm-menu-links button {
  display: inline-block;
  background: none;
  border: 0;
  padding: 0;
  color: var(--white);
  font: 900 clamp(36px, 5.6vw, 84px) / 1.2 var(--display);
  transition:
    color 0.25s,
    transform 0.4s cubic-bezier(0.2, 0.8, 0.2, 1);
}
.hm-menu-links a:hover,
.hm-menu-links a:focus-visible,
.hm-menu-links button:hover,
.hm-menu-links button:focus-visible {
  color: var(--y);
  transform: translateX(16px);
}
.hm-menu-prev {
  flex: 0 0 min(30vw, 420px);
  width: min(30vw, 420px);
  aspect-ratio: 4 / 5;
  object-fit: cover;
}
@media (max-width: 760px) {
  .hm-menu-prev {
    display: none;
  }
}
.hm-menu-foot {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 12px;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.75);
}

/* 手机底部有固定导航栏（style.css 中 .mobile-tabs），首屏文字和页脚为它留出空间 */
@media (max-width: 760px) {
  .hm {
    margin-bottom: -100px;
  }
  .hm-hero-foot {
    padding-bottom: 92px;
  }
  .hm-foot {
    padding-bottom: 100px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .hm-curtain {
    display: none;
  }
  .hm-ch,
  .hm-curtain b,
  .hm-curtain span {
    animation: none;
  }
  .hm-shot {
    transition: opacity 0.01s;
    transform: none;
  }
  .hm-inl {
    transform: none;
    opacity: 1;
  }
  .hm-shutters i,
  .hm-menu-in {
    transition: none !important;
  }
}
</style>
