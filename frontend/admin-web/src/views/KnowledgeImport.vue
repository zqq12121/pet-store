<script setup lang="ts">
import {ref,onUnmounted} from 'vue'
import {admin,headers,clearAuth,date} from '../../../shared/api'
import type {ImportJob,ImportError} from '../../../shared/adminTypes'
import type {Page} from '../../../shared/types'
import {useLoad} from '../../../shared/useLoad'
const emit=defineEmits<{imported:[]}>()
const visible=ref(false),file=ref<File>(),key=ref(''),jobs=ref<ImportJob[]>([]),selected=ref<ImportJob>(),errors=ref<Page<ImportError>>(),errorPage=ref(1),{loading,error,run}=useLoad()
const labels:Record<string,string>={queued:'排队中',running:'导入中',succeeded:'全部成功',partial_succeeded:'部分成功',failed:'导入失败'}
let timer:ReturnType<typeof setTimeout>|undefined,disposed=false
// 文件下载与原始二进制上传保留管理员认证，401 与普通接口保持相同退出行为。
async function transfer(path:string,options:RequestInit={}){const response=await fetch('/api/v1/admin/knowledge/'+path,{...options,headers:{...headers('admin'),...options.headers}});if(!response.ok){if(response.status===401){clearAuth('admin');window.dispatchEvent(new CustomEvent('auth:expired',{detail:'admin'}))}const body=await response.json().catch(()=>({}));throw new Error(body.message||'文件请求失败，请重试')}return response}
const download=(format:'csv'|'xlsx')=>run(async()=>{const response=await transfer('import-template?format='+format);const url=URL.createObjectURL(await response.blob());const link=document.createElement('a');link.href=url;link.download=`knowledge-import-template.${format}`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000)})
function close(){clearTimeout(timer)}
function choose(event:Event){file.value=(event.target as HTMLInputElement).files?.[0];key.value=crypto.randomUUID()}
async function refresh(){clearTimeout(timer);const result=await admin<ImportJob[]>('GET','/admin/knowledge/jobs');if(disposed||!visible.value)return;jobs.value=result;if(result.some(job=>['queued','running'].includes(job.status)))timer=setTimeout(()=>{void refresh().catch(e=>{error.value=e instanceof Error?e.message:'任务状态查询失败'})},2000);else emit('imported')}
const open=()=>{visible.value=true;void run(refresh)}
const submit=()=>run(async()=>{if(!file.value)throw new Error('请先选择文件');const format=file.value.name.split('.').pop()?.toLowerCase();if(format!=='csv'&&format!=='xlsx')throw new Error('只支持 .csv 和 .xlsx 文件');if(file.value.size>10*1024*1024)throw new Error('文件不能超过 10 MB');const response=await transfer('import-jobs?format='+format,{method:'POST',body:file.value,headers:{'Idempotency-Key':key.value,'Content-Type':format==='csv'?'text/csv':'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'}});const job=(await response.json()).data as ImportJob;selected.value=job;errors.value=undefined;await refresh()})
const readErrors=()=>run(async()=>{if(selected.value)errors.value=await admin<Page<ImportError>>('GET',`/admin/knowledge/jobs/${selected.value.id}/errors`,undefined,{page:errorPage.value,pageSize:20})})
function inspect(job:ImportJob){selected.value=job;errorPage.value=1;void readErrors()}
onUnmounted(()=>{disposed=true;clearTimeout(timer)})
</script>
<template>
 <el-button @click="open">批量导入 / 导入记录</el-button>
 <el-dialog v-model="visible" title="批量导入知识" width="min(900px, 94vw)" @closed="close">
  <p>下载模板并替换或删除示例。支持 UTF-8 CSV、Excel，单次最多 10 MB / 5000 条。仅新增草稿，须逐条审核发布后才能用于回答。</p>
  <div class="toolbar"><el-button :disabled="loading" @click="download('csv')">下载 CSV 模板</el-button><el-button :disabled="loading" @click="download('xlsx')">下载 Excel 模板</el-button></div>
  <input type="file" accept=".csv,.xlsx" aria-label="选择知识导入文件" class="native-upload" :disabled="loading" @change="choose"/>
  <p class="small muted">同一文件直接重试不会重复导入。重新选择文件会创建新任务，也会新增草稿，请勿重复选择已成功导入的文件。</p>
  <div class="toolbar"><el-button type="primary" :loading="loading" :disabled="!file" @click="submit">导入为草稿</el-button><el-button :disabled="loading" @click="run(refresh)">刷新记录</el-button></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false"/>
  <h3>最近 100 次导入</h3><el-table :data="jobs" empty-text="暂无导入记录"><el-table-column label="提交时间" min-width="140"><template #default="{row}">{{date(row.createdAt)}}</template></el-table-column><el-table-column label="状态" min-width="100"><template #default="{row}">{{labels[row.status]}}</template></el-table-column><el-table-column prop="successCount" label="成功" width="70"/><el-table-column prop="failedCount" label="失败" width="70"/><el-table-column label="结果" min-width="160"><template #default="{row}"><span v-if="row.errorMessage">{{row.errorMessage}}</span><el-button v-if="row.failedCount" link type="primary" @click="inspect(row)">查看错误行</el-button></template></el-table-column></el-table>
  <template v-if="errors"><p class="small muted">任务 {{selected?.id}} · 修正后仅重新上传失败行</p><el-table :data="errors.items"><el-table-column prop="rowNumber" label="行号" width="70"/><el-table-column prop="field" label="字段" width="120"/><el-table-column prop="message" label="原因" min-width="220"/></el-table><el-pagination v-model:current-page="errorPage" :total="errors.total" :page-size="20" layout="prev,pager,next" @current-change="readErrors"/></template>
 </el-dialog>
</template>
