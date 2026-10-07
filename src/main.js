import './utils/crypto-polyfill.js'
import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { theme } from './stores/theme'
import { auth } from './stores/auth'
import './style.css'
import './creative.css'
import './image.css'
import './poster-studio.css'
import './product-set.css'
import './consumer.css'
import './billing.css'
import './ecosystem.css'
import './video-create.css'
import './video-workbench.css'
import './video-analyze.css'
import './studio.css'
import './live-studio.css'
import './publishing.css'
import './brand.css'
import './publishing-workspace.css'

theme.apply()

window.addEventListener('auth-expired', () => {
  auth.clearSession()
  if (router.currentRoute.value.name !== 'login') router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
})

async function startApp() {
  if (auth.isAuthenticated) await auth.restore()
  createApp(App).use(router).mount('#app')
}
startApp()
