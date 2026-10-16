import {afterEach,expect,it,vi} from 'vitest'
import {mutationKey,randomId} from './api'
const getRandomValues=globalThis.crypto.getRandomValues.bind(globalThis.crypto)
afterEach(()=>vi.unstubAllGlobals())

it('HTTP 公网 IP 缺少 randomUUID 时仍生成合法的不同 UUID',()=>{
 vi.stubGlobal('crypto',{getRandomValues})
 const first=randomId(),next=randomId()
 expect(first).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
 expect(next).not.toBe(first)
})

it('无原生 randomUUID 时重试复用幂等键，内容改变才生成新键',()=>{
 vi.stubGlobal('crypto',{getRandomValues})
 const key=mutationKey(),first=key('/orders',{petId:'one'})
 expect(key('/orders',{petId:'one'})).toBe(first)
 expect(key('/orders',{petId:'two'})).not.toBe(first)
})
