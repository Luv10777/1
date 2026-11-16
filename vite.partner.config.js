import { fileURLToPath } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

const projectRoot = fileURLToPath(new URL('.', import.meta.url))

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, projectRoot, '')
  const proxy = {
    '/api': {
      target: process.env.VITE_DEV_API_TARGET || env.VITE_DEV_API_TARGET || 'http://127.0.0.1:18080',
      // 保留页面 Host，让同源请求通过后端的 Origin 校验。
      changeOrigin: false,
    },
  }
  return {
    root: fileURLToPath(new URL('./partner', import.meta.url)),
    envDir: projectRoot,
    cacheDir: fileURLToPath(new URL('./node_modules/.vite-partner', import.meta.url)),
    publicDir: false,
    plugins: [vue()],
    optimizeDeps: { include: ['vue', 'vue-router', 'lucide-vue-next', '@headlessui/vue'] },
    // 服务商后台挂在一方志站的 /partner/ 下，和一方志共用登录（不设 VITE_APP_CONTEXT，令牌存在同一处）。
    base: '/partner/',
    server: {
      host: '127.0.0.1', port: 4178, strictPort: true,
      fs: { allow: [projectRoot] },
      proxy,
      headers: { 'X-Robots-Tag': 'noindex, nofollow', 'Referrer-Policy': 'no-referrer' },
    },
    preview: { host: '127.0.0.1', port: 4178, strictPort: true, proxy },
    // 构建进一方志站的 dist/partner/，和站点一起发布；先跑主站构建（它会清空 dist/）。
    build: { outDir: '../dist/partner', emptyOutDir: true, target: 'es2020' },
  }
})
