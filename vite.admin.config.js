import { fileURLToPath } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

const projectRoot = fileURLToPath(new URL('.', import.meta.url))

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, projectRoot, '')
  const proxy = {
    '/api': {
      target: process.env.VITE_DEV_API_TARGET || env.VITE_DEV_API_TARGET || 'http://127.0.0.1:18080',
      changeOrigin: true,
    },
  }
  return {
    root: fileURLToPath(new URL('./admin', import.meta.url)),
    envDir: projectRoot,
    cacheDir: fileURLToPath(new URL('./node_modules/.vite-admin', import.meta.url)),
    publicDir: false,
    plugins: [vue()],
    optimizeDeps: { include: ['vue', 'vue-router', 'lucide-vue-next', '@headlessui/vue'] },
    define: { 'import.meta.env.VITE_APP_CONTEXT': JSON.stringify('admin') },
    server: {
      host: '127.0.0.1', port: 4177, strictPort: true,
      fs: { allow: [projectRoot] },
      proxy,
      headers: { 'X-Robots-Tag': 'noindex, nofollow', 'Referrer-Policy': 'no-referrer' },
    },
    preview: { host: '127.0.0.1', port: 4177, strictPort: true, proxy },
    build: { outDir: '../dist-admin', emptyOutDir: true, target: 'es2020' },
  }
})
