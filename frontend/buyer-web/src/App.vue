<script setup lang="ts">
import { isDemo,isJavaLocal } from '../../shared/api'
</script>
<template>
 <a class="skip-link" href="#main">跳到正文</a>
 <header class="site-header"><div class="container nav"><RouterLink to="/" class="brand">暖爪 <span>paw & home</span></RouterLink><nav aria-label="主导航"><RouterLink to="/">首页</RouterLink><RouterLink to="/pets">发现宠物</RouterLink><RouterLink to="/ai">AI 养宠助手</RouterLink><RouterLink to="/shop">到店指南</RouterLink></nav><RouterLink class="btn secondary nav-order" to="/orders">我的预约</RouterLink><RouterLink class="desktop-profile text-link" to="/profile">我的</RouterLink><RouterLink class="mobile-ai" to="/ai">AI</RouterLink></div></header>
 <!-- 首页已有咨询入口；聊天页隐藏悬浮入口，避免遮挡发送按钮。 -->
 <RouterLink v-if="$route.path!=='/'&&$route.path!=='/ai'" class="floating-ai" to="/ai" aria-label="咨询 AI 养宠助手">AI 养宠助手 ↗</RouterLink>
 <!-- 只按路径切换，筛选参数变化时保留当前页面与输入状态；不等待旧页退出。 -->
 <main id="main"><RouterView v-slot="{Component,route}"><Transition name="page" appear><div :key="route.path" class="route-page"><component :is="Component" /></div></Transition></RouterView></main>
 <footer class="site-footer container"><RouterLink to="/" class="brand">暖爪 <span>paw & home</span></RouterLink><p>以陪伴为开始，以责任为约定。</p><RouterLink to="/shop">联系店主 ↗</RouterLink></footer>
 <nav class="mobile-tabs" aria-label="手机导航"><RouterLink to="/">首页</RouterLink><RouterLink to="/pets">找宠物</RouterLink><RouterLink to="/ai">AI 助手</RouterLink><RouterLink to="/profile">我的</RouterLink></nav>
 <div v-if="isDemo" class="demo-note" role="note">{{isJavaLocal?'本地联调 · 支付、短信为模拟服务 · AI 暂未接入':'演示环境 · 示例宠物与门店信息 · 支付、短信和 AI 均为模拟'}}</div>
</template>
