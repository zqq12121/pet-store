<script setup lang="ts">defineProps<{loading?:boolean;error?:string}>();defineEmits<{retry:[]}>()</script>
<template><div v-if="loading" class="loading" role="status"><span class="spinner"></span>正在加载，请稍候…</div><div v-else-if="error" class="error-panel" role="alert"><p>{{error}}</p><button class="btn secondary" @click="$emit('retry')">重新加载</button></div></template>
