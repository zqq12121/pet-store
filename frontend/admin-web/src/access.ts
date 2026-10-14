import {computed,ref} from 'vue'
import {admin} from '../../shared/api'

export interface ManagementAccount {username:string;role:'admin'|'staff';displayName:string|null;passwordChangeAvailableAt:string|null}
export const managementAccount=ref<ManagementAccount>()
export const isStaff=computed(()=>managementAccount.value?.role==='staff')

// 每次路由切换读取服务端身份，刷新页面和停用账号不会依赖陈旧的本地角色。
export async function refreshManagementAccount(){managementAccount.value=await admin<ManagementAccount>('GET','/admin/auth/security')}
