import { defineConfig, devices } from '@playwright/test';

/**
 * 端到端测试配置。
 *
 * <p>为什么 webServer 只起买家端：管理后台的用例需要先登录（要真后端），
 * 当前 e2e 只覆盖"不需要后端也能验证"的渲染与路由守卫，所以只起一个 dev server，
 * 在 CI 里也能跑，不依赖 MySQL / Redis / Nacos。
 *
 * <p>reuseExistingServer：本地开发时如果已经 `npm run dev:buyer`，直接复用那个实例，
 * 避免 Playwright 再起一个端口冲突（vite 配置了 strictPort）。
 */
export default defineConfig({
  testDir: './e2e',
  // 单个用例 30 秒足够：都是页面渲染，没有真实业务等待。
  timeout: 30_000,
  fullyParallel: true,
  // CI 环境禁止 test.only，避免误提交导致其余用例被跳过。
  forbidOnly: !!process.env.CI,
  // 本地失败不重试（方便看真实报错），CI 重试一次以过滤偶发抖动。
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://127.0.0.1:5173',
    // 只在失败重试时保留 trace，避免每次运行都产生大文件。
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
      // 手机视口用例只在 mobile 项目里跑，否则会被执行两遍。
      testIgnore: /mobile\.spec\.ts/,
    },
    {
      // 买家端有大量移动端布局，单列一个手机视口验证关键页面不溢出。
      // 显式指定 chromium：devices['iPhone 13'] 默认 browserName 是 webkit，
      // 而 CI 只安装了 chromium，不指定会报 "Executable doesn't exist ... webkit"。
      // 用 chromium + iPhone 的视口与 UA 足够验证布局，省下一次浏览器下载。
      name: 'mobile',
      use: { ...devices['iPhone 13'], browserName: 'chromium' },
      testMatch: /mobile\.spec\.ts/,
    },
  ],
  webServer: {
    command: 'npm run dev:buyer',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    // dev server 的 vite 日志不阻塞测试，但失败时要能看到编译错误。
    stdout: 'pipe',
    stderr: 'pipe',
  },
});
