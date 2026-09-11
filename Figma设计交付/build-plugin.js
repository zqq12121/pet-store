// 将经过检查的静态页面数据打包，不下载或执行第三方代码。
const fs=require('fs'),path=require('path'),dir=__dirname;
const data=fs.readFileSync(path.join(dir,'design.json'),'utf8');
const layout=fs.readFileSync(path.join(dir,'layout.js'),'utf8').replace(/if\(typeof module[^\n]+/,'');
const runtime=fs.readFileSync(path.join(dir,'plugin-runtime.js'),'utf8');
fs.writeFileSync(path.join(dir,'code.js'),'const DATA='+data+';\n'+layout+'\n'+runtime);
console.log('code.js 已打包；未在 Figma 执行。');
