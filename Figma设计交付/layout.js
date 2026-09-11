// 浏览器预览与 Figma 共用宽度分配，减少手机端溢出与双端布局偏差。
const palette={bg:'FBF8F3',surface:'FFFFFF',sand:'F2ECE2',ink:'2B2724',muted:'746B63',brand:'B9512B',brandHover:'923C1F',peach:'F7E4D3',line:'E4DBD0',green:'456759',sage:'E9F0E8',white:'FFFFFF'};
function padding(n){return {x:n.padX??n.pad??0,y:n.padY??n.pad??0};}
function widths(n,w){
 const kids=n.children||[],inner=w-2*padding(n).x;
 if(n.dir!=='HORIZONTAL')return kids.map(c=>Math.min(c.w||inner,inner));
 const available=inner-Math.max(0,kids.length-1)*(n.gap??16);
 let fixed=kids.reduce((a,c)=>a+(c.w||0),0),flex=kids.filter(c=>!c.w).length;
 const reserve=flex?Math.max(100,available-fixed)/flex:0;
 const requested=kids.map(c=>c.w||reserve),sum=requested.reduce((a,b)=>a+b,0);
 return requested.map(v=>Math.max(1,v*Math.min(1,available/sum)));
}
function cardModel(n){const p=n.pet,small=n.w<200;return {type:'box',gap:0,bg:'surface',radius:20,target:n.target,children:[{type:'image',image:p.img,w:n.w,h:n.imageH,radius:20},{type:'box',pad:small?12:20,gap:8,children:[{type:'text',text:p.name+' · '+p.breed,size:small?15:20,bold:true,color:'ink'},{type:'text',text:p.meta,size:small?11:13,color:'muted'},{type:'text',text:p.price,size:small?22:26,bold:true,color:'brand'},{type:'text',text:'在售 · 到店自提',size:11,color:'green'}]}]};}
if(typeof module!=='undefined')module.exports={palette,padding,widths,cardModel};
