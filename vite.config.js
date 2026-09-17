import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  server: {
    host: '0.0.0.0',
    port: 4173,
    allowedHosts: ['sunday-discipline-mens-meat.trycloudflare.com'],
    // 开发环境把 /api 转发到本地后端，前端不需要配 VITE_API_BASE_URL，也没有跨域问题
    proxy: {
      '/api': {
        target: process.env.VITE_DEV_API_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    target: 'es2020',
  },
})
