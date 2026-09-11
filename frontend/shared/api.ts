import axios from 'axios'
export const isDemo = import.meta.env.VITE_DEMO_MODE === 'true' || (import.meta.env.DEV && import.meta.env.VITE_DEMO_MODE !== 'false')
// 区分 Node 固定验证码演示与 Java 随机验证码联调。
export const isJavaLocal = isDemo && import.meta.env.VITE_JAVA_LOCAL === 'true'
export type Role = 'buyer' | 'admin'
export class ApiError extends Error { constructor(message: string, public code = 'NETWORK_ERROR', public status = 0) { super(message) } }
const storageKey = (role: Role) => `warmpaw:${role}:auth`
export function auth(role: Role): {accessToken:string; expiresAt:number; user?:{nickname:string; phoneMasked:string}} | null {
  try { const value=JSON.parse(sessionStorage.getItem(storageKey(role)) || 'null'); if(value?.expiresAt > Date.now()) return value } catch { /* 损坏或过期会话按未登录处理。 */ }
  sessionStorage.removeItem(storageKey(role)); return null
}
export function saveAuth(role:Role, data:{accessToken:string; expiresIn:number; user?:unknown}) { sessionStorage.setItem(storageKey(role), JSON.stringify({...data,expiresAt:Date.now()+data.expiresIn*1000})) }
export function clearAuth(role:Role) { sessionStorage.removeItem(storageKey(role)) }
export function headers(role:Role):Record<string,string> { const value=auth(role); return value?{Authorization:`Bearer ${value.accessToken}`} : {} }
export function client(role:Role) {
  const http=axios.create({baseURL:'/api/v1',timeout:15000})
  return async function request<T>(method:string,path:string,data?:unknown,params?:Record<string,unknown>,key?:string):Promise<T> {
    try {
      const result=await http.request({method,url:path,data,params,headers:{...headers(role),...(key?{'Idempotency-Key':key}:{})}})
      if(result.data.code!=='OK')throw new ApiError(result.data.message,result.data.code,result.status)
      return result.data.data as T
    } catch(error) {
      if(error instanceof ApiError)throw error
      if(axios.isAxiosError(error)) {
        if(error.response?.status===401){clearAuth(role);window.dispatchEvent(new CustomEvent('auth:expired',{detail:role}))}
        throw new ApiError(error.response?.data?.message || '连接暂时中断，请重试。你的表单已保留。',error.response?.data?.code,error.response?.status)
      }
      throw error
    }
  }
}
export const buyer=client('buyer'), admin=client('admin')
// 同一逻辑提交在超时后保留原幂等键；内容变化才生成新键。
export function mutationKey() { let fingerprint='',key=''; return (path:string,body:unknown) => {const next=path+JSON.stringify(body);if(next!==fingerprint){fingerprint=next;key=crypto.randomUUID()}return key} }
export const money=(amount:number) => new Intl.NumberFormat('zh-CN',{style:'currency',currency:'CNY',maximumFractionDigits:2}).format(amount/100)
export const date=(value:string|null|undefined) => value?new Date(value).toLocaleString('zh-CN',{hour12:false,month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'}):'—'
export function safeUrl(value:string) { try { const url=new URL(value,location.origin);return ['http:','https:'].includes(url.protocol)?url.href:'#' } catch { return '#' } }
export async function upload(role:Role,file:File,purpose:string,orderId?:string) {
  const limit=purpose==='pet_video'?100:10
  if(file.size>limit*1024*1024)throw new Error(`文件不能超过 ${limit}MB`)
  const body=new FormData();body.append('file',file);body.append('purpose',purpose);if(orderId)body.append('orderId',orderId)
  return client(role)<{id:string;publicUrl:string|null}>('POST',role==='admin'?'/admin/files':'/files',body)
}
