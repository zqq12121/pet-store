// 使用现有 Playwright 与 Chrome，验证隔离副本；不修改生产页面或绕过真实云端认证。
const {createRequire}=require('node:module')
const path=require('node:path')
const fs=require('node:fs')
const assert=require('node:assert/strict')
const root=path.resolve(__dirname,'..')
const {chromium}=createRequire(path.join(root,'frontend/package.json'))('@playwright/test')
const base=process.argv.find(a=>a.startsWith('http://'))||'http://127.0.0.1:8190'
const interactive=process.argv.includes('--interactive')
const out=path.join(root,'output/demo-acceptance')

// 只向明确返回测试 ID 的本机副本注入模拟 SDK；正常站点始终使用官方认证。
function simulatedSdk(){
 window.initAlicom4=(config,done)=>{
  if(config.captchaId!=='demo-acceptance-only')throw new Error('拒绝在真实站点注入模拟认证')
  const callbacks={};let dialog,proof
  const captcha={
   appendTo(){queueMicrotask(()=>callbacks.ready());return captcha},
   onReady(f){callbacks.ready=f;return captcha},onSuccess(f){callbacks.success=f;return captcha},
   onClose(f){callbacks.close=f;return captcha},onError(f){callbacks.error=f;return captcha},
   getValidate(){return proof},destroy(){dialog?.remove()},
   showBox(){
    proof={lot_number:'demo-'+crypto.randomUUID(),captcha_output:'demo-only',pass_token:'demo-only',gen_time:String(Math.floor(Date.now()/1000))}
    dialog=document.createElement('dialog')
    dialog.innerHTML='<h2>模拟图形认证，仅供隔离测试</h2><p>这是测试替代实现，不调用阿里云。</p><button type="button">通过模拟验证</button> <button type="button">取消模拟验证</button>'
    const buttons=dialog.querySelectorAll('button')
    buttons[0].onclick=()=>callbacks.success();buttons[1].onclick=()=>callbacks.close()
    dialog.oncancel=()=>callbacks.close();document.body.appendChild(dialog);dialog.showModal()
   }
  };done(captcha)
 }
}

