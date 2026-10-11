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
