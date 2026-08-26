import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { theme } from './stores/theme'
import './style.css'
import './creative.css'
import './consumer.css'
import './billing.css'
import './ecosystem.css'

theme.apply()

createApp(App).use(router).mount('#app')
