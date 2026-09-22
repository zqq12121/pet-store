import type { ObjectDirective } from 'vue'

const cleanups=new WeakMap<HTMLElement,()=>void>()

// 元素进入视口才播放一次；不预先隐藏内容，慢请求和动画不可用时仍可正常阅读。
export const vReveal:ObjectDirective<HTMLElement,number|undefined>={
  mounted(el,{value}){
    const motion=window.matchMedia('(prefers-reduced-motion: reduce)')
    if(motion.matches||!('IntersectionObserver' in window))return
    let animation:Animation|undefined
    const observer=new IntersectionObserver(entries=>{
      if(!entries.some(entry=>entry.isIntersecting))return
      observer.disconnect()
      animation=el.animate([
        {opacity:0,transform:'translateY(18px)'},
        {opacity:1,transform:'translateY(0)'}
      ],{duration:560,delay:Math.min(value??0,180),easing:'cubic-bezier(.22,1,.36,1)',fill:'backwards'})
    },{threshold:0.08})
    // 切页或用户开启减少动画时，取消观察和动画，避免残留样式与后台任务。
    const cleanup=()=>{observer.disconnect();animation?.cancel();motion.removeEventListener('change',cleanup)}
    motion.addEventListener('change',cleanup)
    cleanups.set(el,cleanup)
    observer.observe(el)
  },
  beforeUnmount(el){cleanups.get(el)?.();cleanups.delete(el)}
}
