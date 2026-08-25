/**
 * Phase 4 SaaS commercial contracts.
 *
 * This module is deliberately separate from commerce.js. SaaS subscription,
 * platform credits and provider costs are not consumer order money and must be
 * persisted, reconciled and audited in different server-side tables later.
 */

export const PLAN_CODES = Object.freeze({ TRIAL: 'TRIAL', STARTER: 'STARTER', GROWTH: 'GROWTH', ENTERPRISE: 'ENTERPRISE' })
export const BILLING_INTERVALS = Object.freeze({ MONTH: 'MONTH', QUARTER: 'QUARTER', YEAR: 'YEAR' })
export const SUBSCRIPTION_STATES = Object.freeze({ INCOMPLETE: 'INCOMPLETE', TRIALING: 'TRIALING', ACTIVE: 'ACTIVE', PAST_DUE: 'PAST_DUE', GRACE_PERIOD: 'GRACE_PERIOD', SUSPENDED: 'SUSPENDED', CANCELED: 'CANCELED', EXPIRED: 'EXPIRED' })
export const CREDIT_ENTRY_TYPES = Object.freeze({ PURCHASE: 'PURCHASE', GRANT: 'GRANT', PROMOTION: 'PROMOTION', RESERVE: 'RESERVE', CAPTURE: 'CAPTURE', RELEASE: 'RELEASE', ADJUSTMENT: 'ADJUSTMENT', EXPIRE: 'EXPIRE', REFUND_REVERSAL: 'REFUND_REVERSAL' })

const ACTIVE_STATES = new Set([SUBSCRIPTION_STATES.TRIALING, SUBSCRIPTION_STATES.ACTIVE, SUBSCRIPTION_STATES.PAST_DUE, SUBSCRIPTION_STATES.GRACE_PERIOD])

function id(prefix) { return `${prefix}_${crypto.randomUUID()}` }
function now() { return new Date().toISOString() }
function freeze(value) { return Object.freeze(value) }

export function createPlanVersion(input = {}) {
  if (!input.planCode || !input.version || !input.entitlements) throw new Error('PLAN_VERSION_REQUIRED')
  return freeze({
    id: input.id || id('plan_version'),
    planCode: input.planCode,
    version: input.version,
    displayName: input.displayName || input.planCode,
    prices: freeze((input.prices || []).map((price) => freeze({ ...price, amountMinor: price.amountMinor ?? 0, currency: price.currency || 'CNY' }))),
    entitlements: freeze(Object.fromEntries(Object.entries(input.entitlements).map(([code, rule]) => [code, freeze({ ...rule })]))),
    createdAt: input.createdAt || now(),
  })
}

export function createSubscription(input = {}) {
  if (!input.tenantId || !input.planVersion?.id) throw new Error('SUBSCRIPTION_INPUT_REQUIRED')
  return {
    id: input.id || id('subscription'),
    tenantId: input.tenantId,
    planVersionId: input.planVersion.id,
    planSnapshot: input.planVersion,
    state: input.state || SUBSCRIPTION_STATES.TRIALING,
    startsAt: input.startsAt || now(),
    renewsAt: input.renewsAt || null,
    cancelAt: input.cancelAt || null,
    createdAt: now(),
    updatedAt: now(),
  }
}

export function effectiveEntitlements(subscription, overrides = []) {
  if (!subscription?.planSnapshot) throw new Error('SUBSCRIPTION_REQUIRED')
  const result = Object.fromEntries(Object.entries(subscription.planSnapshot.entitlements).map(([code, rule]) => [code, { ...rule, source: 'PLAN_VERSION' }]))
  for (const override of overrides) {
    if (!override.code || override.startsAt && new Date(override.startsAt) > new Date() || override.endsAt && new Date(override.endsAt) <= new Date()) continue
    result[override.code] = { ...(result[override.code] || {}), ...override, source: 'APPROVED_OVERRIDE' }
  }
  return result
}

