import type { ObjectDirective } from 'vue';

const cleanups = new WeakMap<HTMLElement, () => void>();

// 元素进入视口才播放一次；不预先隐藏内容，慢请求和动画不可用时仍可正常阅读。
export const vReveal: ObjectDirective<HTMLElement, number | undefined> = {
  mounted(el, { value }) {
    const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
    if (motion.matches || !('IntersectionObserver' in window)) return;
    let animation: Animation | undefined;
    const observer = new IntersectionObserver(
      (entries) => {
        if (!entries.some((entry) => entry.isIntersecting)) return;
        observer.disconnect();
        animation = el.animate(
          [
            { opacity: 0, transform: 'translateY(18px)' },
            { opacity: 1, transform: 'translateY(0)' },
          ],
          {
            duration: 560,
            delay: Math.min(value ?? 0, 180),
            easing: 'cubic-bezier(.22,1,.36,1)',
            fill: 'backwards',
          }
        );
      },
      { threshold: 0.08 }
    );
    // 切页或用户开启减少动画时，取消观察和动画，避免残留样式与后台任务。
    const cleanup = () => {
      observer.disconnect();
      animation?.cancel();
      motion.removeEventListener('change', cleanup);
    };
    motion.addEventListener('change', cleanup);
    cleanups.set(el, cleanup);
    observer.observe(el);
  },
  beforeUnmount(el) {
    cleanups.get(el)?.();
    cleanups.delete(el);
  },
};

const wipeCleanups = new WeakMap<HTMLElement, () => void>();

/*
 * 照片揭开：元素快进入视口时，从下往上揭开，里面的图片同时从略大缩回原尺寸（参考 onform 的图片入场）。
 * 提前 15% 视口高度开始，避免看到图片先出现再被遮住；不支持或减少动画时直接显示。
 */
export const vWipe: ObjectDirective<HTMLElement> = {
  mounted(el) {
    const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
    if (motion.matches || !('IntersectionObserver' in window)) return;
    const animations: Animation[] = [];
    const observer = new IntersectionObserver(
      (entries) => {
        if (!entries.some((entry) => entry.isIntersecting)) return;
        observer.disconnect();
        animations.push(
          el.animate([{ clipPath: 'inset(100% 0 0 0)' }, { clipPath: 'inset(0 0 0 0)' }], {
            duration: 900,
            easing: 'cubic-bezier(.76,0,.24,1)',
          })
        );
        // 只缩放容器里的图片；直接用在 <img> 上时不缩放，避免图片撑出自身范围。
        const img = el.querySelector('img');
        if (img)
          animations.push(
            img.animate([{ transform: 'scale(1.18)' }, { transform: 'scale(1)' }], {
              duration: 1300,
              easing: 'cubic-bezier(.2,.8,.2,1)',
            })
          );
      },
      { rootMargin: '0px 0px 15% 0px' }
    );
    const cleanup = () => {
      observer.disconnect();
      animations.forEach((a) => a.cancel());
      motion.removeEventListener('change', cleanup);
    };
    motion.addEventListener('change', cleanup);
    wipeCleanups.set(el, cleanup);
    observer.observe(el);
  },
  beforeUnmount(el) {
    wipeCleanups.get(el)?.();
    wipeCleanups.delete(el);
  },
};
