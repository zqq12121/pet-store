import { configDefaults, defineConfig } from 'vitest/config';

// 单元测试（vitest）与端到端测试（Playwright）的用例文件都叫 *.spec/*.test，
// 不做排除的话 vitest 会把 e2e/ 下的 Playwright 用例当单测收集，直接报错。
export default defineConfig({
  test: {
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
});
