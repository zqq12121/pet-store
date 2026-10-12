const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const { spawnSync } = require('node:child_process')
const root = path.resolve(__dirname, '..')
const walk = dir => fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => e.name === 'validation' ? [] : e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)])
const files = walk(root), app = JSON.parse(fs.readFileSync(path.join(root, 'app.json')))
for (const page of app.pages) for (const ext of ['js', 'json', 'wxml', 'wxss']) if (!fs.existsSync(path.join(root, page + '.' + ext))) throw new Error('页面文件缺失：' + page + '.' + ext)
for (const f of files.filter(f => f.endsWith('.json'))) JSON.parse(fs.readFileSync(f))
for (const f of files.filter(f => f.endsWith('.js'))) { const r = spawnSync(process.execPath, ['--check', f]); if (r.status) throw new Error(r.stderr.toString()) }
for (const tab of app.tabBar.list) for (const key of ['iconPath', 'selectedIconPath']) if (!fs.statSync(path.join(root, tab[key])).size) throw new Error('导航图标为空')
// 优先使用本机开发者工具的实际 WXML/WXSS 编译器，不以字符串扫描代替编译。
const bin = '/Applications/wechatwebdevtools.app/Contents/Resources/app.asar.unpacked/node_modules/wcc-exec'
// 编译产物不是可编辑源码，放进系统临时目录，避免编辑器报告数百条假错误。
const output = fs.mkdtempSync(path.join(os.tmpdir(), 'rongrong-compile-'))
try {
  for (const [tool, ext] of [['wcc', '.wxml'], ['wcsc', '.wxss']]) {
    if (!fs.existsSync(path.join(bin, tool))) { console.log(tool + ' 未安装；跳过本机编译器检查'); continue }
    const inputs = files.filter(f => f.endsWith(ext)).map(f => path.relative(root, f))
    const r = spawnSync(path.join(bin, tool), ['-o', path.join(output, tool + '.txt'), ...inputs], { cwd: root, encoding: 'utf8' })
    if (r.status) throw new Error(r.stderr || r.stdout)
    console.log(tool + ' 编译通过：' + inputs.length + ' 个文件')
  }
} finally { fs.rmSync(output, { recursive: true, force: true }) }
console.log('JavaScript、JSON、页面注册与导航资源检查通过：' + app.pages.length + ' 个页面')
