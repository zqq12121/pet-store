import { createRouter,createWebHistory } from 'vue-router'
import { auth } from '../../shared/api'
const router=createRouter({history:createWebHistory(import.meta.env.BASE_URL),scrollBehavior(to,from,saved){return saved || (to.path===from.path?undefined:{top:0})},routes:[
 {path:'/',component:()=>import('./views/Home.vue')},
 {path:'/pets',component:()=>import('./views/Pets.vue')},
 {path:'/pets/:petId',component:()=>import('./views/Pet.vue')},
 {path:'/login',component:()=>import('./views/Login.vue')},
 {path:'/password-login',component:()=>import('./views/PasswordLogin.vue')},
 {path:'/password-reset',component:()=>import('./views/PasswordReset.vue')},
 // 协议无需登录即可阅读，登录页在新标签页打开以保留输入。
 {path:'/terms',component:()=>import('./views/Legal.vue'),props:{kind:'terms'}},
 {path:'/privacy',component:()=>import('./views/Legal.vue'),props:{kind:'privacy'}},
 {path:'/checkout/:petId',component:()=>import('./views/Checkout.vue'),meta:{auth:true}},
 {path:'/orders',component:()=>import('./views/Orders.vue'),meta:{auth:true}},
 {path:'/orders/:orderId',component:()=>import('./views/Order.vue'),meta:{auth:true}},
 {path:'/orders/:orderId/payment',component:()=>import('./views/Payment.vue'),meta:{auth:true}},
 {path:'/orders/:orderId/pickup',component:()=>import('./views/Pickup.vue'),meta:{auth:true}},
 {path:'/orders/:orderId/after-sale',component:()=>import('./views/AfterSale.vue'),meta:{auth:true}},
 {path:'/after-sales/:id',component:()=>import('./views/AfterSale.vue'),meta:{auth:true}},
 {path:'/ai',component:()=>import('./views/Chat.vue')},
 {path:'/shop',component:()=>import('./views/Shop.vue')},
 // 公开社区与受保护的发布、消息入口使用同一买家会话。
 {path:'/community',component:()=>import('./views/Community.vue')},
 {path:'/community/new',component:()=>import('./views/CommunityEditor.vue'),meta:{auth:true}},
 {path:'/community/notices',component:()=>import('./views/CommunityNotices.vue'),meta:{auth:true}},
 {path:'/community/:id/edit',component:()=>import('./views/CommunityEditor.vue'),meta:{auth:true}},
 {path:'/community/:id',component:()=>import('./views/CommunityContent.vue')},
 {path:'/profile',component:()=>import('./views/Profile.vue'),meta:{auth:true}},
 {path:'/:pathMatch(.*)*',component:()=>import('./views/NotFound.vue')}
]})
router.beforeEach(to=>to.meta.auth&&!auth('buyer')?{path:'/login',query:{redirect:to.fullPath}}:true)
window.addEventListener('auth:expired',((e:CustomEvent)=>{if(e.detail==='buyer'&&router.currentRoute.value.meta.auth)void router.push({path:'/login',query:{redirect:router.currentRoute.value.fullPath}})}) as EventListener)
export default router
