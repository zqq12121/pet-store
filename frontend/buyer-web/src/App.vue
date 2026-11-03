<script setup lang="ts">
import { isDemo, isJavaLocal } from '../../shared/api';
import CommunityNoticeLink from './components/CommunityNoticeLink.vue';
</script>
<template>
  <a class="skip-link" href="#main">跳到正文</a>
  <!-- 首页自带全屏顶部和页脚，其他页面使用统一导航。 -->
  <header v-if="$route.path !== '/'" class="site-header">
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
  <footer v-if="$route.path !== '/'" class="site-footer container">
    <RouterLink to="/" class="brand">茸茸星球</RouterLink>
    <p>线上只占位不扣款，到店看过再付款。</p>
    <RouterLink to="/shop">联系店主 ↗</RouterLink>
  </footer>
  <nav class="mobile-tabs" aria-label="手机导航">
    <RouterLink to="/">首页</RouterLink>
    <RouterLink to="/pets">找猫狗</RouterLink>
    <RouterLink to="/ai">问 AI</RouterLink>
    <RouterLink to="/community">社区</RouterLink>
    <RouterLink to="/profile">我的</RouterLink>
  </nav>
  <div v-if="isDemo" class="demo-note" role="note">
    {{
      isJavaLocal
        ? '本地联调 · 支付、短信为模拟服务 · AI 暂未接入'
        : '演示环境 · 示例宠物与门店信息 ·支付、短信和 AI 均为模拟'
    }}
  </div>
</template>
