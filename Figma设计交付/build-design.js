// 页面数据是设计稿的唯一内容来源；本文件在本地生成 Figma 插件，不读取业务数据。
const fs = require('fs');
const path = require('path');
const dir = __dirname;
const state = JSON.parse(fs.readFileSync(path.join(dir, 'state.json'), 'utf8'));
const pages = [];
let sequence = 0;
const t = (text, size=16, color='ink', extra={}) => ({type:'text',text,size,color,...extra});
const box = (children,extra={}) => ({type:'box',children,gap:16,...extra});
const row = (children,extra={}) => box(children,{dir:'HORIZONTAL',...extra});
const photo = (image,w,h,extra={}) => ({type:'image',image,w,h,radius:24,...extra});
const btn = (text,target,extra={}) => ({type:'button',text,target,w:180,h:48,...extra});
const field = (label,value,hint='') => box([t(label,13,'muted'),box([t(value,16)],{h:48,pad:12,bg:'surface',stroke:'line',radius:12}),t(hint,11,'muted')],{gap:6});
const badge = (text,color='green',bg='sage') => box([t(text,12,color)],{dir:'HORIZONTAL',bg,pad:8,radius:8,w:160});
const pets = [
 {name:'奶糖',breed:'柯基',meta:'弟弟 · 4个月 · 活泼亲人',price:'¥ 4,800',img:'corgi'},
 {name:'年糕',breed:'中华田园猫',meta:'妹妹 · 5个月 · 安静温柔',price:'¥ 1,800',img:'cat'},
 {name:'松饼',breed:'金毛',meta:'弟弟 · 3个月 · 友好热情',price:'¥ 3,600',img:'golden'},
 {name:'橘子',breed:'中华田园猫',meta:'妹妹 · 6个月 · 亲人爱玩',price:'¥ 1,500',img:'ginger'}
];
const petcard=(p,m=false,target='d-detail')=>({type:'petcard',pet:p,w:m?171:290,imageH:m?170:252,target:p.img==='corgi'?target:null});
const nav=(active='首页')=>row([
 t('暖爪  paw & home',25,'brand',{w:360,bold:true}),
 btn('首页','d-home',{w:84,ghost:true}),btn('发现宠物','d-list',{w:110,ghost:true}),
 btn('AI 养宠助手','d-ai',{w:130,ghost:true}),btn('到店指南','d-shop',{w:110,ghost:true}),
 btn('我的订单','d-orders',{w:130,secondary:true})
],{h:88,w:1440,padX:80,gap:16,center:true,bg:'bg'});
const mnav=(title,back='m-home')=>row([btn('‹',back,{w:44,ghost:true}),t(title,18,'ink',{w:250,bold:true}),t('⋯',18,'muted',{w:28})],{w:390,h:64,padX:16,gap:8,center:true,bg:'bg'});
const mtabs=()=>row([btn('首页','m-home',{w:82,ghost:true}),btn('找宠物','m-list',{w:82,ghost:true}),btn('AI 助手','m-ai',{w:82,ghost:true}),btn('我的','m-profile',{w:82,ghost:true})],{w:390,h:76,padX:16,gap:10,bg:'surface',center:true});
const title=(eyebrow,heading,sub='')=>box([t(eyebrow,12,'brand',{tracking:2}),t(heading,34,'ink',{bold:true}),...(sub?[t(sub,15,'muted')]:[])],{gap:12});
const shopcard=()=>box([t('暖爪 · 城市宠物生活馆',22,'ink',{bold:true}),t('到店看见真实的它，再开始一段陪伴。',14,'muted'),t('示例地址：城市中心 · 生活街区 18 号',15),t('每天 10:00 – 20:00  ·  仅到店自提',14,'muted'),row([btn('查看到店指南','d-shop',{secondary:true}),btn('联系店主','d-contact',{ghost:true})])],{pad:28,bg:'sand',radius:24});
function screen(id,name,device,children,extra={}){pages.push({id,name,device,type:'screen',w:device==='mobile'?390:1440,h:device==='mobile'?844:1000,children,...extra});}
function desktop(id,name,body){screen(id,name,'desktop',[nav(),box(body,{padX:80,padY:32,gap:32})]);}
function mobile(id,name,body,extra={}){screen(id,name,'mobile',[mnav(name,extra.back),box(body,{padX:20,padY:20,gap:20}),...(extra.noTabs?[]:[mtabs()])],extra);}
const health=()=>box([t('健康与成长档案',20,'ink',{bold:true}),row([badge('疫苗记录可查看'),badge('已完成定期驱虫')]),t('疫苗：已完成当前月龄阶段接种，详情以到店核验记录为准。\n驱虫：体内外驱虫记录随个体档案交付。',14,'muted'),btn('查看检疫证明','d-certificate',{secondary:true,w:230})],{pad:24,bg:'surface',radius:20});
const productSummary=()=>row([photo('corgi',96,112,{radius:12}),box([t('奶糖 · 柯基',18,'ink',{bold:true}),t('弟弟 / 4个月 / 唯一个体',13,'muted'),t('¥ 4,800',24,'brand',{bold:true})],{gap:8})],{gap:16,pad:16,bg:'surface',radius:20});

