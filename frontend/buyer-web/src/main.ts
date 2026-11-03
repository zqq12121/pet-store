import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import router from './router';
import { startSmoothScroll, stopSmoothScroll } from './motion';
import './style.css';
// 电脑上全站平滑滚动；切换页面前停下惯性，交给路由恢复滚动位置。
startSmoothScroll();
router.beforeEach(() => stopSmoothScroll());
createApp(App).use(createPinia()).use(router).mount('#app');
