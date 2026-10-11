// 定点修正已存在的小程序：复用可编辑页面、照片、变量和组件，不访问网络。
async function repairMiniProgram(apply = false) {
  const ids = {
    home: '30:512', pets: '30:513', detail: '30:514', booking: '30:515',
    success: '30:516', records: '30:517', record: '30:518', my: '30:519',
    login: '30:520', shop: '30:521', ai: '30:522', empty: '30:523'
  };
  const pets = [
    ['奶糖', '柯基', '4个月', '弟弟', '4,800', '001', '08b1df33f71fed3769848a3cdb6eefa70e88e998'],
    ['年糕', '田园猫', '5个月', '妹妹', '1,800', '002', 'a6edeaf9c2df201f3f1a47f1e789cddbf59d99e5'],
    ['松饼', '金毛', '3个月', '弟弟', '3,600', '003', '199b94884be6c1babff99bb8f0b95ac59730907d'],
    ['橘子', '田园猫', '6个月', '妹妹', '1,500', '004', '7770cfd048bbf9034127290f6c2b6a359938a697']
  ];
  const page = await figma.getNodeByIdAsync('4:2');
  if (!page || page.type !== 'PAGE') throw Error('找不到原手机买家端页面。');
  if (figma.fileKey && figma.fileKey !== '2P7oqPkLhdwdkpbGnyFNWZ') throw Error('请在原设计文件运行。');
  await figma.setCurrentPageAsync(page);
  // 写入前验证节点、卡片结构和旧连接，防止对不同版本的文件盲目修改。
  const screens = {};
  for (const [key, id] of Object.entries(ids)) {
    const n = await figma.getNodeByIdAsync(id);
    if (!n || n.type !== 'FRAME' || n.parent !== page) throw Error('原屏幕结构不符：' + key);
    screens[key] = n;
  }
  const allNodes = root => [root, ...root.findAll(() => true)];
  const cards = root => root.findAll(n => n.type === 'INSTANCE' && n.name.startsWith('宠物卡片 / '));
  const label = n => n.name.split(' → ')[0];
  const pills = screens.pets.findAll(n => ['全部', '猫咪', '狗狗'].includes(label(n)) && n.type === 'FRAME');
  const originalCards = cards(screens.pets);
  if (originalCards.length !== 4 || pills.length !== 3) throw Error('需要检查选宠页面的卡片和分类图层。');
  if (originalCards.some(n => n.parent.type !== 'FRAME' || n.parent.layoutMode !== 'HORIZONTAL')) {
    throw Error('卡片行布局已经变化，请先检查画布。');
  }
  const filterSources = screens.pets.findAll(n =>
    (label(n) === '筛选' || label(n) === '图标 / filter') && 'reactions' in n && n.reactions.length);
  if (!filterSources.length) throw Error('找不到原筛选入口。');
  if (page.children.some(n => n.name.startsWith('小程序 / 修正 / '))) {
    throw Error('已有修正页面，请检查已有结果后继续，避免重复创建。');
  }
  const report = {
    status: apply ? 'applying' : 'prepared-only', pageId: page.id,
    originalScreenIds: ids, originalCards: originalCards.map(n => ({id: n.id, name: n.name})),
    plannedScreens: 21, filterDemo: '参考价 2,000 元以内',
    prototypePlaybackVerified: false, createdNodeIds: [], mutatedNodeIds: []
  };
  if (!apply) return report;

  // 克隆和修改文本之前，加载原页面使用的全部字体，包括混合字体范围。
  const fonts = new Map();
  for (const root of Object.values(screens)) {
    for (const t of root.findAllWithCriteria({types: ['TEXT']})) {
      const names = t.fontName === figma.mixed ? t.getRangeAllFontNames(0, t.characters.length) : [t.fontName];
      for (const f of names) fonts.set(f.family + '/' + f.style, f);
    }
  }
  for (const f of fonts.values()) await figma.loadFontAsync(f);
  const created = [], mutated = new Set();
  const chainKeys = ['detail', 'login', 'booking', 'success', 'records', 'record'];
  const chains = [Object.fromEntries(chainKeys.map(k => [k, screens[k]]))];
  // 从现有区域右边开始排列；新的状态页不覆盖原来的 H5 或管理后台。
  const rightEdge = Math.max(...Object.values(screens).map(n => n.x + n.width));
  function clone(root, name, column, row) {
    const n = root.clone();
    page.appendChild(n);
    n.name = '小程序 / 修正 / ' + name;
    n.x = rightEdge + 160 + column * 470;
    n.y = screens.home.y + row * 960;
    created.push(n);
    return n;
  }
  async function navigate(n, target) {
    let root = n;
    while (root.parent && root.parent !== page) root = root.parent;
    // 同屏筛选状态不能建立指向自身顶层 Frame 的连接。
    if (root === target) {
      await n.setReactionsAsync([]);
      mutated.add(n.id);
      return;
    }
    await n.setReactionsAsync([{trigger: {type: 'ON_CLICK'}, actions: [{
      type: 'NODE', destinationId: target.id, navigation: 'NAVIGATE',
      transition: {type: 'DISSOLVE', duration: 0.2, easing: {type: 'EASE_OUT'}}, resetScrollPosition: true
    }]}]);
    mutated.add(n.id);
  }
  for (let i = 1; i < pets.length; i++) {
    const data = pets[i], chain = {};
    for (let j = 0; j < chainKeys.length; j++) {
      const key = chainKeys[j], n = clone(screens[key], data[0] + ' / ' + screens[key].name.split(' ').slice(3).join(' '), j, i - 1);
      chain[key] = n;
      const replacements = [
        ['奶糖', data[0]], ['柯基', data[1]], ['4个月', data[2]],
        ['弟弟', data[3]], ['4,800', data[4]], ['4800', data[4].replace(',', '')], ['DEMO-PET-001', 'DEMO-PET-' + data[5]]
      ];
      for (const t of n.findAllWithCriteria({types: ['TEXT']})) {
        let copy = t.characters;
        for (const [from, to] of replacements) copy = copy.split(from).join(to);
        if (copy !== t.characters) t.characters = copy;
      }
      for (const child of allNodes(n)) {
        if ('fills' in child && Array.isArray(child.fills) && child.fills.some(f => f.type === 'IMAGE' && f.imageHash === pets[0][6])) {
          child.fills = child.fills.map(f => f.type === 'IMAGE' && f.imageHash === pets[0][6] ? {...f, imageHash: data[6]} : f);
        }
      }
    }
    // 全链路映射到同一只宠物，避免登录或提交后返回奶糖。
    const remap = new Map(chainKeys.map(k => [ids[k], chain[k].id]));
    for (const n of Object.values(chain)) {
      for (const child of allNodes(n)) {
        if (!('reactions' in child) || !child.reactions.length) continue;
        const reactions = JSON.parse(JSON.stringify(child.reactions));
        let changed = false;
        for (const reaction of reactions) for (const action of reaction.actions || []) {
          if (action.type === 'NODE' && remap.has(action.destinationId)) {
            action.destinationId = remap.get(action.destinationId); changed = true;
          }
        }
        if (changed) await child.setReactionsAsync(reactions);
      }
    }
    chains.push(chain);
  }

  const variants = [screens.pets];
  const variantPlans = [['猫咪', [1, 3]], ['狗狗', [0, 2]], ['2千以内', [1, 3]]];
  for (let i = 0; i < variantPlans.length; i++) {
    const [title, keep] = variantPlans[i], n = clone(screens.pets, '选宠 / ' + title, i, 3);
    const selectedCards = cards(n), firstRow = selectedCards[0].parent;
    const rows = [...new Set(selectedCards.map(c => c.parent))];
    for (const c of selectedCards) {
      if (keep.some(index => c.name.includes(pets[index][0]))) firstRow.appendChild(c);
      else c.remove();
    }
    for (const row of rows) if (!row.children.length) row.remove();
    for (const t of n.findAllWithCriteria({types: ['TEXT']})) {
      if (t.characters === '4位示例伙伴') t.characters = '2位示例伙伴';
      if (title === '2千以内' && t.characters === '筛选') t.characters = title;
    }
    // 复制原分类的选中和未选中样式，保留设计变量绑定。
    const active = pills.find(p => label(p) === '全部'), inactive = pills.find(p => label(p) === '猫咪');
    for (const pill of n.findAll(c => c.type === 'FRAME' && ['全部', '猫咪', '狗狗'].includes(label(c)))) {
      const source = label(pill) === title ? active : inactive;
      pill.fills = JSON.parse(JSON.stringify(source.fills));
      const color = source.findAllWithCriteria({types: ['TEXT']})[0].fills;
      for (const t of pill.findAllWithCriteria({types: ['TEXT']})) t.fills = JSON.parse(JSON.stringify(color));
    }
    variants.push(n);
  }
  const categoryTargets = {全部: screens.pets, 猫咪: variants[1], 狗狗: variants[2]};
  for (const root of [screens.home, ...variants]) {
    for (const card of cards(root)) {
      const index = pets.findIndex(data => card.name.includes(data[0]));
      if (index < 0) throw Error('无法识别卡片：' + card.name);
      await navigate(card, chains[index].detail);
    }
    if (root === screens.home) continue;
    for (const n of allNodes(root)) {
      if (!('reactions' in n)) continue;
      if (categoryTargets[label(n)] && n.type === 'FRAME') {
        await navigate(n, categoryTargets[label(n)]);
      }
      if ((label(n) === '筛选' || label(n) === '图标 / filter') && n.reactions.length) await navigate(n, variants[3]);
    }
  }
  report.status = 'written-awaiting-visual-and-playback-review';
  report.createdNodeIds = created.flatMap(n => allNodes(n).map(c => c.id));
  report.mutatedNodeIds = [...mutated];
  report.petChains = chains.map((chain, i) => ({pet: pets[i][0], screens: Object.fromEntries(Object.entries(chain).map(([k, n]) => [k, n.id]))}));
  report.categoryScreens = variants.map(n => ({id: n.id, name: n.name, cardCount: cards(n).length}));
  figma.currentPage.selection = [variants[1], variants[2], variants[3]];
  figma.viewport.scrollAndZoomIntoView(figma.currentPage.selection);
  return report;
}