// 电脑买家端：电商导航、大幅实拍、清晰状态与到店信任信息。
screen('d-home','D01 · 首页','desktop',[
 nav(),
 row([
  box([t('A LITTLE PAW. A LOT OF LOVE.',12,'brand',{tracking:2}),t('一个小小的相遇，\n让日常多一点温柔。',51,'ink',{serif:true}),t('发现与你合拍的猫咪与狗狗。\n看见真实档案，在门店安心相遇。',17,'muted'),row([btn('去认识它们 →','d-list',{w:190}),btn('问问 AI 怎么养','d-ai',{w:190,secondary:true})],{gap:16}),t('一宠一档  /  检疫证明可查  /  到店当面核验',12,'muted')],{w:588,gap:28,padY:32}),
  box([photo('corgi',580,510,{radius:32}),row([t('HELLO, 我是奶糖',16,'ink',{bold:true,w:280}),btn('认识我 →','d-detail',{w:156,secondary:true})],{pad:16,center:true})],{w:580,bg:'peach',radius:32,gap:0})
 ],{padX:80,padY:32,gap:80,w:1440}),
 box([row([title('MEET YOUR COMPANION','每一只，都值得认真认识。'),btn('查看全部 →','d-list',{ghost:true})],{gap:120}),row(pets.map(p=>petcard(p)),{gap:24})],{padX:80,padY:48,gap:28}),
 box([shopcard()],{padX:80,padY:32}),
 row([t('暖爪 paw & home',22,'brand',{w:400}),t('以陪伴为开始，以责任为约定。',14,'muted',{w:470}),btn('AI 养宠助手 ↗','d-ai',{w:230})],{padX:80,padY:40,gap:20})
]);
desktop('d-list','D02 · 发现宠物',[
 title('FIND YOUR LITTLE FRIEND','寻找与你合拍的它','品种、性格、年龄慢慢挑；每只宠物都有独立档案。'),
 row([field('搜索宠物','品种、毛色、性格…'),btn('搜索','d-list-filtered',{w:110})],{gap:16,center:true}),
 row([btn('全部','d-list',{w:100}),btn('猫咪','d-list-filtered',{w:100,secondary:true}),btn('狗狗','d-list',{w:100,secondary:true}),btn('品种与价格筛选','d-filter',{w:196,secondary:true}),btn('最新上架 ↓','d-list-filtered',{w:170,ghost:true})],{gap:12}),
 t('找到 12 位小伙伴',14,'muted'),row(pets.map(p=>petcard(p)),{gap:24}),
 row([petcard(pets[1]),petcard(pets[3]),petcard(pets[0]),petcard(pets[2])],{gap:24})
]);
desktop('d-list-filtered','D03 · 猫咪筛选结果',[title('A PURRFECT MATCH','猫咪小伙伴','已筛选：猫咪 · 价格 ¥1,000–¥3,000'),row([btn('猫咪 ×','d-list',{secondary:true}),btn('¥1,000–¥3,000 ×','d-list',{secondary:true,w:220}),btn('清除筛选','d-list',{ghost:true})]),row([petcard(pets[1]),petcard(pets[3])],{gap:24})]);
desktop('d-detail','D04 · 宠物详情',[
 t('首页 / 发现宠物 / 奶糖',13,'muted'),
 row([box([photo('corgi',570,590,{radius:24}),row([photo('corgi',88,88,{radius:12}),box([t('▶',26,'brand'),t('查看视频',12)],{w:112,h:88,pad:16,bg:'sand',radius:12})])],{w:570,gap:16}),
 box([badge('● 在售 · 到店自提'),t('奶糖，想做你的\n快乐小尾巴。',38,'ink',{serif:true}),t('柯基  /  弟弟  /  4个月  /  3.2 kg',15,'muted'),t('¥ 4,800',36,'brand',{bold:true}),t('活泼亲人，喜欢探索，也喜欢依偎。\n期待一位有耐心、愿意陪它慢慢成长的家人。',16,'muted'),row([badge('活泼亲人'),badge('适合陪伴')]),health(),row([btn('立即购买','d-login',{w:220}),btn('问问 AI 怎么养','d-ai',{w:230,secondary:true})]),t('不支持物流配送 · 付款后请在约定期限内到店',12,'muted')],{w:570,gap:20})],{gap:64}),shopcard()
]);
desktop('d-login','D05 · 手机号登录',[row([photo('cat',560,560,{radius:32}),box([title('WELCOME HOME','你好，未来的家人。','登录后可下单、查看订单和自提凭证。'),field('手机号','138 0000 0000'),field('短信验证码','输入 6 位验证码','验证码仅用于本人身份核验'),btn('获取验证码','d-login-code',{secondary:true,w:180}),btn('登录并继续','d-checkout',{w:440}),t('登录即表示你已阅读隐私说明。',12,'muted')],{w:470,gap:24,pad:24})],{gap:96})]);
desktop('d-login-code','D06 · 验证码已发送',[title('VERIFICATION','验证码已发送','已发送至 138****0000，请勿向他人透露。'),field('短信验证码','123456','59 秒后可重新获取'),btn('登录并继续','d-checkout',{w:360})]);
desktop('d-checkout','D07 · 确认订单',[
 title('ONE STEP CLOSER','确认这一次相遇','1 确认订单   —   2 微信支付   —   3 到店自提'),
 row([box([productSummary(),shopcard(),field('联系人','张女士'),field('已验证手机号','138 0000 0000','到店凭本人手机号和自提码核验'),field('订单备注（选填）','计划周日下午到店')],{w:710,gap:24}),
 box([t('订单金额',22,'ink',{bold:true}),row([t('奶糖 · 柯基',16,'ink',{w:240}),t('¥ 4,800',20,'brand')]),t('数量 1  ·  无配送费用',13,'muted'),t('应付合计',16),t('¥ 4,800.00',36,'brand',{bold:true}),btn('阅读《活体宠物交易须知》','d-agreement',{secondary:true,w:340}),btn('☐ 我已阅读并同意交易须知','d-checkout-agreed',{ghost:true,w:340}),btn('请先阅读并同意协议',null,{disabled:true,w:340}),t('提交后为你保留30分钟，请及时完成支付。',12,'muted')],{w:410,pad:32,gap:20,bg:'surface',radius:24})],{gap:48})
]);
desktop('d-checkout-agreed','D08 · 协议已勾选',[title('READY TO MEET','订单已确认'),productSummary(),shopcard(),badge('✓ 已阅读并同意交易须知','brand','peach'),t('应付金额   ¥ 4,800.00',32,'brand',{bold:true}),btn('提交订单并支付','d-payment',{w:360})]);
desktop('d-payment','D09 · 电脑端支付引导',[row([box([title('CONTINUE ON YOUR PHONE','在手机上继续付款','请使用微信扫一扫，在手机端打开这笔订单。'),t('订单号：WP202609070001\n应付金额：¥ 4,800.00\n付款剩余时间：29:42',18),t('手机支付完成后，本页将显示自提信息。',14,'muted'),btn('查看支付完成示例','d-pickup',{w:250,secondary:true})],{w:600,gap:28}),box([t('手机继续付款',22,'ink',{bold:true}),box([t('二维码展示区域',18,'muted'),t('原型示意 · 非收款码',12,'muted')],{w:280,h:280,pad:48,bg:'sand',radius:20}),t('为同一订单打开手机H5支付页',13,'muted')],{w:410,pad:48,bg:'surface',radius:32})],{gap:80})]);
desktop('d-pickup','D10 · 支付成功与自提凭证',[title('SEE YOU SOON','支付成功，期待与你见面。','请在 9月10日 15:30 前到店自提。'),row([box([badge('● 待自提'),t('你的自提码',18),t('4826 1950',48,'brand',{bold:true}),t('仅向店主出示，请勿转发。',13,'muted'),t('订单 WP202609070001\n奶糖 · 柯基  /  实付 ¥ 4,800.00',16),btn('查看订单详情','d-orders',{w:280})],{w:570,pad:40,bg:'surface',radius:24}),box([shopcard(),t('到店后请先核验宠物健康与证明，\n完成本人确认后由店主办理交付。',15,'muted'),btn('申请售后','d-after-sale',{secondary:true,w:240})],{w:570,gap:24})],{gap:64})]);
desktop('d-orders','D11 · 我的订单',[title('MY ORDERS','每一次相遇，都有记录。'),row([btn('全部','d-orders',{w:100}),btn('待付款','d-orders',{secondary:true,w:110}),btn('待自提','d-pickup',{secondary:true,w:110}),btn('已完成','d-order-completed',{secondary:true,w:110}),btn('售后','d-after-progress',{secondary:true,w:100})]),box([row([t('WP202609070001',14,'muted',{w:840}),badge('● 待自提')]),productSummary(),row([btn('查看自提码','d-pickup',{w:190}),btn('订单详情','d-pickup',{secondary:true}),btn('申请售后','d-after-sale',{ghost:true})])],{pad:28,bg:'surface',radius:24})]);
desktop('d-order-completed','D12 · 已完成订单',[title('WELCOME TO THE FAMILY','奶糖已到家，陪伴刚刚开始。'),badge('✓ 已完成'),productSummary(),t('交付时间：2026-09-07 16:18\n买家已完成现场健康确认与验证码核验。\n健康保障截止：2026-09-14 16:18（设计示例）',16,'muted'),row([btn('申请健康售后','d-after-sale',{w:220}),btn('问问 AI 饲养建议','d-ai',{secondary:true,w:240})])]);
desktop('d-after-sale','D13 · 售后申请',[title('WE ARE HERE FOR YOU','让问题被认真对待。','请按实际情况提交，店主会查看材料并联系你。'),productSummary(),row([field('申请类型','健康问题申诉'),field('期望处理方式','全额退款')]),field('问题描述','请填写症状、发现时间及诊疗情况'),box([t('＋ 上传诊断报告',18,'brand'),t('正规动物诊疗机构报告 · PDF / JPG / PNG · 每份不超过10MB',12,'muted')],{pad:32,bg:'surface',stroke:'line',radius:16}),row([btn('提交申请','d-after-progress',{w:240}),btn('联系店主','d-contact',{secondary:true})])]);
desktop('d-after-progress','D14 · 售后进度',[title('AFTER-SALES','申请已提交，我们会认真处理。'),badge('等待店主审核','brand','peach'),t('售后编号 AS20260907001',14,'muted'),box([t('● 09月07日 18:40  已提交申请',20,'brand'),t('│ 诊断报告与问题说明已收到。',15,'muted'),t('○ 等待店主审核',20),t('│ 审核通过后将显示退款或到店办理指引。',15,'muted'),t('○ 处理结果',20)],{pad:32,bg:'surface',radius:24}),btn('联系店主','d-contact',{w:220,secondary:true})]);
desktop('d-ai','D15 · AI 养宠助手',[row([box([title('PAW COMPANION','懂一点它，\n就更靠近一点。'),t('猫狗饲养、健康护理、品种特点，\n随时聊聊你的疑问。',16,'muted'),photo('cat',340,260,{radius:24}),t('AI 生成，仅供参考。\n出现持续不适，请及时就医。',13,'muted')],{w:400,gap:28}),box([row([t('暖爪 AI 助手',22,'ink',{bold:true,w:420}),badge('● 在线')]),box([t('你好！我是你的养宠小助手。\n想了解哪位小伙伴，或聊聊日常照护？',16)],{pad:24,bg:'sand',radius:20}),row([btn('幼犬一天喂几次？','d-ai-answer',{secondary:true,w:230}),btn('怎么查看自提订单？','d-ai-answer',{secondary:true,w:230})]),box([t('奶糖 · 柯基 / 4个月',15,'brand'),t('正在围绕这只宠物交流',12,'muted')],{pad:16,bg:'peach',radius:12}),field('输入问题','我想了解奶糖刚到家该注意什么'),row([btn('发送 →','d-ai-answer',{w:140}),btn('联系店主','d-contact',{ghost:true,w:160})])],{w:740,pad:28,bg:'surface',radius:24,gap:24})],{gap:64})]);
desktop('d-ai-answer','D16 · AI 引用与兜底',[title('PAW COMPANION','给奶糖一个温柔的适应期。'),box([t('刚到家的前几天，先为它准备安静、固定的休息区。\n尽量维持原来的粮食与喂食节奏，再逐步调整。\n减少陌生人频繁接触，观察饮食、精神和排便情况。',18),t('参考来源  ① 幼犬到家适应指南  ② 门店饲养手册',13,'brand'),t('AI 生成，仅供参考。如有持续呕吐、腹泻或精神不振，建议及时就医。',14,'muted'),row([btn('有帮助','d-ai',{w:140,secondary:true}),btn('仍未解决，联系店主','d-contact',{w:260})])],{pad:32,bg:'surface',radius:24}),field('继续提问','还有哪些准备事项？'),btn('继续对话','d-ai',{w:220})]);
desktop('d-shop','D17 · 到店指南',[title('COME SAY HELLO','来店里，见一面。','我们相信，真实的相处比照片更能帮你作出决定。'),row([photo('golden',560,460,{radius:24}),box([shopcard(),t('到店流程\n01 查看档案与检疫证明\n02 当面观察健康与性格\n03 买家确认后完成交付',18)],{w:570,gap:28})],{gap:64})]);
desktop('d-contact','D18 · 联系店主',[title('TALK TO A REAL PERSON','把你的问题，交给店主。'),box([t('暖爪 · 城市宠物生活馆',24,'ink',{bold:true}),t('联系电话：400-000-0000（设计示例）\n微信号：warm-paw-demo（设计示例）\n营业时间：每天 10:00–20:00',18),btn('返回首页','d-home',{w:200})],{pad:40,bg:'surface',radius:24})]);
desktop('d-filter','D19 · 筛选面板',[title('FILTER','找到合适的小伙伴'),row([btn('猫咪','d-list-filtered',{w:130}),btn('狗狗','d-list',{secondary:true,w:130})]),field('品种','全部品种'),row([field('最低价格','¥ 1,000'),field('最高价格','¥ 3,000')]),row([field('性别','不限'),field('月龄','0–12个月')]),field('毛色','不限'),row([btn('重置','d-list',{secondary:true}),btn('查看筛选结果','d-list-filtered',{w:240})])]);
desktop('d-agreement','D20 · 交易须知',[title('PLEASE READ','活体宠物交易须知','以下为页面展示结构，正式条款由经营者确认后发布。'),box([t('01  到店自提与现场核验',22,'ink',{bold:true}),t('本店仅支持到店自提。交付前请查看宠物健康状况、检疫证明与档案，并完成本人确认。',16),t('02  健康保障与售后材料',22,'ink',{bold:true}),t('保障期限与责任边界以本次签署版本为准。健康问题请提交正规动物诊疗机构出具的诊断报告。',16),t('03  协议留存',22,'ink',{bold:true}),t('协议版本与确认时间将随订单保存，便于随时查阅。',16)],{pad:32,bg:'surface',radius:24,gap:24}),btn('已阅读，返回确认订单','d-checkout-agreed',{w:300})]);
desktop('d-certificate','D21 · 检疫证明展示',[title('INDIVIDUAL RECORD','奶糖的检疫证明','实际证号与图片由店主逐只上传；此处不生成仿真证明。'),box([t('证明编号：待经营者录入',20),t('检疫证明图片展示区\n实际页面支持放大查看',24,'muted'),t('设计占位 · 非官方证件',14,'brand')],{pad:64,bg:'surface',radius:24,gap:40}),btn('返回宠物详情','d-detail',{w:240})]);

