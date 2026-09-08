import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'
const root = fileURLToPath(new URL('..', import.meta.url))
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, root, '')
  // 两个应用分别预构建依赖，避免并行启动时相互覆盖缓存。
  return { cacheDir: `${root}/node_modules/.vite-admin`, root: `${root}/admin-web`, envDir: root, base: env.VITE_ADMIN_BASE || '/', publicDir: `${root}/public`, plugins: [vue()], server: { port: 5174, strictPort: true, proxy: { '/api': env.VITE_API_TARGET || 'http://127.0.0.1:8787', '/demo-files': 'http://127.0.0.1:8787' } }, build: { outDir: '../dist/admin', emptyOutDir: true } }
})
