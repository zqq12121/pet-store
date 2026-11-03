<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import { isDemo, isJavaLocal } from '../../shared/api';
import CommunityNoticeLink from './components/CommunityNoticeLink.vue';
import CursorBubble from './components/CursorBubble.vue';
import { smoothScrollTo } from './motion';
import { vReveal } from './reveal';

// 往下滚时收起顶部导航，往上滚时立即出现，给内容让出空间。
const headerHidden = ref(false);
let lastY = 0;
function onScroll() {
  const y = window.scrollY;
  if (Math.abs(y - lastY) < 6) return;
  headerHidden.value = y > lastY && y > 160;
  lastY = y;
}
onMounted(() => window.addEventListener('scroll', onScroll, { passive: true }));
onUnmounted(() => window.removeEventListener('scroll', onScroll));
</script>
<template>
  <a class="skip-link" href="#main">跳到正文</a>
  <!-- 首页自带全屏顶部和页脚，其他页面使用统一导航。 -->
  <header
    v-if="$route.path !== '/'"
    class="site-header"
    :class="{ hidden: headerHidden }"
    @focusin="headerHidden = false"
  >
    <div class="container nav">
      <RouterLink to="/" class="brand">茸茸星球</RouterLink>
      <nav aria-label="主导航">
        <RouterLink to="/">首页</RouterLink>
        <RouterLink to="/pets">找猫狗</RouterLink>
        <RouterLink to="/ai">问问怎么养</RouterLink>
        <RouterLink to="/community">宠友社区</RouterLink>
        <RouterLink to="/shop">到店指南</RouterLink>
      </nav>
      <RouterLink class="btn secondary nav-order" to="/orders">我的预约</RouterLink>
      <CommunityNoticeLink class="header-community-notices" />
      <RouterLink class="desktop-profile text-link" to="/profile">我的</RouterLink>
      <RouterLink class="mobile-ai" to="/ai">AI</RouterLink>
    </div>
  </header>
  <!-- 首页已有咨询入口；聊天页隐藏悬浮入口，避免遮挡发送按钮。 -->
  <RouterLink
    v-if="$route.path !== '/' && $route.path !== '/ai'"
    class="floating-ai"
    to="/ai"
    aria-label="咨询 AI 养宠助手"
    >问问 AI ↗</RouterLink
  >
  <!-- 只按路径切换，筛选参数变化时保留当前页面与输入状态；不等待旧页退出。 -->
  <main id="main">
    <RouterView v-slot="{ Component, route }">
      <Transition name="page" appear>
        <div :key="route.path" class="route-page">
          <component :is="Component" />
        </div>
      </Transition>
    </RouterView>
  </main>
  <!-- 页脚：深色、弧形上边缘、超大店名，与首页一致 -->
  <footer v-if="$route.path !== '/'" class="site-footer">
    <div class="container footer-row">
      <div>
        <p class="footer-eyebrow">有想看的？</p>
        <p class="footer-title">先来店里坐一坐。</p>
      </div>
      <nav aria-label="页脚">
        <RouterLink to="/pets">找猫狗</RouterLink>
        <RouterLink to="/ai">问问怎么养</RouterLink>
        <RouterLink to="/community">宠友社区</RouterLink>
        <RouterLink to="/shop">到店指南</RouterLink>
        <RouterLink to="/terms">用户协议</RouterLink>
        <RouterLink to="/privacy">隐私政策</RouterLink>
      </nav>
    </div>
    <div class="container footer-bar">
      <span>线上只占位不扣款，到店看过再付款。</span>
      <button type="button" @click="smoothScrollTo(0)">回到顶部 ↑</button>
    </div>
    <div v-reveal class="footer-giant" aria-hidden="true">茸茸星球</div>
  </footer>
  <nav class="mobile-tabs" aria-label="手机导航">
    <RouterLink to="/">首页</RouterLink>
    <RouterLink to="/pets">找猫狗</RouterLink>
    <RouterLink to="/ai">问 AI</RouterLink>
    <RouterLink to="/community">社区</RouterLink>
    <RouterLink to="/profile">我的</RouterLink>
  </nav>
  <CursorBubble />
  <div v-if="isDemo" class="demo-note" role="note">
    {{
      isJavaLocal
        ? '本地联调 · 支付、短信为模拟服务 · AI 暂未接入'
        : '演示环境 · 示例宠物与门店信息 ·支付、短信和 AI 均为模拟'
    }}
  </div>
</template>