// 手机端使用同一内容系统，保留独立点击区域与紧凑信息层级。
screen('m-home','M01 · 手机首页','mobile',[
 row([t('暖爪 paw & home',22,'brand',{w:240,bold:true}),btn('AI','m-ai',{w:64,secondary:true})],{w:390,h:76,padX:20,gap:24,center:true}),
 box([t('A LITTLE PAW. A LOT OF LOVE.',10,'brand'),t('一个小小的相遇，\n让日常多一点温柔。',29,'ink',{serif:true}),photo('corgi',350,330,{radius:24}),row([btn('去认识它们 →','m-list',{w:190}),btn('问问 AI','m-ai',{w:144,secondary:true})],{gap:16}),t('一宠一档 · 到店核验 · 安心自提',11,'muted')],{padX:20,padY:20,gap:20}),
 box([t('认识你的新伙伴',22,'ink',{bold:true}),row([petcard(pets[0],true,'m-detail'),petcard(pets[1],true,'m-detail')],{gap:8}),btn('查看全部宠物 →','m-list',{w:350,secondary:true})],{pad:20,gap:20}),mtabs()
]);
mobile('m-list','发现宠物',[field('搜索','品种、毛色、性格…'),row([btn('全部','m-list',{w:74}),btn('猫咪','m-list-cat',{w:74,secondary:true}),btn('狗狗','m-list',{w:74,secondary:true}),btn('筛选','m-filter',{w:74,secondary:true})],{gap:12}),t('找到 12 位小伙伴',13,'muted'),row([petcard(pets[0],true,'m-detail'),petcard(pets[1],true,'m-detail')],{gap:8}),row([petcard(pets[2],true,'m-detail'),petcard(pets[3],true,'m-detail')],{gap:8})]);
mobile('m-list-cat','猫咪小伙伴',[badge('猫咪 · ¥1,000–¥3,000','brand','peach'),btn('清除筛选','m-list',{ghost:true,w:160}),row([petcard(pets[1],true,'m-detail'),petcard(pets[3],true,'m-detail')],{gap:8})]);
mobile('m-filter','筛选宠物',[t('选择你的偏好',25,'ink',{bold:true}),row([btn('猫咪','m-list-cat',{w:164}),btn('狗狗','m-list',{w:164,secondary:true})]),field('品种','全部品种'),row([field('最低价格','¥1,000'),field('最高价格','¥3,000')],{gap:12}),field('性别 / 月龄','不限 / 0–12个月'),field('毛色','不限'),btn('查看筛选结果','m-list-cat',{w:350})],{noTabs:true});
mobile('m-detail','宠物详情',[photo('corgi',350,360,{radius:24}),badge('● 在售 · 到店自提'),t('奶糖，快乐小尾巴。',28,'ink',{serif:true}),t('柯基 / 弟弟 / 4个月 / 3.2kg',14,'muted'),t('¥ 4,800',34,'brand',{bold:true}),t('活泼亲人，喜欢探索，也喜欢依偎。',15,'muted'),box([t('健康与成长档案',20,'ink',{bold:true}),t('疫苗记录与驱虫记录可查\n到店前请阅读健康情况与饲养说明',14,'muted'),btn('查看检疫证明','m-certificate',{w:302,secondary:true})],{pad:24,bg:'surface',radius:20}),row([btn('问问 AI','m-ai',{w:142,secondary:true}),btn('立即购买','m-login',{w:192})],{gap:16})],{noTabs:true,back:'m-list'});
mobile('m-login','登录',[t('你好，未来的家人。',28,'ink',{serif:true}),t('用手机号登录，开始这次相遇。',14,'muted'),field('手机号','138 0000 0000'),field('验证码','输入6位验证码'),btn('获取验证码','m-login-ready',{w:350,secondary:true}),btn('登录并继续','m-checkout',{w:350}),t('微信内可授权登录并绑定手机号。',12,'muted')],{noTabs:true,back:'m-detail'});
mobile('m-login-ready','验证码已发送',[badge('已发送至 138****0000'),field('验证码','123456','59秒后可重新获取'),btn('登录并继续','m-checkout',{w:350})],{noTabs:true});
mobile('m-checkout','确认订单',[productSummary(),box([t('到店自提',18,'ink',{bold:true}),t('暖爪 · 城市宠物生活馆\n每天 10:00–20:00\n示例地址：城市中心生活街区18号',14,'muted')],{pad:20,bg:'sand',radius:16}),field('联系人','张女士'),field('已验证手机号','138 0000 0000'),btn('阅读活体宠物交易须知','m-agreement',{w:350,secondary:true}),btn('☐ 已阅读并同意须知','m-checkout-ready',{w:350,ghost:true}),t('合计  ¥ 4,800.00',26,'brand',{bold:true}),btn('请先同意协议',null,{w:350,disabled:true})],{noTabs:true});
mobile('m-agreement','交易须知',[t('请认真阅读',26,'ink',{bold:true}),t('本店仅支持到店自提。\n\n交付前请核验宠物健康、档案与检疫证明，并完成本人确认。\n\n健康保障与售后以正式签署版本为准。健康申诉请提交诊断报告。\n\n协议版本与确认时间随订单保存。',16),t('设计展示结构 · 正式条款待确认',12,'muted'),btn('已阅读并同意','m-checkout-ready',{w:350})],{noTabs:true});
mobile('m-checkout-ready','确认并支付',[productSummary(),badge('✓ 已同意交易须知','brand','peach'),t('到店自提 / 张女士 / 138****0000',14,'muted'),t('合计  ¥ 4,800.00',28,'brand',{bold:true}),btn('微信支付 ¥ 4,800','m-paying',{w:350}),t('下单后保留30分钟，超时自动关闭未付款订单。',12,'muted')],{noTabs:true});
mobile('m-paying','正在确认支付',[t('正在确认支付结果…',26,'ink',{bold:true}),t('请稍候，支付结果以微信确认的信息为准。',15,'muted'),badge('处理中','brand','peach'),btn('查看支付成功示例','m-pickup',{w:350}),btn('支付遇到问题','m-payment-error',{w:350,secondary:true})],{noTabs:true});
mobile('m-payment-error','支付未完成',[t('付款尚未完成',28,'ink',{bold:true}),t('这只宠物仍为你保留，\n请在 29:42 内完成付款。',16,'muted'),btn('继续微信支付','m-paying',{w:350}),btn('返回订单','m-orders',{w:350,secondary:true})],{noTabs:true});
mobile('m-pickup','自提凭证',[badge('✓ 支付成功 · 待自提'),t('期待与你见面。',30,'ink',{serif:true}),box([t('到店向店主出示',14,'muted'),t('4826 1950',40,'brand',{bold:true}),t('自提码请勿转发',12,'muted')],{pad:28,bg:'surface',radius:24}),t('请在 9月10日 15:30 前到店\n暖爪 · 城市宠物生活馆\n每天 10:00–20:00',15),btn('我已到店，核验健康','m-confirm',{w:350}),btn('查看订单 / 申请售后','m-orders',{w:350,secondary:true})],{noTabs:true});
mobile('m-confirm','当面健康确认',[t('请先当面核验，再确认。',24,'ink',{bold:true}),t('逐项观察奶糖的健康状态，\n有疑问请先与店主沟通。',15,'muted'),box([t('✓ 精神状态',17),t('✓ 眼鼻情况',17),t('✓ 毛发与皮肤',17),t('✓ 排泄物情况',17)],{pad:24,bg:'surface',radius:20,gap:20}),field('本人短信验证码','123456','用于确认本次交付'),btn('确认健康，提交核验','m-confirmed',{w:350}),t('仍需店主核销后才完成交付。',12,'muted')],{noTabs:true});
mobile('m-confirmed','买家确认已提交',[badge('✓ 本人核验已完成'),t('请交给店主\n完成最后一步。',28,'ink',{serif:true}),t('当前仍为待自提。店主确认交付后，\n订单才会变为已完成。',16,'muted'),btn('查看已交付示例','m-completed',{w:350}),btn('返回自提凭证','m-pickup',{w:350,secondary:true})],{noTabs:true});
mobile('m-completed','已完成交付',[badge('✓ 订单已完成'),t('欢迎奶糖，\n成为你的家人。',30,'ink',{serif:true}),photo('corgi',350,260,{radius:24}),t('交付：09月07日 16:18\n健康保障截止：09月14日 16:18\n具体范围以签署协议为准。',14,'muted'),btn('问问 AI 到家注意事项','m-ai',{w:350}),btn('申请健康售后','m-after',{w:350,secondary:true})],{noTabs:true});
mobile('m-orders','我的订单',[row([btn('待付款','m-pending',{w:100}),btn('待自提','m-pickup',{w:108,secondary:true}),btn('售后','m-after-progress',{w:108,secondary:true})]),badge('● 待自提'),productSummary(),btn('查看自提凭证','m-pickup',{w:350}),btn('申请售后','m-after',{w:350,secondary:true})]);
mobile('m-profile','我的',[t('下午好，张女士。',26,'ink',{serif:true}),t('138****0000',14,'muted'),box([btn('我的订单 →','m-orders',{w:302,secondary:true}),btn('联系店主 →','m-contact',{w:302,secondary:true}),btn('AI 养宠助手 →','m-ai',{w:302,secondary:true}),btn('退出登录','m-login',{w:302,ghost:true})],{pad:24,bg:'surface',radius:24}),t('每一份陪伴，都值得用心。',14,'muted')]);
mobile('m-after','申请售后',[t('我们会认真处理。',26,'ink',{serif:true}),field('申请类型','健康问题申诉'),field('期望方式','全额退款'),field('问题说明','症状、发现时间与诊疗情况'),box([t('＋ 上传诊断报告',18,'brand'),t('PDF / 图片，单份不超过10MB',12,'muted')],{pad:28,bg:'surface',stroke:'line',radius:20}),btn('提交申请','m-after-progress',{w:350})],{noTabs:true});
mobile('m-after-progress','售后进度',[badge('等待店主审核','brand','peach'),t('申请已收到',28,'ink',{bold:true}),t('AS20260907001',13,'muted'),box([t('● 已提交申请',19,'brand'),t('诊断报告与问题说明已收到',14,'muted'),t('○ 店主审核',19),t('○ 处理结果',19)],{pad:28,bg:'surface',radius:24,gap:24}),btn('联系店主','m-contact',{w:350,secondary:true})]);
mobile('m-ai','AI 养宠助手',[t('懂一点它，\n就更靠近一点。',28,'ink',{serif:true}),box([t('你好！想聊聊哪位小伙伴，\n或了解猫狗日常照护？',16)],{pad:24,bg:'surface',radius:20}),btn('奶糖刚到家要注意什么？','m-ai-answer',{w:350,secondary:true}),btn('查询我的自提订单','m-ai-order',{w:350,secondary:true}),field('输入问题','输入你想了解的内容'),btn('发送 →','m-ai-answer',{w:350}),t('AI 生成，仅供参考。',11,'muted')]);
mobile('m-ai-answer','AI 回复',[box([t('奶糖刚到家要注意什么？',15)],{pad:20,bg:'peach',radius:20}),box([t('先为它准备安静、固定的休息区。\n\n尽量维持原来的粮食与喂食节奏，减少频繁接触陌生人，观察精神与排便情况。',16),t('来源：幼犬到家适应指南',12,'brand'),t('AI 生成，仅供参考。\n持续不适建议及时就医。',12,'muted')],{pad:24,bg:'surface',radius:20,gap:20}),row([btn('有帮助','m-ai',{w:120,secondary:true}),btn('联系店主','m-contact',{w:210})],{gap:20}),field('继续提问','还需要准备什么？')]);
mobile('m-ai-order','本人订单咨询',[t('这是你最近的订单',24,'ink',{bold:true}),badge('● 待自提'),productSummary(),t('请在约定时间前到店，自提凭证仅在\n你的订单详情内展示。',14,'muted'),btn('查看本人订单','m-pickup',{w:350}),t('AI 生成，仅供参考。订单事实来自订单系统。',11,'muted')]);
mobile('m-contact','联系店主',[t('和店主聊一聊。',28,'ink',{serif:true}),t('暖爪 · 城市宠物生活馆\n每天10:00–20:00',17),field('联系电话','400-000-0000','设计示例'),field('微信号','warm-paw-demo','设计示例'),btn('返回','m-profile',{w:350,secondary:true})]);
mobile('m-certificate','检疫证明',[t('奶糖的个体档案',24,'ink',{bold:true}),t('证明编号：待经营者录入',15),box([t('证明图片展示区',22,'muted'),t('设计占位 · 非官方证件',12,'brand')],{pad:40,h:300,bg:'surface',radius:20}),btn('返回详情','m-detail',{w:350})],{noTabs:true});

