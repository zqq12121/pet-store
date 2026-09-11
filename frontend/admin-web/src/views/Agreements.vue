<script setup lang="ts">
import {onMounted,reactive,ref} from 'vue'
import {admin,mutationKey,date} from '../../../shared/api'
import {useLoad} from '../../../shared/useLoad'
type Agreement={id:string;type:string;version:string;title:string;content:string;status:string;publishedAt:string|null;healthGuaranteeDays:number|null}
const {loading,error,run}=useLoad(),items=ref<Agreement[]>([]),total=ref(0),page=ref(1),selected=ref<Agreement>(),reviewed=ref(false),key=mutationKey()
const form=reactive({type:'live_pet_trade',version:'',title:'',content:'',healthGuaranteeDays:7})
const labels:Record<string,string>={live_pet_trade:'活体交易协议',pickup_confirmation:'现场确认协议',draft:'草稿',published:'当前生效',retired:'历史版本'}
async function load(){const data=await admin<{items:Agreement[];total:number}>('GET','/admin/agreements',undefined,{page:page.value,pageSize:20});items.value=data.items;total.value=data.total}
async function create(){await run(async()=>{
  // 现场确认协议禁止携带健康保障天数，保持与后端契约一致。
  const {healthGuaranteeDays,...common}=form,body={...common,...(form.type==='live_pet_trade'?{healthGuaranteeDays}:{})}
  const result=await admin<Agreement>('POST','/admin/agreements',body,undefined,key('/admin/agreements',body));selected.value=result;reviewed.value=false
  form.version='';form.title='';form.content='';await load()
})}
function inspect(item:Agreement){selected.value=item;reviewed.value=false}
async function publish(){if(!selected.value||!reviewed.value)return;await run(async()=>{
  // 发布后正文不可修改；新版本替换当前版本，历史订单保留原签署快照。
  const path=`/admin/agreements/${selected.value!.id}/publish`,body={reviewConfirmed:true}
  selected.value=await admin<Agreement>('POST',path,body,undefined,key(path,body));reviewed.value=false;await load()
})}
onMounted(()=>run(load))
</script>
<template>
  <div class="panel" style="padding:24px">
    <h2>协议版本</h2><p>先创建草稿并核对正文，再发布。发布后正文不可修改，历史订单保留签署时的版本。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false"/>
    <el-table :data="items" v-loading="loading"><el-table-column label="类型"><template #default="{row}">{{labels[row.type]}}</template></el-table-column><el-table-column prop="version" label="版本"/><el-table-column prop="title" label="标题"/><el-table-column label="状态"><template #default="{row}">{{labels[row.status]}}</template></el-table-column><el-table-column label="发布时间"><template #default="{row}">{{date(row.publishedAt)}}</template></el-table-column><el-table-column label="操作"><template #default="{row}"><el-button @click="inspect(row)">查看</el-button></template></el-table-column></el-table>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next" @current-change="run(load)"/>
    <h2>创建新版本</h2>
    <!-- Element Plus 表单直接拦截提交，避免嵌套 form 导致浏览器刷新。 -->
    <el-form label-position="top" @submit.prevent="create">
      <el-form-item label="协议类型"><el-select v-model="form.type"><el-option label="活体交易协议" value="live_pet_trade"/><el-option label="现场确认协议" value="pickup_confirmation"/></el-select></el-form-item>
      <el-form-item label="版本号"><el-input v-model="form.version" required maxlength="32" placeholder="例如 v1.0"/></el-form-item>
      <el-form-item label="标题"><el-input v-model="form.title" required maxlength="100"/></el-form-item>
      <el-form-item v-if="form.type==='live_pet_trade'" label="健康保障天数"><el-input-number v-model="form.healthGuaranteeDays" :min="1" :max="30"/></el-form-item>
      <el-form-item label="正文（Markdown）"><el-input v-model="form.content" type="textarea" :rows="8" required maxlength="50000"/></el-form-item>
      <el-button type="primary" native-type="submit" :loading="loading">创建草稿</el-button>
    </el-form>
    <el-dialog :model-value="!!selected" title="查看协议" width="min(800px, 90vw)" @close="selected=undefined">
      <template v-if="selected"><h3>{{selected.title}} · {{selected.version}}</h3><p>{{labels[selected.type]}} · {{labels[selected.status]}}</p><p v-if="selected.healthGuaranteeDays">健康保障 {{selected.healthGuaranteeDays}} 天</p>
        <!-- 使用纯文本显示正文，避免管理员录入 HTML 后在页面执行。 -->
        <pre style="white-space:pre-wrap;overflow-wrap:anywhere">{{selected.content}}</pre>
        <template v-if="selected.status==='draft'"><el-checkbox v-model="reviewed">我已审核正文及保障规则，确认发布并替换同类型当前版本</el-checkbox><br><el-button type="primary" :disabled="!reviewed" :loading="loading" @click="publish">发布协议</el-button></template>
      </template>
    </el-dialog>
  </div>
</template>