export function checkEntitlement(subscription, code, quantity = 1, overrides = []) {
  const entitlements = effectiveEntitlements(subscription, overrides)
  const rule = entitlements[code]
  if (!ACTIVE_STATES.has(subscription.state)) return { allowed: false, limit: 0, remaining: 0, resetAt: subscription.renewsAt, source: 'SUBSCRIPTION_STATE', reasonCode: 'SUBSCRIPTION_INACTIVE' }
  if (!rule) return { allowed: false, limit: 0, remaining: 0, resetAt: subscription.renewsAt, source: 'NONE', reasonCode: 'ENTITLEMENT_NOT_INCLUDED' }
  if (rule.enabled === false) return { allowed: false, limit: 0, remaining: 0, resetAt: subscription.renewsAt, source: rule.source, reasonCode: 'ENTITLEMENT_DISABLED' }
  const used = rule.used || 0
  const limit = rule.limit ?? Infinity
  const remaining = Math.max(0, limit - used)
  return { allowed: remaining >= quantity, limit, remaining, resetAt: rule.resetAt || subscription.renewsAt, source: rule.source, reasonCode: remaining >= quantity ? 'ALLOWED' : 'LIMIT_REACHED' }
}

export function createUsageEvent(input = {}) {
  if (!input.idempotencyKey || !input.tenantId || !input.capabilityCode || !Number.isInteger(input.quantity) || input.quantity <= 0) throw new Error('USAGE_EVENT_REQUIRED')
  return { id: input.id || id('usage'), eventId: input.eventId || id('usage_event'), idempotencyKey: input.idempotencyKey, tenantId: input.tenantId, merchantId: input.merchantId || null, capabilityCode: input.capabilityCode, quantity: input.quantity, unit: input.unit || 'COUNT', workflowRunId: input.workflowRunId || null, itemId: input.itemId || null, stepId: input.stepId || null, providerCode: input.providerCode || null, providerUsage: input.providerUsage || null, status: input.status || 'RECEIVED', occurredAt: input.occurredAt || now(), schemaVersion: input.schemaVersion || 'v1' }
}

export function createCreditAccount(input = {}) {
  if (!input.tenantId) throw new Error('CREDIT_ACCOUNT_TENANT_REQUIRED')
  return { id: input.id || id('credit_account'), tenantId: input.tenantId, currency: input.currency || 'PLATFORM_CREDIT', createdAt: now() }
}

export function createCreditLedgerEntry(input = {}) {
  if (!input.accountId || !input.tenantId || !input.type || !Number.isInteger(input.amount) || (input.amount === 0 && input.type !== CREDIT_ENTRY_TYPES.CAPTURE)) throw new Error('CREDIT_ENTRY_REQUIRED')
  return { id: input.id || id('credit_entry'), accountId: input.accountId, tenantId: input.tenantId, type: input.type, amount: input.amount, lotId: input.lotId || null, reservationId: input.reservationId || null, usageEventId: input.usageEventId || null, reason: input.reason || null, createdAt: input.createdAt || now() }
}

export function reconstructCreditBalance(entries = [], accountId) {
  return entries.filter((entry) => entry.accountId === accountId).reduce((sum, entry) => sum + entry.amount, 0)
}