// 从真实文件读取修正结果，导出可检查的 PNG 与节点报告。
async function reviewMiniProgram() {
  const page = await figma.getNodeByIdAsync('4:2');
  await figma.setCurrentPageAsync(page);
  const originals = await Promise.all(Array.from({length: 12}, (_, i) => figma.getNodeByIdAsync('30:' + (512 + i))));
  const added = page.children.filter(n => n.type === 'FRAME' && n.name.startsWith('小程序 / 修正 / '));
  if (added.length !== 21) throw Error('修正屏数量不符：' + added.length + '；请先检查部分写入结果。');
  // 原卡片含有空的隐藏文字；空范围不能调用 getRangeAllFontNames。
  const textFonts = t => t.characters.length ? t.getRangeAllFontNames(0, t.characters.length) : (t.fontName === figma.mixed ? [] : [t.fontName]);
  const loadedFonts = new Map();
  for (const root of [...originals, ...added]) for (const t of root.findAllWithCriteria({types: ['TEXT']})) {
    for (const f of textFonts(t)) loadedFonts.set(f.family + '/' + f.style, f);
  }
  for (const f of loadedFonts.values()) await figma.loadFontAsync(f);
  const corrected = [];
  // 只修正分类结果的数量，不修改原有四卡片的全部列表。
  for (const n of added.filter(n => n.name.includes('/ 选宠 /'))) {
    for (const t of n.findAllWithCriteria({types: ['TEXT']})) {
      if (/^4\s*位.*示例/.test(t.characters)) {
        t.characters = t.characters.replace(/^4/, '2');
        corrected.push(t.id);
      }
    }
    if (n.name.endsWith('/ 2千以内')) {
      const active = originals[1].findOne(c => c.type === 'FRAME' && c.name.split(' → ')[0] === '全部');
      const activeText = active.findAllWithCriteria({types: ['TEXT']})[0];
      // 价格筛选时仍属于全部分类；同时突出已应用的筛选条件。
      for (const pill of n.findAll(c => c.type === 'FRAME' && ['全部', '筛选'].includes(c.name.split(' → ')[0]))) {
        pill.fills = JSON.parse(JSON.stringify(active.fills));
        for (const t of pill.findAllWithCriteria({types: ['TEXT']})) t.fills = JSON.parse(JSON.stringify(activeText.fills));
        corrected.push(pill.id);
      }
    }
  }
  // 用真实选宠卡片作为照片来源，修复橘子链曾误用狗狗照片的问题。
  const orangeCard = originals[1].findOne(n => n.type === 'INSTANCE' && n.name.includes('宠物卡片 / 橘子'));
  const orangePhoto = orangeCard.findOne(n => 'fills' in n && Array.isArray(n.fills) && n.fills.some(f => f.type === 'IMAGE'));
  const orangeHash = orangePhoto.fills.find(f => f.type === 'IMAGE').imageHash;
  for (const root of added.filter(n => n.name.includes('/ 橘子 /'))) {
    for (const n of root.findAll(c => 'fills' in c && Array.isArray(c.fills) && c.fills.some(f => f.type === 'IMAGE' && f.imageHash === 'd81f5453a6b8ab9c9abd1537f77ad4a992f8d06b'))) {
      n.fills = n.fills.map(f => f.type === 'IMAGE' && f.imageHash === 'd81f5453a6b8ab9c9abd1537f77ad4a992f8d06b' ? {...f, imageHash: orangeHash} : f);
      corrected.push(n.id);
    }
  }
  // 提交后仍需门店确认：列表、详情和个人页不应直接显示已确认。
  const waitingColor = originals[4].findOne(n => n.type === 'TEXT' && n.characters === '等待门店确认').fills;
  const listRoots = [originals[5], ...added.filter(n => n.name.endsWith('/ 我的预约'))];
  for (const root of listRoots) {
    const secondId = root.findOne(n => n.type === 'TEXT' && n.characters === 'DEMO-APPT-002');
    if (secondId) {
      let card = secondId.parent;
      while (card.parent !== root && !card.findOne(n => n.type === 'TEXT' && n.characters.includes('10月12日'))) card = card.parent;
      if (card.parent === root) throw Error('找不到第二条示例预约卡片，停止修改。');
      card.visible = false;
      corrected.push(card.id);
      for (const n of [card, ...card.findAll(() => true)]) if ('reactions' in n && n.reactions.length) await n.setReactionsAsync([]);
    }
    for (const t of root.findAllWithCriteria({types: ['TEXT']})) if (t.characters === '已确认') {
      t.characters = '待确认'; t.fills = JSON.parse(JSON.stringify(waitingColor)); corrected.push(t.id);
    }
  }
  const detailRoots = [originals[6], ...added.filter(n => n.name.endsWith('/ 预约详情'))];
  const pendingCopy = new Map([
    ['预约已确认', '等待门店确认'], ['已确认 · 等待到店', '已提交 · 等待确认'],
    ['✓ 已提交     ✓ 已确认     ○ 待到店', '✓ 已提交     ○ 待确认     ○ 待到店']
  ]);
  for (const root of detailRoots) for (const t of root.findAllWithCriteria({types: ['TEXT']})) {
    const copy = pendingCopy.get(t.characters) || (t.characters.startsWith('期待与你和') ? '门店确认后，请按约定时间到店。' : null);
    if (copy) {t.characters = copy; corrected.push(t.id);}
  }
  const arrivalCount = originals[7].findOne(n => n.type === 'TEXT' && n.characters === '待到店').parent.findOne(n => n.type === 'TEXT' && n.characters === '1');
  if (arrivalCount) {arrivalCount.characters = '0'; corrected.push(arrivalCount.id);}
  // 选中首页，便于随后从完整浏览流程启动演示。
  figma.currentPage.selection = [originals[0]];
  figma.viewport.scrollAndZoomIntoView(figma.currentPage.selection);
  const roots = [...originals, ...added], rootIds = new Set(roots.map(n => n.id));
  const report = {status: 'written-and-exported-awaiting-playback', pageId: page.id,
    originalScreenCount: originals.length, addedScreenCount: added.length,
    correctedNodeIds: corrected, prototypePlaybackVerified: false, screens: []};
  const files = [];
  for (let i = 0; i < roots.length; i++) {
    const n = roots[i], nodes = [n, ...n.findAll(() => true)];
    const texts = n.findAllWithCriteria({types: ['TEXT']});
    const destinations = nodes.flatMap(c => 'reactions' in c ? c.reactions.flatMap(r =>
      (r.actions || []).filter(a => a.type === 'NODE').map(a => ({sourceId: c.id, source: c.name, destinationId: a.destinationId}))) : []);
    const images = nodes.flatMap(c => 'fills' in c && Array.isArray(c.fills) ? c.fills.filter(f => f.type === 'IMAGE').map(f =>
      ({id: c.id, name: c.name, hash: f.imageHash, width: c.width, height: c.height})) : []);
    const fonts = [...new Set(texts.flatMap(t => textFonts(t).map(f => f.family)))];
    const entry = {id: n.id, name: n.name, width: n.width, height: n.height,
      nodeCount: nodes.length, nodeTypes: [...new Set(nodes.map(c => c.type))], fonts,
      texts: texts.map(t => ({id: t.id, text: t.characters, visible: t.visible})), images, destinations,
      layout: n.children.map(c => ({id: c.id, name: c.name, y: c.y, height: c.height,
        overflowDirection: c.overflowDirection, clipsContent: c.clipsContent,
        contentBottom: 'children' in c ? Math.max(0, ...c.children.filter(x => x.visible).map(x => x.y + x.height)) : null})),
      unresolvedTargets: destinations.filter(d => !rootIds.has(d.destinationId))};
    report.screens.push(entry);
    figma.ui.postMessage({type: 'progress', text: '正在导出实际页面 ' + (i + 1) + '/' + roots.length + '：' + n.name});
    const bytes = await n.exportAsync({format: 'PNG', constraint: {type: 'SCALE', value: 1}});
    files.push({name: String(i + 1).padStart(2, '0') + '-' + n.id.replace(':', '-') + '.png', bytes});
  }
  figma.ui.postMessage({type: 'archive', files, report});
  return {status: report.status, originalScreenCount: originals.length, addedScreenCount: added.length,
    correctedNodeIds: corrected, prototypePlaybackVerified: false,
    screens: report.screens.map(s => ({id: s.id, name: s.name, nodeCount: s.nodeCount, fonts: s.fonts}))};
}

