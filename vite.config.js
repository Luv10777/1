import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { sourceLocator, uiEditPlugin } from './dev/ui-edit/vitePlugin.js'

export default defineConfig(({ command, mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const nodeTransforms = command === 'serve' ? [sourceLocator(process.cwd())] : []
  return {
  plugins: [vue({ template: { compilerOptions: { nodeTransforms } } }), tailwindcss(), uiEditPlugin()],
  server: {
    host: '0.0.0.0',
    port: 4173,
    allowedHosts: ['sunday-discipline-mens-meat.trycloudflare.com'],
    // 开发环境把 /api 转发到本地后端，前端不需要配 VITE_API_BASE_URL，也没有跨域问题
    proxy: {
      '/api': {
        target: process.env.VITE_DEV_API_TARGET || env.VITE_DEV_API_TARGET || 'http://localhost:18080',
        changeOrigin: true,
        ws: true,
      },
      // 服务商后台由 npm run dev:partner 在 4178 提供；转过来以后和一方志同一个地址，登录是同一个。
      '/partner': {
        target: 'http://127.0.0.1:4178',
        ws: true,
      },
    },
  },
  build: {
    target: 'es2020',
  },
  }
})
