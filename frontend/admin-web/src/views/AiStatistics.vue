<script setup lang="ts">
import {ref,onMounted} from 'vue'
import {admin,date} from '../../../shared/api'
import type {AiStatistics} from '../../../shared/adminTypes'
import {useLoad} from '../../../shared/useLoad'
const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date())
const start=ref(today),end=ref(today),data=ref<AiStatistics>(),{loading,error,run}=useLoad()
const duration=(value:number|null)=>value===null?'尚无样本':`${(value/1000).toFixed(2)} 秒`
const load=()=>run(async()=>{if(!start.value||!end.value)throw new Error('请选择起止日期');data.value=await admin<AiStatistics>('GET','/admin/ai/statistics',undefined,{startDate:start.value,endDate:end.value})})
onMounted(load)
</script>
<template>
 <p class="eyebrow">AI OPERATIONS</p><h2>了解咨询，持续改进回答。</h2>
 <p>按北京时间统计被受理的问题；重复重放不重复计数。反馈展示这些回答的当前评价，成功生成不代表问题已解决。</p>
 <div class="toolbar"><el-date-picker v-model="start" type="date" value-format="YYYY-MM-DD" aria-label="统计开始日期" placeholder="开始日期"/><span>至</span><el-date-picker v-model="end" type="date" value-format="YYYY-MM-DD" aria-label="统计结束日期" placeholder="结束日期"/><el-button type="primary" :loading="loading" @click="load">查询统计</el-button></div>
 <el-alert v-if="error" :title="error" type="error" :closable="false"/><el-skeleton v-if="loading&&!data" :rows="5" animated/>
 <template v-if="data">
  <p class="small muted">当前结果：{{data.startDate}} 至 {{data.endDate}} · 最多查询 93 天</p>
  <div class="metrics"><article v-for="item in [['有提问的会话',data.totals.sessionCount],['提问数',data.totals.questionCount],['完成回答',data.totals.completedCount],['失败回答',data.totals.failedCount]]" :key="item[0]" class="metric"><p>{{item[0]}}</p><strong>{{item[1]}}</strong></article></div>
  <div class="panel"><h3>反馈与响应速度</h3><p>赞 {{data.totals.positiveCount}} · 踩 {{data.totals.negativeCount}} · 正在生成 {{data.totals.streamingCount}}</p><p>平均首字：{{duration(data.totals.avgFirstTokenMs)}}（{{data.totals.firstTokenSamples}} 条样本）<br>平均生成耗时：{{duration(data.totals.avgGenerationMs)}}（{{data.totals.generationSamples}} 条样本，包含失败请求）</p><small class="muted">耗时为服务端生成用时，包含工具查询；不含网页渲染。历史未采集数据不补记为 0。</small></div>
  <div class="panel"><h3>知识库当前状态</h3><p>共 {{data.knowledge.total}} 条 · 草稿 {{data.knowledge.draft}} · 已发布 {{data.knowledge.published}} · 已归档 {{data.knowledge.archived}}</p><p>可用于回答 {{data.knowledge.ready}} · 等待/正在更新 {{data.knowledge.pending}} · 更新失败 {{data.knowledge.failed}}</p><el-button @click="$router.push('/knowledge')">维护知识与查看导入记录</el-button></div>
  <h3>每日明细</h3><div class="table-panel"><el-table :data="data.daily"><el-table-column prop="date" label="日期" min-width="130"/><el-table-column prop="sessionCount" label="会话"/><el-table-column prop="questionCount" label="提问"/><el-table-column prop="completedCount" label="完成"/><el-table-column prop="failedCount" label="失败"/><el-table-column prop="positiveCount" label="赞"/><el-table-column prop="negativeCount" label="踩"/></el-table></div><p class="small muted">会话按所选范围去重，跨日会话的每日计数不能直接相加。更新时间 {{date(data.updatedAt)}}</p>
 </template>
</template>
