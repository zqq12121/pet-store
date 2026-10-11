// 将修正函数与本地插件入口合并；打包过程不会操作 Figma。
const fs = require('fs');
const path = require('path');
const source = ['repair.js', 'review.js', 'runner.js'].map(name => fs.readFileSync(path.join(__dirname, name), 'utf8')).join('\n');
fs.writeFileSync(path.join(__dirname, 'code.js'), source);
console.log('code.js 已打包；此命令不操作 Figma，执行结果以插件报告为准。');