async function main(){
 const target=new URL(base)
 assert.equal(target.hostname,'127.0.0.1');assert.notEqual(target.port,'8090')
 const config=await (await fetch(base+'/api/v1/auth/graph-captcha')).json()
 assert.equal(config.data?.captchaId,'demo-acceptance-only','目标不是隔离验收环境')
 fs.mkdirSync(out,{recursive:true})
 const browser=await chromium.launch({channel:'chrome',headless:!interactive})
 const errors=[]
 try{
  async function newPage(){
   const context=await browser.newContext({viewport:{width:1440,height:900},timezoneId:'Asia/Shanghai'})
   await context.addInitScript(simulatedSdk)
   const page=await context.newPage()
   page.on('pageerror',e=>errors.push(e.message))
   page.on('console',message=>{if(['error','warning'].includes(message.type()))errors.push(message.text())})
   return page
  }
  const buyer=await newPage(),admin=await newPage()
  await buyer.goto(base+'/password-login')
  await buyer.getByLabel('手机号或用户名').fill('demo_buyer')
  await buyer.getByLabel('密码',{exact:true}).fill('DemoBuyer2026!')
  await admin.goto(base+'/admin/login')
  await admin.locator('input[autocomplete="username"]').fill('admin')
  await admin.locator('input[autocomplete="current-password"]').fill('DemoAdmin2026!')
  if(interactive){
   console.log('已打开隔离演示的买家与后台登录页，账号已填写；点击登录后选择“通过模拟验证”。')
   await new Promise(resolve=>browser.on('disconnected',resolve));return
  }
  // 账号密码与会话由真实 Java 服务校验、签发，只有图形认证和通知是模拟实现。
  await buyer.getByRole('button',{name:'登录',exact:true}).click()
  await buyer.getByRole('button',{name:'通过模拟验证',exact:true}).click()
  await buyer.waitForURL('**/profile')
  await admin.getByRole('button',{name:'登录工作台',exact:true}).click()
  await admin.getByRole('button',{name:'通过模拟验证',exact:true}).click()
  await admin.waitForURL(base+'/admin/')
  const shots=[]
  async function shot(page,name){
   const filename=path.join(out,name+'.png');await page.screenshot({path:filename,fullPage:true});shots.push(filename)
  }
  async function api(page,role,url){
   return page.evaluate(async({role,url})=>{
    const auth=JSON.parse(sessionStorage.getItem('warmpaw:'+role+':auth'))
    const response=await fetch('/api/v1'+url,{headers:{Authorization:'Bearer '+auth.accessToken}})
    const body=await response.json();if(body.code!=='OK')throw new Error(body.message);return body.data
   },{role,url})
  }
  await buyer.goto(base+'/pets')
  await buyer.getByRole('link',{name:/奶霜（示例）/}).first().click()
  await buyer.getByRole('button',{name:'查看检疫证明',exact:true}).click()
  await buyer.getByRole('dialog').getByText(/DEMO-/).waitFor()
  await shot(buyer,'01-quarantine')
  await buyer.getByRole('button',{name:'关闭图片',exact:true}).click()
  await buyer.getByRole('link',{name:'预约到店',exact:true}).click()
  await buyer.getByLabel('联系人',{exact:true}).fill('模拟买家')
  await buyer.getByLabel('已验证手机号').fill('13900000001')
  const tomorrow=new Date(Date.now()+86400000)
  const visit=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(tomorrow)+'T13:00'
  await buyer.getByLabel('到店时间（北京时间）').fill(visit)
  await buyer.getByRole('button',{name:'阅读《活体宠物交易须知》',exact:true}).click()
  await buyer.getByRole('dialog').getByText(/模拟协议/).waitFor()
  await buyer.getByRole('button',{name:'已阅读并同意',exact:true}).click()
  await buyer.setViewportSize({width:390,height:844})
  assert.equal(await buyer.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true)
  await shot(buyer,'02-mobile-checkout')
  await buyer.setViewportSize({width:1440,height:900})
  await buyer.getByRole('button',{name:'提交到店预约',exact:true}).click()
  await buyer.waitForURL(/\/orders\/order_/)
  const appointmentId=new URL(buyer.url()).pathname.split('/').pop()
  let order=await api(buyer,'buyer','/orders/'+appointmentId)
  assert.equal(order.status,'pending_confirmation');assert.equal(order.payment.status,'offline_unpaid')
  await shot(buyer,'03-appointment-submitted')
  await admin.goto(base+'/admin/orders')
  await admin.getByRole('row').filter({hasText:order.orderNo}).getByRole('button',{name:'查看详情'}).click()
  await admin.getByRole('button',{name:'确认到店预约',exact:true}).click()
  await admin.getByRole('button',{name:'已到店付款并交付',exact:true}).waitFor()
  await shot(admin,'04-appointment-confirmed')
  await admin.getByRole('button',{name:'已到店付款并交付',exact:true}).click()
  await admin.getByRole('button',{name:'已收款并交付',exact:true}).click()
  await admin.getByRole('button',{name:'已到店付款并交付',exact:true}).waitFor({state:'hidden'})
  order=await api(buyer,'buyer','/orders/'+appointmentId)
  assert.equal(order.status,'completed');assert.equal(order.payment.status,'offline_received')
  assert.deepEqual(order.afterSaleEligibility.allowedTypes,[])
  assert.equal((await api(buyer,'buyer','/pets/'+order.product.id)).status,'sold')
  await buyer.getByRole('button',{name:'刷新状态',exact:true}).click()
  await buyer.getByRole('heading',{name:'已完成',exact:true}).waitFor()
  await shot(buyer,'05-appointment-completed')
  // 独立历史夹具验证售后上传、权限与审核，不能把此流程冒充新预约功能。
  const orders=await api(buyer,'buyer','/orders?pageSize=100')
  const legacy=orders.items.find(o=>o.orderNo==='DEMO-AFTERSALE-20261009')
  assert.ok(legacy)
  await buyer.goto(base+'/orders/'+legacy.id)
  await buyer.getByRole('link',{name:'申请售后',exact:true}).click()
  await buyer.getByLabel('问题说明').fill('模拟健康问题，仅用于诊断附件、归属权限和售后审核的演示。')
  const diagnosis=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date()).replace(' ','T')
  await buyer.getByLabel('诊断时间').fill(diagnosis)
  const uploadResponse=buyer.waitForResponse(r=>r.url().endsWith('/api/v1/files')&&r.request().method()==='POST')
  await buyer.locator('input[type=file]').setInputFiles(path.join(root,'output/demo-materials/diagnosis-demo.png'))
  const asset=(await (await uploadResponse).json()).data
  assert.equal(asset.visibility,'private');assert.equal(asset.publicUrl,null)
  await buyer.getByRole('button',{name:'移除',exact:true}).waitFor()
  await shot(buyer,'06-diagnosis-uploaded')
  await buyer.getByRole('button',{name:'提交申请',exact:true}).click()
  await buyer.waitForURL(/\/after-sales\/after_sale_/)
  const saleId=new URL(buyer.url()).pathname.split('/').pop()
  await admin.goto(base+'/admin/after-sales')
  await admin.getByRole('row').filter({hasText:saleId}).getByRole('button',{name:'查看材料'}).click()
  const popupEvent=admin.waitForEvent('popup')
  await admin.getByRole('button',{name:'查看诊断报告 1',exact:true}).click()
  const report=await popupEvent;await report.waitForLoadState()
  assert.equal(await report.locator('img').evaluate(i=>i.complete&&i.naturalWidth>0),true)
  await shot(report,'07-private-diagnosis');await report.close()
  assert.equal((await buyer.context().request.get(base+'/api/v1/media/'+asset.id)).status(),404)
  await admin.getByPlaceholder('请记录责任认定依据与处理说明').fill('模拟审核：附件展示和权限校验通过，本次演示按驳回分支结束。')
  await admin.getByRole('button',{name:'驳回并说明原因',exact:true}).click()
  await admin.getByRole('button',{name:'驳回并说明原因',exact:true}).waitFor({state:'hidden'})
  await buyer.getByRole('button',{name:'刷新进度',exact:true}).click()
  await buyer.getByText('申请已驳回',{exact:true}).first().waitFor()
  await shot(buyer,'08-after-sale-reviewed')
  assert.deepEqual(errors,[])
  const result={result:'PASS',base,appointmentId,legacyOrderId:legacy.id,saleId,diagnosisFileId:asset.id,
   checks:['真实密码登录与会话','公开模拟证明','协议阅读和预约提交','后台确认','线下交付与宠物已售','新预约在线售后保持关闭','历史订单诊断上传','私有附件授权可读且匿名404','后台审核及买家进度','390px结算无横向溢出'],
   thirdPartyMode:'simulated',pageErrors:errors,screenshots:shots}
  fs.writeFileSync(path.join(out,'report.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result))
 }finally{await browser.close()}
}
main().catch(error=>{console.error(error);process.exitCode=1})
