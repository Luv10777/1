import { createRouter, createWebHistory } from 'vue-router'
import { auth } from '../src/stores/auth.js'
import { loadPlatformAccess, platform } from '../src/stores/platform.js'
import LoginView from './views/LoginView.vue'
import AccessView from './views/AccessView.vue'

const WorkbenchView = () => import('./views/WorkbenchView.vue')
const ReviewView = () => import('./views/ReviewView.vue')

const ManagementView = () => import('../src/views/PlatformManagementView.vue')
const AllianceView = () => import('../src/views/PlatformAllianceView.vue')
const PlansView = () => import('../src/views/PlatformPlansView.vue')
const UsersView = () => import('../src/views/PlatformUsersView.vue')
const CreditsView = () => import('../src/views/PlatformCreditsView.vue')
const ReferralView = () => import('../src/views/PlatformReferralView.vue')
const CreditOverviewView = () => import('../src/views/PlatformCreditOverviewView.vue')
const RedeemCodesView = () => import('../src/views/PlatformRedeemCodesView.vue')

export function adminDestination(raw) {
  if (typeof raw !== 'string') return '/workbench'
  const path = raw.split(/[?#]/)[0]
  return ['/workbench', '/review', '/information', '/merchants', '/users', '/alliance', '/plans', '/credits', '/credit-overview', '/redeem-codes', '/referral'].includes(path) ? raw : '/workbench'
}

export function createAdminRouter(history = createWebHistory()) {
  const instance = createRouter({
    history,
    routes: [
      { path: '/', redirect: '/workbench' },
      { path: '/login', name: 'admin-login', component: LoginView, meta: { title: '管理员登录' } },
      { path: '/workbench', name: 'admin-home', component: WorkbenchView, meta: { title: '工作台', admin: true } },
      { path: '/review', name: 'admin-review', component: ReviewView, meta: { title: '审核中心', admin: true } },
      { path: '/information', alias: '/admin/information', name: 'admin-information', component: ManagementView, meta: { title: '信息管理', admin: true } },
      { path: '/merchants', name: 'admin-merchants', component: ManagementView, meta: { title: '商家管理', admin: true } },
      // 实名已并入用户管理，旧地址跳过去。
      { path: '/identities', alias: ['/admin/identities', '/admin/real-name'], redirect: '/users' },
      { path: '/credit-overview', name: 'admin-credit-overview', component: CreditOverviewView, meta: { title: '烟火看板', admin: true } },
      { path: '/credits', name: 'admin-credits', component: CreditsView, meta: { title: '烟火日志', admin: true } },
      { path: '/redeem-codes', name: 'admin-redeem-codes', component: RedeemCodesView, meta: { title: '兑换码', admin: true } },
      { path: '/referral', name: 'admin-referral', component: ReferralView, meta: { title: '邀请返利', admin: true } },
      { path: '/users', name: 'admin-users', component: UsersView, meta: { title: '用户管理', admin: true } },
      { path: '/plans', name: 'admin-plans', component: PlansView, meta: { title: '会员套餐', admin: true } },
      { path: '/alliance', name: 'admin-alliance', component: AllianceView, meta: { title: '联盟券', admin: true } },
      { path: '/403', name: 'admin-forbidden', component: AccessView, meta: { title: '未获授权' } },
      { path: '/unavailable', name: 'admin-unavailable', component: AccessView, meta: { title: '连接暂不可用' } },
      { path: '/:pathMatch(.*)*', name: 'admin-not-found', component: AccessView, meta: { title: '页面不存在' } },
    ],
    scrollBehavior: () => ({ top: 0 }),
  })
  instance.beforeEach(async to => {
    if (!to.meta.admin) return true
    if (!auth.isAuthenticated) return { name: 'admin-login', query: { redirect: to.fullPath } }
    if (await loadPlatformAccess()) return true
    if (!auth.isAuthenticated) return { name: 'admin-login', query: { redirect: to.fullPath } }
    return platform.error
      ? { name: 'admin-unavailable', query: { redirect: to.fullPath } }
      : { name: 'admin-forbidden' }
  })
  instance.afterEach(to => {
    if (typeof document !== 'undefined') document.title = `管理后台 · ${to.meta.title || '运营管理'}`
  })
  return instance
}

const router = createAdminRouter()

export function handleAdminAuthExpired() {
  const current = router.currentRoute.value
  auth.clearSession()
  if (!current.matched.length || current.name === 'admin-login') return
  return router.replace({ name: 'admin-login', query: { redirect: adminDestination(current.fullPath) } })
}

export default router
