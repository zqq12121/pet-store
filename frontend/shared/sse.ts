// SSE 按空行分帧，保留跨网络分块的 UTF-8 字符和未完成事件。
export async function readSse(stream:ReadableStream<Uint8Array>,onEvent:(event:string,data:Record<string,unknown>)=>void) {
  const reader=stream.getReader(), decoder=new TextDecoder();let pending=''
  function drain(final=false) {
    pending=pending.replace(/\r\n/g,'\n'); let boundary:number
    while((boundary=pending.indexOf('\n\n'))>=0){const block=pending.slice(0,boundary);pending=pending.slice(boundary+2);emit(block)}
    if(final&&pending.trim()){emit(pending);pending=''}
  }
  function emit(block:string){let event='message';const data:string[]=[];for(const line of block.split('\n')){if(line.startsWith('event:'))event=line.slice(6).trim();if(line.startsWith('data:'))data.push(line.slice(5).trimStart())}if(data.length)onEvent(event,JSON.parse(data.join('\n')))}
  try {for(;;){const {value,done}=await reader.read();if(done){pending+=decoder.decode();drain(true);break}pending+=decoder.decode(value,{stream:true});drain()}} finally {reader.releaseLock()}
}
