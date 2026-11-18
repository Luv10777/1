import { createRouter, createWebHistory } from 'vue-router'
import { auth } from '../src/stores/auth.js'
import { can, loadPartner, partner } from './partnerStore.js'
import AccessView from './views/AccessView.vue'

const OverviewView = () => import('./views/OverviewView.vue')
const TenantsView = () => import('./views/TenantsView.vue')
const CodesView = () => import('./views/CodesView.vue')
const TeamView = () => import('./views/TeamView.vue')
const DashboardView = () => import('./views/DashboardView.vue')
const DistributionsView = () => import('./views/DistributionsView.vue')
const TaskLogsView = () => import('./views/TaskLogsView.vue')

/** 服务商后台挂在一方志站的 /partner/ 下，和一方志共用登录。 */
export const PARTNER_BASE = '/partner/'
const PAGES = ['/overview', '/dashboard', '/tenants', '/codes', '/team', '/credits', '/task-logs']
export function partnerDestination(raw) {
  if (typeof raw !== 'string') return '/overview'
  return PAGES.includes(raw.split(/[?#]/)[0]) ? raw : '/overview'
}

/** 没登录时去一方志的登录页，登录后回到服务商后台的这一页。 */
export function loginUrl(path = '/overview') {
  return `/login?redirect=${encodeURIComponent(PARTNER_BASE.slice(0, -1) + partnerDestination(path))}`
}

export function createPartnerRouter(history = createWebHistory(PARTNER_BASE)) {
  const instance = createRouter({
    history,
    routes: [
      { path: '/', redirect: '/overview' },
      { path: '/overview', name: 'partner-overview', component: OverviewView, meta: { title: '工作台', partner: true } },
      { path: '/dashboard', name: 'partner-dashboard', component: DashboardView, meta: { title: '经营看板', partner: true, permission: 'tenants' } },
      { path: '/tenants', name: 'partner-tenants', component: TenantsView, meta: { title: '名下商户', partner: true, permission: 'tenants' } },
      { path: '/codes', name: 'partner-codes', component: CodesView, meta: { title: '激活码', partner: true, permission: 'codes' } },
      { path: '/credits', name: 'partner-credits', component: DistributionsView, meta: { title: '烟火', partner: true, permission: ['finance', 'distribute'] } },
      { path: '/distributions', redirect: '/credits' },
      { path: '/task-logs', name: 'partner-task-logs', component: TaskLogsView, meta: { title: '任务日志', partner: true } },
      { path: '/team', name: 'partner-team', component: TeamView, meta: { title: '团队', partner: true } },
      { path: '/403', name: 'partner-forbidden', component: AccessView, meta: { title: '不是服务商成员' } },
      { path: '/:pathMatch(.*)*', name: 'partner-not-found', component: AccessView, meta: { title: '页面不存在' } },
    ],
    scrollBehavior: () => ({ top: 0 }),
  })
  instance.beforeEach(async to => {
    if (!to.meta.partner) return true
    if (!auth.isAuthenticated) { window.location.assign(loginUrl(to.fullPath)); return false }
    // 没有这项权限的页面回到工作台（入口本来就不显示，这里防止直接输地址）。
    if (await loadPartner()) return !to.meta.permission || [].concat(to.meta.permission).some(can) ? true : { name: 'partner-overview' }
    if (!auth.isAuthenticated) { window.location.assign(loginUrl(to.fullPath)); return false }
    return { name: 'partner-forbidden', query: partner.error ? { reason: partner.error } : {} }
  })
  instance.afterEach(to => {
    if (typeof document !== 'undefined') document.title = `${partner.overview?.partner.name || '服务商后台'} · ${to.meta.title || '一方志'}`
  })
  return instance
}

const router = createPartnerRouter()

/** 登录过期：清掉登录状态，回一方志登录页，登录后回到这一页。 */
export function handlePartnerAuthExpired() {
  const current = router.currentRoute.value
  auth.clearSession()
  window.location.assign(loginUrl(current.matched.length ? current.fullPath : '/overview'))
}

export default router
