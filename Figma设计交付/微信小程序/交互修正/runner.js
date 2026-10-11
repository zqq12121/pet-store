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
