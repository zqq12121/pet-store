<script setup lang="ts">
// 两种登录方式共用文案；每次进入页面都由父页面保持默认未勾选。
const accepted=defineModel<boolean>({required:true})
defineProps<{disabled:boolean}>()
</script>
<template>
 <label class="check login-consent"><input v-model="accepted" type="checkbox" :disabled="disabled" aria-label="同意用户服务协议和隐私政策"><span>我已阅读并同意<RouterLink to="/terms" target="_blank" rel="noopener" @click.stop>《用户服务协议》</RouterLink>和<RouterLink to="/privacy" target="_blank" rel="noopener" @click.stop>《隐私政策》</RouterLink></span></label>
</template>
<style scoped>
.login-consent{align-items:flex-start}
.login-consent input{margin:3px 0 0}
.login-consent span{min-width:0}
.login-consent a{color:var(--brand);text-decoration:underline;text-underline-offset:3px}
</style>
