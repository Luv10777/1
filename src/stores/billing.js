import { reactive } from 'vue'
import { BILLING_INTERVALS, PLAN_CODES, SUBSCRIPTION_STATES, createPlanVersion, createSaasBillingEngine } from '../domain/billing.js'

const plans = [
  createPlanVersion({ id: 'plan_trial_v1', planCode: PLAN_CODES.TRIAL, version: '2026-08-25.v1', displayName: '试用航线', prices: [{ interval: BILLING_INTERVALS.MONTH, amountMinor: 0 }], entitlements: { AI_IMAGE: { enabled: true, limit: 30 }, AI_VIDEO: { enabled: true, limit: 2 }, STORES: { enabled: true, limit: 1 }, AUTOMATION: { enabled: false, limit: 0 } } }),
  createPlanVersion({ id: 'plan_growth_v1', planCode: PLAN_CODES.GROWTH, version: '2026-08-25.v1', displayName: '增长版', prices: [{ interval: BILLING_INTERVALS.MONTH, amountMinor: 29900 }], entitlements: { AI_IMAGE: { enabled: true, limit: 300 }, AI_VIDEO: { enabled: true, limit: 20 }, STORES: { enabled: true, limit: 5 }, AUTOMATION: { enabled: true, limit: 8 } } }),
  createPlanVersion({ id: 'plan_enterprise_v1', planCode: PLAN_CODES.ENTERPRISE, version: '2026-08-25.v1', displayName: '企业版', prices: [{ interval: BILLING_INTERVALS.MONTH, amountMinor: 99900 }], entitlements: { AI_IMAGE: { enabled: true, limit: 2000 }, AI_VIDEO: { enabled: true, limit: 100 }, STORES: { enabled: true, limit: 30 }, AUTOMATION: { enabled: true, limit: 50 } } }),
]

const engine = createSaasBillingEngine({ plans })
const subscription = engine.subscribe({ tenantId: 'tenant_demo_001', planVersionId: 'plan_growth_v1', state: SUBSCRIPTION_STATES.ACTIVE, renewsAt: '2026-09-25T00:00:00.000Z' })
const account = [...engine.accounts.values()][0]
engine.ledger.push({ id: 'grant_growth_month', accountId: account.id, tenantId: account.tenantId, type: 'GRANT', amount: 480, reason: '增长版月度点数' })

export const billing = reactive({
  engine,
  plans,
  subscription,
  account,
  selectedCode: 'AI_IMAGE',
  message: '',
  usage: { AI_IMAGE: 126, AI_VIDEO: 7, STORES: 2, AUTOMATION: 3 },
  get currentPlan() { return this.subscription.planSnapshot },
  get balance() { return engine.ledger.filter((entry) => entry.accountId === this.account.id).reduce((sum, entry) => sum + entry.amount, 0) },
  get ledgerEntries() { return engine.ledger.slice(-5).reverse() },
  getEntitlement(code) {
    const rule = this.currentPlan.entitlements[code]
    const used = this.usage[code] || 0
    return { ...rule, used, remaining: Math.max(0, (rule?.limit ?? 0) - used), source: 'PLAN_VERSION' }
  },
  requestUpgrade(plan) {
    this.message = `${plan.displayName} 已生成升级咨询草稿，待商家确认后创建 SaaS 订单。`
  },
  simulateUsage(code, quantity = 1) {
    const result = engine.getEntitlement(this.subscription.id, code, quantity)
    if (!result.allowed) { this.message = `${code} 已达到本周期权益上限`; return }
    this.usage[code] = (this.usage[code] || 0) + quantity
    this.message = `已记录 ${quantity} 个 ${code} 用量事件（Mock）`
  },
})

