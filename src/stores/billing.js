import { reactive, watch } from 'vue'
import { auth } from './auth'
import { get, post, membershipRequired } from '../utils/request'

export const billing = reactive({
  summary: null,
  plans: [],
  loading: false,
  error: '',
})

let pending = null
let scope = 0

watch(() => [auth.user?.id, auth.tenantId], () => {
  scope += 1
  pending = null
  billing.summary = null
  billing.plans = []
  billing.error = ''
  billing.loading = false
}, { flush: 'sync' })

export async function refreshBilling() {
  if (!auth.isAuthenticated) return null
  if (pending) return pending
  const current = scope
  billing.loading = true
  pending = get('/api/billing/summary').then(data => {
    if (current === scope) { billing.summary = data; billing.error = '' }
    return current === scope ? data : null
  }).catch(error => {
    if (current === scope) billing.error = error.message || '账户暂时无法读取'
    return null
  }).finally(() => {
    if (current === scope) { billing.loading = false; pending = null }
  })
  return pending
}

export async function loadBillingPlans() {
  const current = scope
  const data = await get('/api/billing/plans')
  if (current === scope) billing.plans = data
  return data
}

export async function requireMembership() {
  if (!auth.requireLogin()) throw new Error('请先登录后开始创作')
  const account = await refreshBilling()
  if (!account) throw new Error(billing.error || '暂时无法确认会员状态，请稍后重试')
  if (!account.memberActive) throw membershipRequired()
  return account
}

export const billingApi = {
  topUp: data => post('/api/billing/orders/topup', data),
  subscribe: data => post('/api/billing/orders/subscription', data),
  order: id => get('/api/billing/orders/' + id),
  refresh: id => post('/api/billing/orders/' + id + '/refresh', {}),
  cancel: id => post('/api/billing/orders/' + id + '/cancel', {}),
  alipay: id => post('/api/billing/orders/' + id + '/alipay', {}),
  orders: (page = 0) => get('/api/billing/orders', { page, size: 20 }),
  topups: (page = 0) => get('/api/billing/topups', { page, size: 20 }),
  ledger: (page = 0) => get('/api/billing/ledger', { page, size: 20 }),
  operatorPlans: () => get('/api/billing/operations/plans'),
  createPlan: data => post('/api/billing/operations/plans', data),
  publish: (id, published) => post('/api/billing/operations/plans/' + id + '/publication', { published }),
}

export function number(value) {
  return new Intl.NumberFormat('zh-CN').format(value)
}
export function yuan(minor) {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(minor / 100)
}
export function dateTime(value) {
  if (!value) return ''
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value))
}

