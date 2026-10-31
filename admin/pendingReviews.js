import { computed, reactive } from 'vue'
import { platformManagementApi } from '../src/services/platformManagement.js'
import { allianceAdminApi } from '../src/services/allianceApi.js'
import { referralApi } from '../src/services/referralApi.js'

/**
 * 需要平台处理的事项数量，复用各页面已有的统计接口。读不到的一项保持 null，不影响其他项。
 * 管理后台打开时每分钟刷新一次，切换页面和窗口重新获得焦点时也会刷新。
 */
export const pending = reactive({ information: null, merchants: null, offers: null, disputes: null, accounts: null, statements: null, withdrawals: null })

export const pendingItems = computed(() => [
  { key: 'information', label: '待审核信息', route: 'admin-information' },
  { key: 'merchants', label: '待审核入驻', route: 'admin-merchants' },
  { key: 'offers', label: '待审核供券', route: 'admin-alliance' },
  { key: 'disputes', label: '待处理申诉', route: 'admin-alliance' },
  { key: 'accounts', label: '待核对收款账号', route: 'admin-alliance' },
  { key: 'statements', label: '有异议的对账单', route: 'admin-alliance' },
  { key: 'withdrawals', label: '待审核提现', route: 'admin-referral' },
].map(item => ({ ...item, count: pending[item.key] || 0 })))

export const pendingTotal = computed(() => pendingItems.value.reduce((sum, item) => sum + item.count, 0))

/** 侧栏每个入口的待处理数。 */
export const pendingByRoute = computed(() => pendingItems.value.reduce((map, item) => ({ ...map, [item.route]: (map[item.route] || 0) + item.count }), {}))

let running = null
export async function refreshPending() {
  if (running) return running
  running = (async () => {
    const [summary, alliance, accounts, statements, withdrawals] = await Promise.allSettled([
      platformManagementApi.summary(),
      allianceAdminApi.summary(),
      allianceAdminApi.accounts('PENDING_VERIFY'),
      allianceAdminApi.statements({ status: 'OBJECTED' }),
      referralApi.withdrawals({ status: 'PENDING', size: 1 }),
    ])
    if (summary.status === 'fulfilled') Object.assign(pending, { information: summary.value.pendingInformation, merchants: summary.value.pendingMerchants })
    if (alliance.status === 'fulfilled') Object.assign(pending, { offers: alliance.value.pendingOffers, disputes: alliance.value.openDisputes })
    if (accounts.status === 'fulfilled') pending.accounts = Array.isArray(accounts.value) ? accounts.value.length : null
    if (statements.status === 'fulfilled') pending.statements = Array.isArray(statements.value) ? statements.value.length : null
    if (withdrawals.status === 'fulfilled') pending.withdrawals = withdrawals.value?.pendingCount ?? null
  })().finally(() => { running = null })
  return running
}

let timer = null
const onFocus = () => { if (document.visibilityState === 'visible') refreshPending() }
export function startPendingPolling() {
  if (timer) return
  refreshPending()
  timer = setInterval(() => { if (document.visibilityState === 'visible') refreshPending() }, 60000)
  document.addEventListener('visibilitychange', onFocus)
}
export function stopPendingPolling() {
  clearInterval(timer)
  timer = null
  document.removeEventListener('visibilitychange', onFocus)
}
