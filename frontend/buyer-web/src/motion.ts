/*
 * 全站平滑滚动：鼠标滚轮的位移按惯性逐帧追上，参考 onform.studio 的滚动手感。
 * 只在电脑（精确指针）上启用；系统开启“减少动画”、横向滑动、按住 Ctrl 缩放、页面被弹层锁定，
 * 或鼠标下方的区域自己能滚动（聊天记录、弹窗、筛选抽屉）时，都交给浏览器原生滚动。
 */
const reduce = window.matchMedia('(prefers-reduced-motion: reduce)');
const finePointer = window.matchMedia('(pointer: fine)');

let target = 0,
  current = 0,
  smoothing = false,
  raf = 0,
  started = false;

const clamp = (v: number, min: number, max: number) => Math.min(max, Math.max(min, v));
const enabled = () => finePointer.matches && !reduce.matches;
const maxScroll = () => document.documentElement.scrollHeight - window.innerHeight;

function step() {
  current += (target - current) * 0.11;
  if (Math.abs(target - current) < 0.5) {
    current = target;
    smoothing = false;
  }
  window.scrollTo(0, current);
  if (smoothing) raf = requestAnimationFrame(step);
}
function kick() {
  if (smoothing) return;
  smoothing = true;
  raf = requestAnimationFrame(step);
}

// 鼠标下方是否有自己能朝这个方向继续滚动的区域。
function scrollsInside(node: EventTarget | null, dy: number) {
  let el = node instanceof Element ? node : null;
  while (el && el !== document.body && el !== document.documentElement) {
    const { overflowY } = getComputedStyle(el);
    if ((overflowY === 'auto' || overflowY === 'scroll') && el.scrollHeight > el.clientHeight) {
      const atTop = el.scrollTop <= 0;
      const atBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 1;
      if ((dy < 0 && !atTop) || (dy > 0 && !atBottom)) return true;
    }
    el = el.parentElement;
  }
  return false;
}

function onWheel(e: WheelEvent) {
  if (!enabled() || e.ctrlKey || e.defaultPrevented) return;
  if (Math.abs(e.deltaX) > Math.abs(e.deltaY)) return;
  // 菜单、弹窗打开时页面被锁定滚动。
  if (
    document.documentElement.style.overflow === 'hidden' ||
    document.body.style.overflow === 'hidden'
  )
    return;
  if (scrollsInside(e.target, e.deltaY)) return;
  e.preventDefault();
  const unit = e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? window.innerHeight : 1;
  if (!smoothing) current = target = window.scrollY;
  target = clamp(target + e.deltaY * unit, 0, maxScroll());
  kick();
}
// 键盘、滚动条、路由跳转等原生滚动发生时，同步当前位置。
function onNativeScroll() {
  if (!smoothing) current = target = window.scrollY;
}

/** 立即停下惯性，切换页面时调用，避免旧页面的滚动目标带到新页面。 */
export function stopSmoothScroll() {
  cancelAnimationFrame(raf);
  smoothing = false;
  current = target = window.scrollY;
}

/** 平滑滚动到指定位置（如“回到顶部”）。 */
export function smoothScrollTo(y: number) {
  if (!enabled()) {
    window.scrollTo({ top: y, behavior: reduce.matches ? 'auto' : 'smooth' });
    return;
  }
  if (!smoothing) current = window.scrollY;
  target = clamp(y, 0, maxScroll());
  kick();
}

export function startSmoothScroll() {
  if (started) return;
  started = true;
  current = target = window.scrollY;
  window.addEventListener('wheel', onWheel, { passive: false });
  window.addEventListener('scroll', onNativeScroll, { passive: true });
}

/**
 * 价格数字乱跳几下后停在真实价格（参考 onform 价格表的悬停效果）。
 * onFrame 每帧收到当前要显示的文字，最后一次收到原文；返回值用于提前停止。
 */
export function scrambleDigits(final: string, onFrame: (text: string) => void) {
  if (reduce.matches) return () => {};
  let f = 0;
  const timer = setInterval(() => {
    f++;
    const done = f > 10;
    onFrame(
      done
        ? final
        : final.replace(/\d/g, (d, i: number) =>
            f > 4 + i / 2 ? d : String(Math.floor(Math.random() * 10))
          )
    );
    if (done) clearInterval(timer);
  }, 40);
  return () => clearInterval(timer);
}
