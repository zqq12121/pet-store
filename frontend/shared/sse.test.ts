import {describe,it,expect} from 'vitest'
import {readSse} from './sse'
// 模拟真实网络将汉字、CRLF 和 JSON 拆到多个数据块的情况。
const stream=(bytes:Uint8Array)=>new ReadableStream<Uint8Array>({start(controller){for(const byte of bytes)controller.enqueue(new Uint8Array([byte]));controller.close()}})
describe('SSE streaming decoder',()=>{
 it('preserves split UTF-8, CRLF and multiline JSON',async()=>{
  const events:unknown[]=[]
  await readSse(stream(new TextEncoder().encode(': heartbeat\r\nevent: delta\r\ndata: {"text":\r\ndata: "你好，猫咪"}\r\n\r\nevent: done\r\ndata: {}\r\n\r\n')),(event,data)=>events.push({event,data}))
  expect(events).toEqual([{event:'delta',data:{text:'你好，猫咪'}},{event:'done',data:{}}])
 })
 it('rejects malformed payload so the chat can recover by message id',async()=>{
  await expect(readSse(stream(new TextEncoder().encode('event: delta\ndata: invalid\n\n')),()=>{})).rejects.toThrow()
 })
})