// 本地插件入口；先只读检查，点击按钮后才写入当前 Figma 文件。
figma.showUI(__html__, {width: 480, height: 560});
let busy = false, started = false;
figma.ui.onmessage = async message => {
  // 等界面消息监听器就绪后再检查，避免早期结果和错误被丢失。
  if (message.type === 'ui-ready' && !started) {
    started = true;
    startMiniPlugin().catch(error => figma.ui.postMessage({type: 'error', text: String(error.message || error)}));
    return;
  }
  if (message.type !== 'apply' || busy) return;
  busy = true;
  try {
    const report = await repairMiniProgram(true);
    figma.ui.postMessage({type: 'result', report});
  } catch (error) {
    figma.ui.postMessage({type: 'error', text: String(error.message || error)});
  }
};
// 已有修正屏时进入检查导出，不重复创建页面。
async function startMiniPlugin() {
  figma.ui.postMessage({type: 'progress', text: '界面已就绪，正在读取当前小程序页面…'});
  const page = await figma.getNodeByIdAsync('4:2');
  if (page.children.some(n => n.name.startsWith('小程序 / 修正 / '))) {
    busy = true;
    figma.ui.postMessage({type: 'result', report: await reviewMiniProgram()});
  } else {
    figma.ui.postMessage({type: 'ready', report: await repairMiniProgram(false)});
  }
}