// 统一后台侧栏与内容网格，突出订单核销、证明和售后待办。
const adminNav=[['工作台','a-home'],['宠物管理','a-pets'],['订单管理','a-orders'],['售后处理','a-after'],['知识库','a-knowledge'],['门店设置','a-shop']];
function admin(id,name,content){screen(id,name,'admin',[row([
 box([t('暖爪',30,'brand',{bold:true}),t('STORE CONSOLE',11,'muted'),...adminNav.map(([label,target])=>btn(label,target,{w:180,secondary:target!==id,ghost:target!==id})),t('单店自营\n店主 · 管理员',12,'muted')],{w:240,h:1000,pad:28,gap:24,bg:'surface'}),
 box([row([t(name,26,'ink',{bold:true,w:740}),t('2026 / 09 / 07',14,'muted',{w:180})],{h:72,center:true}),...content],{w:1200,pad:40,gap:28})
],{w:1440,gap:0})]);}
const metric=(label,value,note)=>box([t(label,14,'muted'),t(value,34,'ink',{bold:true}),t(note,12,'green')],{w:258,pad:24,bg:'surface',radius:20});
const table=(headers,rows,targets=[])=>box([row(headers.map((s,i)=>t(s,12,'muted',{w:i===0?230:150})),{pad:16,bg:'sand',gap:12}),...rows.map((cells,index)=>row(cells.map((s,i)=>i===cells.length-1&&targets[index]?btn(s,targets[index],{w:140,ghost:true}):t(s,14,i===cells.length-1?'brand':'ink',{w:i===0?230:150})),{pad:16,gap:12,center:true}))],{bg:'surface',radius:16,gap:0});
admin('a-home','工作台',[title('HELLO, STORE OWNER','每一份托付，认真照顾。','今天有 2 笔待自提订单、1 笔售后待审核。'),row([metric('今日收款','¥ 16,800','按支付成功时间统计'),metric('今日订单','8','3 笔已支付'),metric('在售宠物','18','猫咪 10 / 狗狗 8'),metric('AI 会话','26','今日有提问的会话')],{gap:16}),row([btn('办理到店核销','a-pickup',{w:230}),btn('发布宠物','a-edit',{w:180,secondary:true}),btn('处理售后','a-after',{w:180,secondary:true})]),t('待处理订单',20,'ink',{bold:true}),table(['订单 / 宠物','联系人','实付','状态','操作'],[['WP09070001 · 奶糖','张女士','¥ 4,800','待自提','办理核销'],['WP09070002 · 年糕','李先生','¥ 1,800','待付款','查看订单']],['a-pickup','a-orders'])]);
admin('a-pets','宠物管理',[row([title('PETS','一宠一档，状态清晰。'),btn('＋ 发布宠物','a-edit',{w:200})],{gap:200}),row([field('搜索','名称 / 品种 / 编号'),btn('在售 18','a-pets',{w:120}),btn('已预订 2','a-pets',{w:130,secondary:true}),btn('下架 3','a-edit',{w:120,secondary:true})],{center:true}),table(['宠物 / 编号','品种','售价','状态','操作'],[['奶糖 · P0001','柯基','¥ 4,800','已预订','查看档案'],['年糕 · P0002','中华田园猫','¥ 1,800','在售','编辑'],['松饼 · P0003','金毛','¥ 3,600','待补证明','完善资料']],['a-edit','a-edit','a-edit']),t('已预订个体不能通过手动修改状态释放；退款与交付由订单流程推进。',13,'muted')]);
admin('a-edit','编辑宠物档案',[row([box([title('INDIVIDUAL PROFILE','奶糖 · 个体档案'),row([field('宠物名称','奶糖'),field('类型 / 品种','狗 / 柯基')]),row([field('性别 / 月龄','弟弟 / 4个月'),field('售价','¥ 4,800')]),row([field('体重 / 毛色','3.2kg / 黄白'),field('性格','活泼亲人')]),field('疫苗与驱虫记录','按实际记录填写'),field('健康与饲养说明','性格、饮食、活动和健康观察'),field('检疫证明编号','待经营者录入'),btn('上传证明图片','a-edit',{w:240,secondary:true})],{w:690,gap:20}),box([photo('corgi',310,340,{radius:20}),t('主图 · 可拖动调整图片顺序',12,'muted'),badge('资料待完善','brand','peach'),t('正式上架前，请核验个体健康与\n证明有效性。已预订个体不可强制上架。',14,'muted'),btn('保存草稿','a-pets',{w:310,secondary:true}),btn('提交上架核验','a-publish',{w:310})],{w:310,gap:20})],{gap:40})]);
admin('a-publish','上架核验',[title('BEFORE PUBLISHING','确认个体健康与证明。'),badge('需店主实际核验','brand','peach'),field('健康核验','已现场确认适合出售'),field('检疫证明','编号与有效材料齐全'),field('核验备注','请填写实际核验情况'),btn('确认核验并上架','a-pets',{w:300}),t('原型为流程示例，不代表任何示例宠物已取得真实资质。',12,'muted')]);
admin('a-orders','订单管理',[row([field('订单号 / 联系人','搜索订单号或已验证手机号'),btn('查询','a-orders',{w:120})],{center:true}),row([btn('全部','a-orders',{w:110}),btn('待自提','a-pickup',{secondary:true,w:130}),btn('售后中','a-after',{secondary:true,w:130})]),table(['订单 / 宠物','联系人','金额','状态','操作'],[['WP09070001 · 奶糖','张女士','¥ 4,800','待自提','核销'],['WP09060008 · 松饼','赵女士','¥ 3,600','售后中','处理售后'],['WP09050003 · 橘子','王先生','¥ 1,500','已完成','查看记录']],['a-pickup','a-after','a-pickup-done'])]);
admin('a-pickup','到店核销',[title('PICKUP VERIFICATION','先核验，再交付。'),row([field('自提码','48261950'),btn('查询订单','a-pickup-ready',{w:180})],{center:true}),productSummary(),box([t('买家健康确认：尚未完成',20,'brand',{bold:true}),t('请买家在本人手机打开自提凭证，\n完成现场健康项目和短信确认。',16,'muted'),btn('等待买家确认',null,{disabled:true,w:260})],{pad:28,bg:'surface',radius:20}),btn('查看买家已确认示例','a-pickup-ready',{w:270,secondary:true})]);
admin('a-pickup-ready','确认交付',[badge('✓ 买家已确认 · 短信校验通过'),productSummary(),t('确认时间：09月07日 16:15\n核验项目：精神 / 眼鼻 / 毛发 / 排泄物\n订单状态：已支付，待自提\n检疫证明：请店主核对实际有效材料',18),field('交付核验备注','已核对当前个体与有效检疫证明'),btn('确认交付并核销','a-pickup-done',{w:300}),t('此操作会将订单变为已完成，宠物变为已售，自提码失效。',13,'muted')]);
admin('a-pickup-done','交付已完成',[badge('✓ 核销成功'),t('这一次相遇，完成了。',34,'ink',{serif:true}),t('订单 WP09070001\n宠物：奶糖 / 状态：已售\n交付时间：09月07日 16:18\n操作人：店主',18),btn('返回订单列表','a-orders',{w:240})]);
admin('a-after','售后处理',[title('AFTER-SALES','材料、处理与结果，都有记录。'),badge('1 笔待审核','brand','peach'),table(['售后 / 订单','申请人','诉求','状态','操作'],[['AS09070001 · 松饼','赵女士','全额退款','待审核','查看材料']],['a-after-review']),t('已交付宠物的售后驳回不会让宠物重新上架；退回后须经复检再处理。',13,'muted')]);
admin('a-after-review','售后审核',[title('REVIEW','健康问题申诉'),row([box([field('用户诉求','全额退款'),field('问题说明','用户描述症状、确诊时间与诊疗情况'),box([t('诊断报告.pdf',18,'brand'),t('附件经权限校验后可查看',12,'muted')],{pad:24,bg:'surface',radius:16}),field('审核意见','请记录责任认定依据与处理说明'),row([btn('通过，等待退回','a-return',{w:260}),btn('驳回并说明原因','a-after',{w:260,secondary:true})])],{w:700,gap:24}),box([t('处理提醒',20,'ink',{bold:true}),t('健康责任由店主审核，AI不作裁决。\n已交付宠物按规则到店退回。\n退款成功前不得重复释放库存。',15,'muted')],{w:340,pad:24,bg:'peach',radius:20})],{gap:32})]);
admin('a-return','退回验收与退款',[title('RETURN & REFUND','确认实物退回后办理退款。'),field('退回个体','松饼 / P0003'),field('验收情况','请记录个体身份与健康观察结果'),btn('确认已退回，发起原路退款','a-refunding',{w:400}),t('退回宠物先下架，退款成功也不会自动重新上架。',14,'muted')]);
admin('a-refunding','退款处理中',[badge('微信原路退款处理中','brand','peach'),t('退款申请已受理。',32,'ink',{bold:true}),t('退款金额：¥ 3,600.00\n退款结果：等待微信确认\n宠物状态：下架，待复检',18),btn('返回售后列表','a-after',{w:240})]);
admin('a-knowledge','猫狗知识库',[row([title('KNOWLEDGE LIBRARY','让每次回答，有据可依。'),btn('＋ 新建知识','a-knowledge-edit',{w:210})],{gap:140}),row([btn('导入 Excel / CSV','a-import',{w:240,secondary:true}),btn('重建向量索引','a-index',{w:240,secondary:true})]),table(['标题','宠物类型','分类','索引状态','操作'],[['幼犬到家适应指南','狗','饲养','已就绪','编辑'],['猫咪日常梳毛','猫','美容','已就绪','编辑'],['疫苗与驱虫记录解读','猫和狗','健康','待更新','查看任务']],['a-knowledge-edit','a-knowledge-edit','a-index']),t('历史问答需店主审核后发布；价格与库存始终从商品系统实时读取。',13,'muted')]);
admin('a-knowledge-edit','编辑知识条目',[field('标题','幼犬到家适应指南'),row([field('宠物类型','狗'),field('知识分类','饲养'),field('格式','文章 / 问答对')]),field('正文','为幼犬准备安静固定的休息区，逐步适应新环境。'),field('来源名称','门店饲养手册'),field('公开来源链接（选填）','https://...'),row([btn('保存草稿','a-knowledge',{w:220,secondary:true}),btn('审核并发布','a-index',{w:220})])]);
admin('a-import','批量导入知识',[title('BULK IMPORT','先整理，再让知识进入问答。'),btn('下载 Excel / CSV 模板','a-import',{w:300,secondary:true}),box([t('＋ 选择知识文件',24,'brand'),t('支持 CSV / XLSX，单文件≤10MB，最多5000行。',14,'muted')],{pad:48,bg:'surface',stroke:'line',radius:24}),btn('导入为草稿','a-import-result',{w:260}),t('导入不会自动发布，需店主检查内容与引用来源。',13,'muted')]);
admin('a-import-result','导入结果',[badge('部分导入成功','brand','peach'),t('98 条成功 / 2 条需修正',30,'ink',{bold:true}),table(['行号','字段','错误','处理','操作'],[['第23行','answer','问答答案为空','补充后重试','查看条目'],['第61行','petType','不支持异宠','仅支持猫狗','查看条目']],['a-knowledge-edit','a-knowledge-edit']),btn('返回知识库','a-knowledge',{w:240})]);
admin('a-index','索引任务',[title('INDEX STATUS','发布成功，还需要索引就绪。'),badge('正在更新向量索引','brand','peach'),t('已处理 72 / 100 条\n旧索引在切换前继续可用。\n商品推荐会重新核对当前售价与库存。',18),btn('查看已就绪示例','a-knowledge',{w:280})]);
admin('a-shop','门店设置',[row([box([title('STORE SETTINGS','让买家找到你。'),field('门店名称','暖爪 · 城市宠物生活馆'),field('门店地址','示例：城市中心生活街区18号'),row([field('营业时间','每天10:00–20:00'),field('联系电话','400-000-0000')]),field('微信号','warm-paw-demo'),field('自提说明','到店核对档案、健康与证明后确认交付'),btn('保存门店设置','a-shop',{w:260})],{w:700,gap:20}),box([t('首页 Banner',22,'ink',{bold:true}),photo('corgi',310,220,{radius:20}),field('展示标题','认识你的新伙伴'),btn('更换图片','a-shop',{w:310,secondary:true}),t('经营联系信息与图片均为设计示例，\n正式上线须填写真实门店资料。',12,'muted')],{w:310,gap:20})],{gap:40})]);

