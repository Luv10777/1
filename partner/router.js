import { createRouter, createWebHistory } from 'vue-router'
import { auth } from '../src/stores/auth.js'
import { isOwner, loadPartner, partner } from './partnerStore.js'
import LoginView from './views/LoginView.vue'
import AccessView from './views/AccessView.vue'

const OverviewView = () => import('./views/OverviewView.vue')
const TenantsView = () => import('./views/TenantsView.vue')
const CodesView = () => import('./views/CodesView.vue')
const TeamView = () => import('./views/TeamView.vue')
const DashboardView = () => import('./views/DashboardView.vue')
const DistributionsView = () => import('./views/DistributionsView.vue')

const PAGES = ['/overview', '/dashboard', '/tenants', '/codes', '/team', '/distributions']
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
      { path: '/overview', name: 'partner-overview', component: OverviewView, meta: { title: '工作台', partner: true } },
      { path: '/dashboard', name: 'partner-dashboard', component: DashboardView, meta: { title: '经营看板', partner: true } },
      { path: '/tenants', name: 'partner-tenants', component: TenantsView, meta: { title: '名下商户', partner: true } },
      { path: '/codes', name: 'partner-codes', component: CodesView, meta: { title: '激活码', partner: true } },
      { path: '/team', name: 'partner-team', component: TeamView, meta: { title: '团队', partner: true, owner: true } },
      { path: '/distributions', name: 'partner-distributions', component: DistributionsView, meta: { title: '分配记录', partner: true, owner: true } },
      { path: '/403', name: 'partner-forbidden', component: AccessView, meta: { title: '不是服务商账号' } },
      { path: '/:pathMatch(.*)*', name: 'partner-not-found', component: AccessView, meta: { title: '页面不存在' } },
    ],
    scrollBehavior: () => ({ top: 0 }),
  })
  instance.beforeEach(async to => {
    if (!to.meta.partner) return true
    if (!auth.isAuthenticated) return { name: 'partner-login', query: { redirect: to.fullPath } }
    // 团队页只给负责人；BD 打开时回到概览。
    if (await loadPartner()) return to.meta.owner && !isOwner() ? { name: 'partner-overview' } : true
    if (!auth.isAuthenticated) return { name: 'partner-login', query: { redirect: to.fullPath } }
    return { name: 'partner-forbidden', query: partner.error ? { reason: partner.error } : {} }
  })
  instance.afterEach(to => {
    if (typeof document !== 'undefined') document.title = `${partner.overview?.partner.name || '服务商后台'} · ${to.meta.title || '一方志'}`
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
