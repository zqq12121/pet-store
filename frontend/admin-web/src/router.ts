import {createRouter,createWebHistory} from 'vue-router'
import {auth} from '../../shared/api'
import {isStaff,refreshManagementAccount} from './access'
const router=createRouter({history:createWebHistory(import.meta.env.BASE_URL),routes:[
 {path:'/login',component:()=>import('./views/Login.vue')},
 {path:'/security',component:()=>import('./views/Security.vue'),meta:{title:'账号安全'}},
 {path:'/',component:()=>import('./views/Dashboard.vue'),meta:{title:'工作台'}},
 {path:'/employees',component:()=>import('./views/Employees.vue'),meta:{title:'员工管理'}},
 {path:'/pets',component:()=>import('./views/Pets.vue'),meta:{title:'宠物管理'}},
 {path:'/pets/:id',component:()=>import('./views/PetEditor.vue'),meta:{title:'宠物档案'}},
 {path:'/orders',component:()=>import('./views/Orders.vue'),meta:{title:'预约与订单'}},
 {path:'/after-sales',component:()=>import('./views/AfterSales.vue'),meta:{title:'售后处理'}},
 {path:'/ai-statistics',component:()=>import('./views/AiStatistics.vue'),meta:{title:'AI 统计'}},
 {path:'/knowledge',component:()=>import('./views/Knowledge.vue'),meta:{title:'知识库'}},
 {path:'/agreements',component:()=>import('./views/Agreements.vue'),meta:{title:'协议管理'}},
 {path:'/shop',component:()=>import('./views/Shop.vue'),meta:{title:'门店设置'}},
 {path:'/community',component:()=>import('./views/Community.vue'),meta:{title:'社区管理'}},
 {path:'/community/:id',component:()=>import('./views/CommunityDetail.vue'),meta:{title:'社区内容与讨论'}},
 {path:'/:pathMatch(.*)*',redirect:'/'}
]})
router.beforeEach(async to=>{
 if(to.path==='/login')return true
 if(!auth('admin'))return {path:'/login',query:{redirect:to.fullPath}}
 try{await refreshManagementAccount()}catch{return {path:'/login',query:{redirect:to.fullPath}}}
 // 菜单之外仍检查直接输入的 URL；后端独立限制实际读写权限。
 if(isStaff.value){
  const allowed=['/','/orders','/security','/pets'].includes(to.path)||/^\/pets\/[^/]+$/.test(to.path)
  if(!allowed||to.path==='/pets/new')return '/'
 }
 return true
})
window.addEventListener('auth:expired',((event:CustomEvent)=>{if(event.detail==='admin')void router.replace('/login')}) as EventListener)
export default router