// 附加异常界面，用于前端实现状态而非制造虚假的成功结果。
mobile('m-empty','没有找到合适的小伙伴',[t('试试放宽一点条件。',28,'ink',{serif:true}),t('暂时没有符合这些条件的宠物，\n可以调整品种、价格或年龄。',16,'muted'),btn('清除筛选条件','m-list',{w:350})]);
mobile('m-reserved','宠物已被预订',[photo('corgi',350,300,{radius:24}),badge('已预订','brand','peach'),t('它正在等待另一场相遇。',26,'ink',{serif:true}),t('这只宠物暂时不能下单，\n还可以认识店里的其他小伙伴。',15,'muted'),btn('查看其他宠物','m-list',{w:350})]);

// 设计说明留在画布中，不混入买家页面的正常操作文案。
screen('guide','00 · 设计指南与交付说明','desktop',[box([
 t('WARM PAW / DIGITAL EXPERIENCE',13,'brand'),t('暖爪\n让相遇，成为日常。',58,'ink',{serif:true}),
 t('电脑买家端 · 手机买家端 · 店主管理后台',25,'ink',{bold:true}),
 t('依据 PRD v1.2 与接口文档；本次按用户确认补充电脑买家端。\n暖爪是临时品牌名，照片与经营数据均为设计示例。\n真实宠物照片来源：Unsplash；不伪造检疫证明、真实订单或付款结果。',17,'muted'),
 t('设计语言\n奶油白 #FBF8F3 / 暖橙 #B9512B / 深棕 #2B2724 / 鼠尾草 #E9F0E8\nNoto Sans SC / Noto Serif SC / Manrope\n桌面1440px · 手机390px · 4/8px间距系统 · 点击区域≥44px',16),
 t('动态原型\n按钮悬停180ms / 页面过渡300ms / 筛选页滑入280ms / 照片轮播450ms\n真实微信支付、短信、AI与退款均需开发接入；原型仅演示页面状态。\n桌面支付采用手机继续H5的设计建议；现有接口未定义Native扫码支付。',16,'muted'),
 row([btn('电脑端原型 →','d-home',{w:240}),btn('手机与后台请切换左侧页面',null,{w:380,secondary:true})])
],{pad:64,gap:28})],{h:1000});

