import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { theme } from './stores/theme'
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
import './studio.css'
import './live-studio.css'

theme.apply()

createApp(App).use(router).mount('#app')
