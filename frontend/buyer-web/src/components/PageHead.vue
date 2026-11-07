<script setup lang="ts">
/*
 * 页面开头：与首页同一套语言——奶油色横带、弧形下边缘、粗黑大标题，
 * 右侧叠放几张圆形宠物照片。back 用于返回上一级（真实的层级，不是装饰标签）。
 * 标题字体只收录常用汉字，title 应传写死的文案；动态内容放进正文区。
 */
defineProps<{
  title: string;
  lead?: string;
  photos?: string[];
  back?: { to: string; label: string };
  compact?: boolean;
  /** 与正文的窄版（.narrow）左边对齐 */
  narrow?: boolean;
}>();
</script>
<template>
  <header class="page-head" :class="{ compact }">
    <div class="container page-head-inner" :class="{ narrow }">
      <div class="page-head-text">
        <RouterLink v-if="back" class="page-back" :to="back.to">← {{ back.label }}</RouterLink>
        <h1>{{ title }}</h1>
        <p v-if="lead" class="page-lead">{{ lead }}</p>
        <slot />
      </div>
      <div v-if="photos?.length" class="page-head-photos" aria-hidden="true">
        <img v-for="(src, i) in photos" :key="src" :src="src" alt="" :class="`p${i}`" />
      </div>
      <div v-if="$slots.actions" class="page-head-actions"><slot name="actions" /></div>
    </div>
  </header>
</template>
