import {createRouter,createWebHistory} from 'vue-router'
import {auth} from '../../shared/api'
const router=createRouter({history:createWebHistory(import.meta.env.BASE_URL),routes:[
 {path:'/login',component:()=>import('./views/Login.vue')},
 {path:'/',component:()=>import('./views/Dashboard.vue'),meta:{title:'工作台'}},
 {path:'/pets',component:()=>import('./views/Pets.vue'),meta:{title:'宠物管理'}},
 {path:'/pets/:id',component:()=>import('./views/PetEditor.vue'),meta:{title:'宠物档案'}},
 {path:'/orders',component:()=>import('./views/Orders.vue'),meta:{title:'预约与订单'}},
 {path:'/after-sales',component:()=>import('./views/AfterSales.vue'),meta:{title:'售后处理'}},
 {path:'/ai-statistics',component:()=>import('./views/AiStatistics.vue'),meta:{title:'AI 统计'}},
 {path:'/knowledge',component:()=>import('./views/Knowledge.vue'),meta:{title:'知识库'}},
 {path:'/agreements',component:()=>import('./views/Agreements.vue'),meta:{title:'协议管理'}},
 {path:'/shop',component:()=>import('./views/Shop.vue'),meta:{title:'门店设置'}},
 {path:'/:pathMatch(.*)*',redirect:'/'}
]})
router.beforeEach(to=>to.path!=='/login'&&!auth('admin')?{path:'/login',query:{redirect:to.fullPath}}:true)
window.addEventListener('auth:expired',((event:CustomEvent)=>{if(event.detail==='admin')void router.replace('/login')}) as EventListener)
export default router
