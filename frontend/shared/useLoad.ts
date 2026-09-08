import { ref } from 'vue'
// 请求失败保留已有内容，并显式提供加载和错误状态。
export function useLoad() {
 const loading=ref(false),error=ref('')
 async function run(task:()=>Promise<void>) {loading.value=true;error.value='';try{await task()}catch(e){error.value=e instanceof Error?e.message:'操作失败，请重试'}finally{loading.value=false}}
 return {loading,error,run}
}
