<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';

/*
 * 鼠标移到带 data-cursor 属性的元素上时，出现跟随鼠标的黄色圆形提示，文字取属性值（如“看看”）。
 * 只在电脑上显示；系统开启“减少动画”时不显示。
 */
const enabled =
  window.matchMedia('(pointer: fine)').matches &&
  !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
const label = ref(''),
  on = ref(false),
  el = ref<HTMLElement>();
let mx = 0,
  my = 0,
  x = 0,
  y = 0,
  raf = 0;

function frame() {
  raf = requestAnimationFrame(frame);
  x += (mx - x) * 0.22;
  y += (my - y) * 0.22;
  if (el.value) el.value.style.transform = `translate3d(${x}px, ${y}px, 0)`;
}
function onMove(e: MouseEvent) {
  mx = e.clientX;
  my = e.clientY;
  const hit = (e.target as Element | null)?.closest<HTMLElement>('[data-cursor]');
  if (hit) label.value = hit.dataset.cursor || '';
  on.value = !!hit;
}
function onLeave() {
  on.value = false;
}

onMounted(() => {
  if (!enabled) return;
  window.addEventListener('mousemove', onMove, { passive: true });
  document.addEventListener('mouseleave', onLeave);
  raf = requestAnimationFrame(frame);
});
onUnmounted(() => {
  cancelAnimationFrame(raf);
  window.removeEventListener('mousemove', onMove);
  document.removeEventListener('mouseleave', onLeave);
});
</script>
<template>
  <div v-if="enabled" ref="el" class="cursor-bubble" :class="{ on }" aria-hidden="true">
    <span>{{ label }}</span>
  </div>
</template>
<style scoped>
.cursor-bubble {
  position: fixed;
  left: -42px;
  top: -42px;
  z-index: 90;
  pointer-events: none;
}
.cursor-bubble span {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 84px;
  height: 84px;
  border-radius: 50%;
  background: var(--tag);
  color: var(--ink);
  font-weight: 700;
  font-size: 15px;
  scale: 0;
  transition: scale 0.35s cubic-bezier(0.2, 0.8, 0.2, 1);
}
.cursor-bubble.on span {
  scale: 1;
}
</style>
