import '../src/utils/crypto-polyfill.js'
import { createApp } from 'vue'
import App from './App.vue'
import router, { handlePartnerAuthExpired } from './router.js'
import { auth } from '../src/stores/auth.js'
import './style.css'

window.addEventListener('auth-expired', handlePartnerAuthExpired)
window.addEventListener('login-required', handlePartnerAuthExpired)

async function start() {
  if (auth.isAuthenticated) await auth.restore()
  createApp(App).use(router).mount('#app')
}
start()
