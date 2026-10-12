<script setup lang="ts">
import {ref,onBeforeUnmount} from 'vue'
import {upload} from '../../../shared/api'
import type {CommunityImage} from '../../../shared/communityTypes'
const props=defineProps<{modelValue:CommunityImage[]}>(),emit=defineEmits<{ 'update:modelValue':[CommunityImage[]];busy:[boolean] }>()
const uploading=ref(false),error=ref(''),previews=new Set<string>()
// 草稿图片不公开，上传后用本地预览；页面卸载时释放浏览器对象 URL。
onBeforeUnmount(()=>previews.forEach(url=>URL.revokeObjectURL(url)))
async function add(event:Event){
 const input=event.target as HTMLInputElement,files=Array.from(input.files||[]);error.value=''
 if(props.modelValue.length+files.length>9){error.value='最多上传9张图片';input.value='';return}
 uploading.value=true;emit('busy',true)
 try{for(const file of files){
  if(!['image/jpeg','image/png','image/webp'].includes(file.type))throw new Error('仅支持 JPG、PNG、WebP 图片')
  const asset=await upload('buyer',file,'community_image'),url=URL.createObjectURL(file);previews.add(url)
  emit('update:modelValue',[...props.modelValue,{id:asset.id,url}])
 }}catch(e){error.value=e instanceof Error?e.message:'图片上传失败'}finally{uploading.value=false;emit('busy',false);input.value=''}
}
function remove(image:CommunityImage){emit('update:modelValue',props.modelValue.filter(i=>i.id!==image.id));if(previews.delete(image.url))URL.revokeObjectURL(image.url)}
</script>
<template><div class="upload-box stack"><label>图片（可选，最多9张，每张5 MB）<input type="file" accept="image/jpeg,image/png,image/webp" multiple :disabled="uploading||modelValue.length>=9" @change="add"></label><p v-if="uploading" role="status">正在上传图片…</p><p v-if="error" class="error-text" role="alert">{{error}}</p><div class="community-upload-images"><figure v-for="image in modelValue" :key="image.id"><img :src="image.url" alt="待发布图片预览"><button type="button" class="text-link" :disabled="uploading" @click="remove(image)">移除</button></figure></div></div></template>
<style scoped>.community-upload-images{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}figure{margin:0}img{width:100%;aspect-ratio:1;object-fit:cover;border-radius:12px}</style>
