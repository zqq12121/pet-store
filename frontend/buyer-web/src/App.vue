<script setup lang="ts">
import { isDemo } from '../../shared/api'
</script>
<template>
 <a class="skip-link" href="#main">跳到正文</a>
 <header class="site-header"><div class="container nav"><RouterLink to="/" class="brand">暖爪 <span>paw & home</span></RouterLink><nav aria-label="主导航"><RouterLink to="/">首页</RouterLink><RouterLink to="/pets">发现宠物</RouterLink><RouterLink to="/ai">AI 养宠助手</RouterLink><RouterLink to="/shop">到店指南</RouterLink></nav><RouterLink class="btn secondary nav-order" to="/orders">我的订单</RouterLink><RouterLink class="desktop-profile text-link" to="/profile">我的</RouterLink><RouterLink class="mobile-ai" to="/ai">AI</RouterLink></div></header>
 <RouterLink class="floating-ai" to="/ai" aria-label="咨询 AI 养宠助手">AI 养宠助手 ↗</RouterLink>
 <main id="main"><RouterView :key="$route.path" /></main>
 <footer class="site-footer container"><RouterLink to="/" class="brand">暖爪 <span>paw & home</span></RouterLink><p>以陪伴为开始，以责任为约定。</p><RouterLink to="/shop">联系店主 ↗</RouterLink></footer>
 <nav class="mobile-tabs" aria-label="手机导航"><RouterLink to="/">首页</RouterLink><RouterLink to="/pets">找宠物</RouterLink><RouterLink to="/ai">AI 助手</RouterLink><RouterLink to="/profile">我的</RouterLink></nav>
 <div v-if="isDemo" class="demo-note" role="note">演示环境 · 示例宠物与门店信息 · 支付、短信和 AI 均为模拟</div>
</template>
