<script setup lang="ts">
import {ref,onMounted} from 'vue';import {admin,date} from '../../../shared/api';import type {Order,Page} from '../../../shared/types';import {useLoad} from '../../../shared/useLoad';import {managementAccount} from '../access'
const today=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai'}).format(new Date())
const arrivals=ref(0),pickups=ref(0),appointments=ref<Page<Order>>(),page=ref(1),{loading,error,run}=useLoad()
// 只查询接待需要的预约数据；经营金额、售后和 AI 统计不进入员工工作台。
const load=(next=1)=>run(async()=>{
 const [list,arrival,pickup]=await Promise.all([
  admin<Page<Order>>('GET','/admin/orders',undefined,{visitDate:today,page:next,pageSize:8}),
  admin<Page<Order>>('GET','/admin/orders',undefined,{status:'reservation_confirmed',pageSize:1}),
  admin<Page<Order>>('GET','/admin/orders',undefined,{status:'paid',pageSize:1})
 ])
 appointments.value=list;page.value=next;arrivals.value=arrival.total;pickups.value=pickup.total
})
onMounted(()=>load())
</script>
<template>
 <p class="eyebrow">RECEPTION DESK</p><h2>{{managementAccount?.displayName||'同事'}}，今天的接待从这里开始。</h2>
 <p>核对预约和联系人，确认收款与检疫证明后登记交付。</p>
 <el-alert v-if="error" :title="error" type="error" :closable="false"/>
 <div class="metrics reception-metrics" v-loading="loading"><article v-for="item in [['今日预约',appointments?.total??'—','按北京时间到店日期'],['待到店',arrivals,'已由店长确认的预约'],['待核销',pickups,'历史已付款订单']]" :key="item[0]" class="metric"><p>{{item[0]}}</p><strong>{{item[1]}}</strong><small>{{item[2]}}</small></article></div>
 <div class="toolbar"><el-button type="primary" size="large" @click="$router.push({path:'/orders',query:{status:'reservation_confirmed'}})">办理到店交付</el-button><el-button size="large" @click="$router.push('/orders?pickup=1')">自提码核销</el-button><el-button size="large" @click="$router.push('/pets')">查看宠物档案</el-button><el-button text :loading="loading" @click="load(page)">刷新待办</el-button></div>
 <div class="section-title"><h3>今日到店预约</h3><span class="small muted">{{today}} · 北京时间</span></div>
 <div class="table-panel"><el-table :data="appointments?.items||[]" v-loading="loading" empty-text="今天暂无到店预约"><el-table-column label="到店时间" min-width="150"><template #default="{row}">{{date(row.appointment?.visitAt)}}</template></el-table-column><el-table-column label="宠物 / 预约" min-width="240"><template #default="{row}">{{row.product.name}}<p class="small" style="margin:4px 0 0">{{row.orderNo}}</p></template></el-table-column><el-table-column prop="contactName" label="联系人" min-width="120"/><el-table-column prop="statusText" label="状态" min-width="120"/><el-table-column label="操作" width="120"><template #default="{row}"><el-button link type="primary" @click="$router.push({path:'/orders',query:{status:row.status}})">查看预约</el-button></template></el-table-column></el-table></div>
 <div class="pagination"><el-pagination v-if="appointments" layout="prev,pager,next" :total="appointments.total" :current-page="page" :page-size="8" @current-change="load"/></div>
 <p class="status-note" style="margin-top:24px">预约确认、取消和退款审批请联系店长。交付记录会保存当前员工账号，便于核对经办人。</p>
</template>
<style scoped>.reception-metrics{grid-template-columns:repeat(3,minmax(0,1fr))}@media(max-width:720px){.reception-metrics{grid-template-columns:1fr}.section-title{flex-wrap:wrap}}</style>