export function createSaasBillingEngine({ plans = [], nowFn = now } = {}) {
  const planVersions = new Map(plans.map((plan) => [plan.id, plan]))
  const subscriptions = new Map()
  const usageEvents = new Map()
  const accounts = new Map()
  const ledger = []
  const orders = new Map()
  const idempotency = new Map()
  const events = []
  const record = (name, payload) => events.push({ id: id('billing_event'), name, payload, occurredAt: nowFn() })
  const accountFor = (tenantId) => {
    let account = [...accounts.values()].find((candidate) => candidate.tenantId === tenantId)
    if (!account) { account = createCreditAccount({ tenantId }); accounts.set(account.id, account) }
    return account
  }
  return {
    planVersions,
    subscriptions,
    usageEvents,
    accounts,
    ledger,
    orders,
    events,
    addPlanVersion(plan) { planVersions.set(plan.id, plan); return plan },
    subscribe(input) {
      const plan = planVersions.get(input.planVersionId)
      if (!plan) throw new Error('PLAN_VERSION_NOT_FOUND')
      const subscription = createSubscription({ ...input, planVersion: plan })
      subscriptions.set(subscription.id, subscription); accountFor(subscription.tenantId); record('SUBSCRIPTION_CREATED', { subscriptionId: subscription.id, planVersionId: plan.id }); return subscription
    },
    getEntitlement(subscriptionId, code, quantity = 1, overrides = []) {
      const subscription = subscriptions.get(subscriptionId)
      if (!subscription) throw new Error('SUBSCRIPTION_NOT_FOUND')
      return checkEntitlement(subscription, code, quantity, overrides)
    },
    recordUsage(input) {
      if (idempotency.has(`usage:${input.idempotencyKey}`)) return idempotency.get(`usage:${input.idempotencyKey}`)
      const event = createUsageEvent(input); usageEvents.set(event.eventId, event); idempotency.set(`usage:${event.idempotencyKey}`, event); record('USAGE_RECORDED', { eventId: event.eventId, capabilityCode: event.capabilityCode, quantity: event.quantity }); return event
    },
    reserveCredits(input) {
      const key = `reserve:${input.idempotencyKey}`
      if (idempotency.has(key)) return idempotency.get(key)
      const account = accountFor(input.tenantId)
      const available = reconstructCreditBalance(ledger, account.id)
      if (available < input.amount) throw new Error('INSUFFICIENT_CREDITS')
      const reservation = { id: id('credit_reservation'), accountId: account.id, tenantId: input.tenantId, amount: input.amount, state: 'RESERVED', createdAt: now() }
      ledger.push(createCreditLedgerEntry({ accountId: account.id, tenantId: input.tenantId, type: CREDIT_ENTRY_TYPES.RESERVE, amount: -input.amount, reservationId: reservation.id, reason: input.reason }))
      idempotency.set(key, reservation); record('CREDITS_RESERVED', { reservationId: reservation.id, amount: input.amount }); return reservation
    },
    captureReservation(reservationId, actualAmount = null) {
      const reservation = [...idempotency.values()].find((value) => value?.id === reservationId)
      if (!reservation) throw new Error('RESERVATION_NOT_FOUND')
      if (reservation.state !== 'RESERVED') return reservation
      const amount = actualAmount ?? reservation.amount
      if (amount > reservation.amount) throw new Error('CAPTURE_EXCEEDS_RESERVATION')
      const difference = reservation.amount - amount
      if (difference) ledger.push(createCreditLedgerEntry({ accountId: reservation.accountId, tenantId: reservation.tenantId, type: CREDIT_ENTRY_TYPES.RELEASE, amount: difference, reservationId, reason: 'UNUSED_RESERVATION' }))
      ledger.push(createCreditLedgerEntry({ accountId: reservation.accountId, tenantId: reservation.tenantId, type: CREDIT_ENTRY_TYPES.CAPTURE, amount: 0, reservationId, reason: 'CAPTURED_BY_USAGE' }))
      reservation.state = 'CAPTURED'; reservation.actualAmount = amount; record('CREDITS_CAPTURED', { reservationId, amount }); return reservation
    },
    releaseReservation(reservationId) {
      const reservation = [...idempotency.values()].find((value) => value?.id === reservationId)
      if (!reservation) throw new Error('RESERVATION_NOT_FOUND')
      if (reservation.state !== 'RESERVED') return reservation
      ledger.push(createCreditLedgerEntry({ accountId: reservation.accountId, tenantId: reservation.tenantId, type: CREDIT_ENTRY_TYPES.RELEASE, amount: reservation.amount, reservationId, reason: 'TASK_RELEASED' }))
      reservation.state = 'RELEASED'; record('CREDITS_RELEASED', { reservationId, amount: reservation.amount }); return reservation
    },
    createSaasOrder(input = {}) {
      if (!input.tenantId || !input.subscriptionId || !Number.isInteger(input.totalMinor) || input.totalMinor < 0) throw new Error('SAAS_ORDER_REQUIRED')
      const key = `order:${input.idempotencyKey || id('order_key')}`
      if (idempotency.has(key)) return idempotency.get(key)
      const order = { id: input.id || id('saas_order'), tenantId: input.tenantId, subscriptionId: input.subscriptionId, totalMinor: input.totalMinor, currency: input.currency || 'CNY', state: 'PENDING_PAYMENT', paymentState: 'INIT', items: input.items || [], createdAt: now() }
      orders.set(order.id, order); idempotency.set(key, order); record('SAAS_ORDER_CREATED', { saasOrderId: order.id, totalMinor: order.totalMinor }); return order
    },
  }
}

