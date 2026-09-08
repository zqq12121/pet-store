<script setup lang="ts">
import {ref,watch} from 'vue'
import QRCode from 'qrcode'
const props=defineProps<{value:string;label:string}>(),src=ref('')
// 二维码只编码后端凭证或站内续付链接，原文始终保留便于手动操作。
watch(()=>props.value,async value=>{src.value='';if(!value)return;try{const image=await QRCode.toDataURL(value,{width:220,margin:2,errorCorrectionLevel:'M'});if(value===props.value)src.value=image}catch{/* 数据无法编码时仍可使用文字码或链接。 */}},{immediate:true})
</script>
<template><img v-if="src" :src="src" :alt="label" width="220" height="220" style="margin:12px auto;max-width:100%"></template>
