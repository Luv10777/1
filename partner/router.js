import { createRouter, createWebHistory } from 'vue-router'
import { auth } from '../src/stores/auth.js'
import { loadPartner, partner } from './partnerStore.js'
import LoginView from './views/LoginView.vue'
import AccessView from './views/AccessView.vue'

const OverviewView = () => import('./views/OverviewView.vue')
const TenantsView = () => import('./views/TenantsView.vue')
const CodesView = () => import('./views/CodesView.vue')

const PAGES = ['/overview', '/tenants', '/codes']
export function partnerDestination(raw) {
  if (typeof raw !== 'string') return '/overview'
  return PAGES.includes(raw.split(/[?#]/)[0]) ? raw : '/overview'
}

export function createPartnerRouter(history = createWebHistory()) {
  const instance = createRouter({
    history,
    routes: [
      { path: '/', redirect: '/overview' },
      { path: '/login', name: 'partner-login', component: LoginView, meta: { title: '服务商登录' } },
      { path: '/overview', name: 'partner-overview', component: OverviewView, meta: { title: '概览', partner: true } },
      { path: '/tenants', name: 'partner-tenants', component: TenantsView, meta: { title: '名下商户', partner: true } },
      { path: '/codes', name: 'partner-codes', component: CodesView, meta: { title: '激活码', partner: true } },
      { path: '/403', name: 'partner-forbidden', component: AccessView, meta: { title: '不是服务商账号' } },
      { path: '/:pathMatch(.*)*', name: 'partner-not-found', component: AccessView, meta: { title: '页面不存在' } },
    ],
    scrollBehavior: () => ({ top: 0 }),
  })
  instance.beforeEach(async to => {
    if (!to.meta.partner) return true
    if (!auth.isAuthenticated) return { name: 'partner-login', query: { redirect: to.fullPath } }
    if (await loadPartner()) return true
    if (!auth.isAuthenticated) return { name: 'partner-login', query: { redirect: to.fullPath } }
    return { name: 'partner-forbidden', query: partner.error ? { reason: partner.error } : {} }
  })
  instance.afterEach(to => {
    if (typeof document !== 'undefined') document.title = `服务商后台 · ${to.meta.title || '一方志'}`
  })
  return instance
}

const router = createPartnerRouter()

export function handlePartnerAuthExpired() {
  const current = router.currentRoute.value
  auth.clearSession()
  if (!current.matched.length || current.name === 'partner-login') return
  return router.replace({ name: 'partner-login', query: { redirect: partnerDestination(current.fullPath) } })
}

export default router
