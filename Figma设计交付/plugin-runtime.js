// 此脚本只在用户批准并手动导入 Figma 后运行。无网络请求，无业务接口调用。
// DATA 与共用布局函数由 build-plugin.js 内嵌；不会读取磁盘、剪贴板或其他文件。
figma.showUI(`<style>body{font:13px system-ui;padding:12px;color:#2B2724;background:#FBF8F3}button{padding:10px 14px;margin:4px;border:0;border-radius:8px;background:#B9512B;color:white}pre{white-space:pre-wrap;font-size:11px;max-height:180px;overflow:auto}img{max-width:100%;max-height:520px;object-fit:contain}</style><b>暖爪 · 页面与交互设计</b><p>只修改指定设计文件；网络访问禁用。</p><div id="buttons"></div><pre id="status">已生成页面会保留。可以检查原型与实际排版。</pre><img id="shot"><script>const modes={sample:'生成三端首页',all:'生成全部页面',audit:'检查全部页面','preview:d-home':'电脑首页','preview:m-home':'手机首页','preview:a-home':'后台工作台',close:'关闭面板'};for(const [id,label]of Object.entries(modes)){const b=document.createElement('button');b.textContent=label;b.onclick=()=>parent.postMessage({pluginMessage:{type:id}},'*');document.getElementById('buttons').appendChild(b)}onmessage=e=>{const m=e.data.pluginMessage;if(!m)return;if(m.text)document.getElementById('status').textContent=m.text;if(m.image)document.getElementById('shot').src='data:image/png;base64,'+m.image}</script>`,{width:860,height:760});
const screenNodes={},links=[],variables={},masters={},report={created:[],skipped:[],errors:[]};
let busy=false;
const progress=text=>figma.ui.postMessage({text});
function color(key){const h=palette[key]||palette.ink;return {r:parseInt(h.slice(0,2),16)/255,g:parseInt(h.slice(2,4),16)/255,b:parseInt(h.slice(4,6),16)/255};}
function paint(key){let p={type:'SOLID',color:color(key)};if(variables[key])p=figma.variables.setBoundVariableForPaint(p,'color',variables[key]);return [p];}
function track(n){report.created.push(n.id);return n;}
function auto(parent,w,n={}){
 const f=track(figma.createFrame());parent.appendChild(f);f.name=n.key||n.type||'Stack';
 f.layoutMode=n.dir||'VERTICAL';f.resize(Math.max(w,1),Math.max(n.h||1,1));
 f.primaryAxisSizingMode=n.dir==='HORIZONTAL'?'FIXED':n.h?'FIXED':'AUTO';f.counterAxisSizingMode=n.dir==='HORIZONTAL'?(n.h?'FIXED':'AUTO'):'FIXED';
 f.paddingLeft=f.paddingRight=padding(n).x;f.paddingTop=f.paddingBottom=padding(n).y;f.itemSpacing=n.gap??16;
 f.fills=n.bg?paint(n.bg):[];f.cornerRadius=n.radius||0;f.clipsContent=false;
 if(n.stroke){f.strokes=paint(n.stroke);f.strokeWeight=1;f.strokeAlign='INSIDE';}
 if(n.center)f.counterAxisAlignItems='CENTER';
 return f;
}
function textNode(parent,n,w){
 const node=track(figma.createText());parent.appendChild(node);node.name=n.key||n.text.slice(0,32);
 node.fontName={family:n.serif?'Noto Serif SC':'Noto Sans SC',style:n.serif?'Medium':n.bold?'Bold':'Regular'};
 node.fontSize=n.size||16;node.lineHeight={unit:'PERCENT',value:150};node.letterSpacing={unit:'PIXELS',value:n.tracking||0};
 node.characters=n.text;node.fills=paint(n.color||'ink');node.resize(Math.max(w,1),1);node.textAutoResize='HEIGHT';return node;
}
function action(destinationId,type='DISSOLVE',duration=.3){return {type:'NODE',destinationId,navigation:'NAVIGATE',transition:{type,easing:{type:'EASE_OUT'},duration},resetScrollPosition:true};}
async function buttonMasters(page){
 for(const kind of ['primary','secondary','ghost','disabled']){
  const name='暖爪组件 / Button / '+kind;
  const old=page.children.find(n=>n.name===name&&n.type==='COMPONENT_SET');
  if(old){masters[kind]={node:old.children[0],prop:Object.keys(old.componentPropertyDefinitions).find(k=>k.startsWith('Label'))};continue;}
  const variants=[];
  for(const state of ['Default','Hover']){
   const c=track(figma.createComponent());page.appendChild(c);c.name='State='+state;
   c.layoutMode='HORIZONTAL';c.resize(180,48);c.primaryAxisSizingMode='FIXED';c.counterAxisSizingMode='FIXED';c.primaryAxisAlignItems='CENTER';c.counterAxisAlignItems='CENTER';c.cornerRadius=12;c.paddingLeft=c.paddingRight=12;
   c.fills=kind==='primary'?paint(state==='Hover'?'brandHover':'brand'):kind==='disabled'?paint('line'):kind==='ghost'&&state==='Default'?[]:paint(state==='Hover'?'peach':'surface');
   if(kind==='secondary'){c.strokes=paint('line');c.strokeWeight=1;}
   const label=textNode(c,{text:'按钮',size:15,color:kind==='primary'?'white':kind==='disabled'?'muted':'brand'},156);label.textAlignHorizontal='CENTER';label.layoutSizingHorizontal='FILL';
   variants.push(c);
  }
  const set=track(figma.combineAsVariants(variants,page));set.name=name;set.x=5400;set.y=-1800+Object.keys(masters).length*200;
  set.layoutMode='HORIZONTAL';set.itemSpacing=24;set.paddingLeft=set.paddingRight=set.paddingTop=set.paddingBottom=16;set.primaryAxisSizingMode='AUTO';set.counterAxisSizingMode='AUTO';
  const prop=set.addComponentProperty('Label','TEXT','按钮');for(const c of variants)c.children[0].componentPropertyReferences={characters:prop};
  if(kind!=='disabled')await variants[0].setReactionsAsync([{trigger:{type:'ON_HOVER'},actions:[{...action(variants[1].id,'SMART_ANIMATE',.18),navigation:'CHANGE_TO'}]}]);
  masters[kind]={node:variants[0],prop};
 }
}
async function render(parent,n,w){
 let node;
 if(n.type==='text')node=textNode(parent,n,w);
 else if(n.type==='image'){
  node=track(figma.createRectangle());parent.appendChild(node);node.resize(w,n.h);node.cornerRadius=n.radius||0;
  node.fills=[{type:'IMAGE',imageHash:DATA.state.images[n.image],scaleMode:'FILL'}];
 }else if(n.type==='button'){
  const master=masters[n.disabled?'disabled':n.ghost?'ghost':n.secondary?'secondary':'primary'];
  node=track(master.node.createInstance());parent.appendChild(node);node.setProperties({[master.prop]:n.text});node.resize(w,n.h||48);
 }else if(n.type==='petcard'){
  // 相同宠物卡片复用组件；手机与电脑的字号及图片比例保持独立。
  const key='card-'+n.pet.img+'-'+Math.round(w);
  if(!masters[key]){
   const p=await figma.getNodeByIdAsync(DATA.state.pages.desktop);
   const name='暖爪组件 / '+key;let component=p.children.find(c=>c.type==='COMPONENT'&&c.name===name);
   if(!component){const model=cardModel({...n,w});delete model.target;const frame=await render(p,model,w);component=figma.createComponentFromNode(frame);component.name=name;component.x=6500+(Object.keys(masters).length%4)*370;component.y=-1800+Math.floor(Object.keys(masters).length/4)*600;report.created.push(component.id);}
   masters[key]=component;
  }
  node=track(masters[key].createInstance());parent.appendChild(node);
 }else{
  node=auto(parent,w,n);const ws=widths(n,w);for(let i=0;i<(n.children||[]).length;i++)await render(node,n.children[i],ws[i]);
 }
 node.name=n.key||node.name;if(n.target&&!n.disabled)links.push({node,target:n.target});return node;
}
async function build(mode){
 // 先确认目标文件与既有资源，防止在其他打开的文件里意外生成。
 if(figma.fileKey&&figma.fileKey!=='2P7oqPkLhdwdkpbGnyFNWZ')throw Error('请先打开指定的暖爪 Figma 文件。');
 const pages={};for(const [device,id]of Object.entries(DATA.state.pages)){const page=await figma.getNodeByIdAsync(id);if(!page||page.type!=='PAGE')throw Error('缺少目标页面：'+device);pages[device]=page;}
 for(const font of [{family:'Noto Sans SC',style:'Regular'},{family:'Noto Sans SC',style:'Bold'},{family:'Noto Serif SC',style:'Medium'}])await figma.loadFontAsync(font);
 for(const [key,id]of Object.entries(DATA.state.tokens)){const v=await figma.variables.getVariableByIdAsync(id);if(v)variables[key]=v;}
 for(const [key,hash]of Object.entries(DATA.state.images))if(!figma.getImageByHash(hash))throw Error('缺少已上传照片：'+key);
 await figma.setCurrentPageAsync(pages.desktop);await buttonMasters(pages.desktop);
 const selection=mode==='sample'?DATA.pages.filter(s=>['d-home','m-home','a-home'].includes(s.id)):DATA.pages;
 for(const s of selection){
  await figma.setCurrentPageAsync(pages[s.device]);const page=pages[s.device],name='暖爪交付 / '+s.id+' / '+s.name;
  const old=page.children.find(n=>n.name===name);
  if(old){screenNodes[s.id]=old;report.skipped.push(s.id);continue;}
  progress('正在生成 '+s.name+'\n'+(Object.keys(screenNodes).length+1)+' / '+selection.length);
  const root=auto(page,s.w,{gap:0,bg:'bg',h:s.h});root.name='暖爪生成中 / '+s.id;
  // 放置在现有设计区右侧，避免覆盖基础组件与用户已有内容。
  root.x=s.x+10000;root.y=s.y;root.clipsContent=true;root.overflowDirection='VERTICAL';
  try{for(const child of s.children)await render(root,child,s.w);root.name=name;screenNodes[s.id]=root;}
  catch(error){root.remove();throw Error(s.id+': '+error.message);}
 }
 // 查找已生成的首页/状态，样例模式后再次运行也能补全连接。
 for(const page of Object.values(pages)){await page.loadAsync();for(const child of page.children){if(child.name.startsWith('暖爪交付 / ')){const id=child.name.split(' / ')[1];screenNodes[id]=child;}}}
 const specs=new Map();function index(n){if(n.key)specs.set(n.key,n);(n.children||[]).forEach(index);}DATA.pages.forEach(index);
 let wired=0;for(const [id,root]of Object.entries(screenNodes)){
  const s=DATA.pages.find(p=>p.id===id);if(!s)continue;
  for(const node of root.findAll(n=>specs.has(n.name))){const spec=specs.get(node.name);if(spec.target&&screenNodes[spec.target]){
   const to=spec.target,transition=to.includes('filter')?{type:'MOVE_IN',direction:'LEFT',matchLayers:false,easing:{type:'EASE_OUT'},duration:.28}:{type:'DISSOLVE',easing:{type:'EASE_OUT'},duration:.3};
   await node.setReactionsAsync([{trigger:{type:'ON_CLICK'},actions:[{...action(screenNodes[to].id),transition}]}]);wired++;
  }}
  if(s.carousel&&screenNodes[s.carousel])await root.setReactionsAsync([{trigger:{type:'AFTER_TIMEOUT',timeout:5},actions:[action(screenNodes[s.carousel].id,'DISSOLVE',.45)]}]);
 }
 for(const [device,page]of Object.entries(pages)){
  const id={desktop:'d-home',mobile:'m-home',admin:'a-home'}[device];
  if(screenNodes[id])page.flowStartingPoints=[...page.flowStartingPoints.filter(f=>f.nodeId!==screenNodes[id].id),{nodeId:screenNodes[id].id,name:{desktop:'电脑买家端',mobile:'手机买家端',admin:'店主管理后台'}[device]}];
 }
 await figma.setCurrentPageAsync(pages.desktop);figma.currentPage.selection=[screenNodes['d-home']];figma.viewport.scrollAndZoomIntoView([screenNodes['d-home']]);
 progress(JSON.stringify({status:'生成完成，仍需在 Figma 中检查排版与原型',screens:Object.keys(screenNodes).length,wired,report},null,2));
}
// 验证直接读取当前 Figma 节点；截图通过原生 exportAsync 生成。
async function inspect(mode){
 const roots=[];for(const id of Object.values(DATA.state.pages)){const page=await figma.getNodeByIdAsync(id);await page.loadAsync();for(const child of page.children)if(child.name.startsWith('暖爪交付 / '))roots.push(child);}
 if(mode.startsWith('preview:')){
  const key=mode.split(':')[1],root=roots.find(n=>n.name.split(' / ')[1]===key);if(!root)throw Error('找不到页面');
  await figma.setCurrentPageAsync(root.parent);figma.currentPage.selection=[root];figma.viewport.scrollAndZoomIntoView([root]);
  const bytes=await root.exportAsync({format:'PNG',constraint:{type:'SCALE',value:key==='m-home'?1:.6}});
  figma.ui.postMessage({text:root.name+'\n节点 '+root.id+' / '+root.width+' × '+root.height,image:figma.base64Encode(bytes)});return;
 }
 const overflow=[],flows=[],ids={};let reactions=0,carousels=0;
 for(const root of roots){
  ids[root.name.split(' / ')[1]]=root.id;
  if(root.reactions.some(r=>r.trigger?.type==='AFTER_TIMEOUT'))carousels++;
  for(const n of root.findAll(n=>'reactions' in n)){reactions+=n.reactions.filter(r=>r.trigger?.type==='ON_CLICK').length;if(n.parent!==root&&n.parent&&n.x+n.width>n.parent.width+1&&n.layoutPositioning!=='ABSOLUTE')overflow.push(root.name.split(' / ')[1]+': '+n.name);}
 }
 for(const id of Object.values(DATA.state.pages)){const p=await figma.getNodeByIdAsync(id);flows.push(...p.flowStartingPoints);}
 progress(JSON.stringify({screens:roots.length,clickReactions:reactions,timedCarousels:carousels,horizontalOverflow:overflow,flows,ids},null,2));
}
figma.ui.onmessage=async msg=>{if(busy)return;busy=true;try{if(msg.type==='close'){figma.closePlugin();return;}if(msg.type==='audit'||msg.type.startsWith('preview:'))await inspect(msg.type);else await build(msg.type);}catch(error){report.errors.push(error.message);progress('操作失败：'+error.message+'\n已完成的页面保留。');}finally{busy=false;}};
