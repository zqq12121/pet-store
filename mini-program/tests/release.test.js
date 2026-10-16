const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { spawnSync } = require('node:child_process')
const { releaseOrigin, prepareRelease } = require('../scripts/prepare-release')

test('正式包拒绝 HTTP、IP、占位域名及带路径或凭据的地址', () => {
  for (const value of ['http://shop.warmpaw.cn', 'https://127.0.0.1', 'https://[::1]', 'https://localhost', 'https://shop.local', 'https://example.com', 'https://shop.example.com', 'https://shop.test', 'https://shop.warmpaw.cn/api', 'https://user:secret@shop.warmpaw.cn', 'https://shop.warmpaw.cn?token=private']) {
    assert.throws(() => releaseOrigin(value))
  }
  assert.equal(releaseOrigin('https://shop.warmpaw.cn/'), 'https://shop.warmpaw.cn')
})

test('发布包使用真实配置且不含测试、旧业务与私有配置，开发目录不变', () => {
  const output = fs.mkdtempSync(path.join(os.tmpdir(), 'warmpaw-release-test-'))
  const source = path.resolve(__dirname, '..')
  const before = fs.readFileSync(path.join(source, 'config.js'), 'utf8')
  try {
    // 合成地址只验证打包行为，不调用外网，也不作为可上线产物交付。
    const target = prepareRelease(source, output, 'https://shop.warmpaw.cn')
    const config = require(path.join(target, 'config.js'))
    assert.deepEqual(config, { demo: false, apiBase: 'https://shop.warmpaw.cn/api/v1', assetOrigin: 'https://shop.warmpaw.cn' })
    for (const item of ['.env', 'tests', 'scripts', 'pages/booking', 'pages/ai', 'pages/shop', 'utils/sse.js']) assert.equal(fs.existsSync(path.join(target, item)), false)
    for (const page of require('../app.json').pages) for (const ext of ['js', 'json', 'wxml', 'wxss']) assert.ok(fs.existsSync(path.join(target, page + '.' + ext)))
    assert.notEqual(prepareRelease(source, output, 'https://shop.warmpaw.cn'), target)
    assert.equal(fs.readFileSync(path.join(source, 'config.js'), 'utf8'), before)
    // 编译实际生成的代码包，检查静态依赖、模板和导航文件是否完整。
    const check = spawnSync(process.execPath, [path.join(source, 'scripts/check.js'), target], { encoding: 'utf8' })
    assert.equal(check.status, 0, check.stdout + check.stderr)
  } finally { fs.rmSync(output, { recursive: true, force: true }) }
})