// 将布局自动分配到各端画布，避免覆盖前一阶段已保存的组件和照片。
// 补齐关键入口与轮播；所有动作仅切换设计状态，不调用业务接口。
mobile('m-wechat','微信登录与手机号绑定',[t('欢迎来到暖爪',28,'ink',{serif:true}),t('使用微信授权登录后，绑定手机号以便到店核验。',15,'muted'),field('手机号','138 0000 0000'),field('短信验证码','123456'),btn('确认绑定并继续','m-checkout',{w:350})],{noTabs:true});
mobile('m-pending','待付款订单',[badge('等待付款','brand','peach'),productSummary(),t('请在29:42内完成微信支付。',18),btn('继续支付','m-paying',{w:350}),btn('取消订单','m-cancelled',{w:350,secondary:true})]);
mobile('m-cancelled','订单已取消',[t('订单已取消',28,'ink',{bold:true}),t('本次预留已解除，可以重新选择。',16,'muted'),btn('继续发现宠物','m-list',{w:350})]);
admin('a-login','管理员登录',[title('STORE ACCESS','欢迎回来，店主。'),field('账号','请输入管理员账号'),field('密码','••••••••'),field('验证码','请输入图片验证码'),box([t('A 7 K 2',24,'brand'),t('验证码示意',12,'muted')],{w:260,pad:20,bg:'sand',radius:12}),btn('登录工作台','a-home',{w:360})]);
for(const s of pages){
 const walk=n=>{if(n.type==='button'&&n.target===s.id){n.target=null;n.demo=true;}if(n.children)n.children.forEach(walk);};walk(s);
 if(s.id==='m-login')s.children[1].children.push(btn('微信授权快捷登录','m-wechat',{w:350,secondary:true}));
}
for(const id of ['d-home','m-home']){
 const first=pages.find(p=>p.id===id), second=JSON.parse(JSON.stringify(first));
 second.id=id+'-slide2';second.name=first.name+' · 轮播第二帧';
 let changed=false;const replace=n=>{if(n.type==='image'&&!changed){n.image='cat';changed=true;}if(n.type==='text'&&n.text==='HELLO, 我是奶糖')n.text='HELLO, 我是年糕';if(n.type==='button'&&n.text==='认识我 →'){n.text='发现猫咪 →';n.target='d-list-filtered';}if(n.type==='text'&&n.text.includes('一个小小的相遇'))n.text='一声轻轻的呼噜，\n是家的另一种声音。';if(n.children)n.children.forEach(replace);};replace(second);
 (id==='d-home'?first.children[1].children[1]:first.children[1]).children.push(btn('下一张 →',second.id,{w:150,secondary:true}));(id==='d-home'?second.children[1].children[1]:second.children[1]).children.push(btn('上一张 ←',id,{w:150,secondary:true}));
 first.carousel=second.id;second.carousel=id;pages.push(second);
}
const counts={desktop:0,mobile:0,admin:0};
for(const s of pages){const i=counts[s.device]++;s.x=80+(i%(s.device==='mobile'?6:3))*(s.device==='mobile'?490:1600);s.y=300+Math.floor(i/(s.device==='mobile'?6:3))*1600;if(s.id==='guide'){s.x=80;s.y=-2200;} }
function stamp(node,parent='') {node.key=node.key||'n'+(++sequence);node.parentKey=parent;for(const child of node.children||[])stamp(child,node.key);}
pages.forEach(s=>{s.key=s.id;stamp(s);});
const design={state,pages,pets};
fs.writeFileSync(path.join(dir,'design.json'),JSON.stringify(design,null,2));
console.log(JSON.stringify({screens:pages.length,nodes:sequence,byDevice:counts}));
