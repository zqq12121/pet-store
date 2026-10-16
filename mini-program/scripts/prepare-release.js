// 单独生成真实模式代码包，保留开发目录的演示配置；不读取或打包服务端密钥。
const fs = require('node:fs')
const path = require('node:path')
const net = require('node:net')

function releaseOrigin(value) {
  const url = new URL(value)
  const host = url.hostname.toLowerCase()
  if (url.protocol !== 'https:' || url.username || url.password || url.port || url.pathname !== '/' || url.search || url.hash
    || net.isIP(host) || !/^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/.test(host)
    || /(^|\.)(localhost|local|invalid|test|example)$/.test(host) || /(^|\.)example\.(com|org|net)$/.test(host)) {
    throw new Error('请提供真实 HTTPS 域名源站，不含路径、端口、账号或 IP 地址')
  }
  return url.origin
}

function prepareRelease(source, output, origin) {
  const base = releaseOrigin(origin)
  const app = JSON.parse(fs.readFileSync(path.join(source, 'app.json')))
  const project = JSON.parse(fs.readFileSync(path.join(source, 'project.config.json')))
  if (!/^wx[0-9a-fA-F]{16}$/.test(project.appid) || !project.setting.urlCheck) throw new Error('请配置有效 AppID 并开启合法域名校验')
  const pages = ['home', 'pets', 'favorites', 'my', 'detail', 'login', 'web-login'].map(name => 'pages/' + name + '/index')
  if (app.pages.length !== pages.length || pages.some(p => !app.pages.includes(p))) throw new Error('上线包仅允许六个业务页面及网站登录确认页')
  // 使用全新目录，既不覆盖历史发布包，也不会删除用户已有文件。
  fs.mkdirSync(output, { recursive: true })
  const target = fs.mkdtempSync(path.join(output, 'mini-program-release-'))
  for (const name of ['app.js', 'app.json', 'app.wxss', 'project.config.json', 'sitemap.json', 'assets', 'components']) {
    fs.cpSync(path.join(source, name), path.join(target, name), { recursive: true })
  }
  fs.mkdirSync(path.join(target, 'utils'))
  // demo.js 是 api.js 的静态依赖，但真实包配置固定为 false，失败也不会回退演示。
  for (const name of ['api.js', 'demo.js', 'format.js', 'page.js']) fs.copyFileSync(path.join(source, 'utils', name), path.join(target, 'utils', name))
  for (const page of pages) {
    fs.mkdirSync(path.dirname(path.join(target, page)), { recursive: true })
    for (const ext of ['js', 'json', 'wxml', 'wxss']) fs.copyFileSync(path.join(source, page + '.' + ext), path.join(target, page + '.' + ext))
  }
  fs.writeFileSync(path.join(target, 'config.js'), '// 发布包只使用真实 HTTPS 接口，不包含 AppSecret。\nmodule.exports = ' + JSON.stringify({ demo: false, apiBase: base + '/api/v1', assetOrigin: base }, null, 2) + '\n')
  return target
}

if (require.main === module) {
  try {
    const origin = process.argv[2]
    if (!origin || process.argv.length !== 3) throw new Error('用法：npm --prefix mini-program run release -- https://你的域名')
    console.log('已生成真实接口代码包：' + prepareRelease(path.resolve(__dirname, '..'), path.resolve(__dirname, '../../output'), origin))
    console.log('仍需执行编译检查、真机验收，再上传审核；生成代码包不表示已上线。')
  } catch (e) { console.error(e.message); process.exitCode = 1 }
}
module.exports = { releaseOrigin, prepareRelease }
