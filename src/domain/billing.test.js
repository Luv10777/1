import test from 'node:test'
import assert from 'node:assert/strict'
import { BILLING_INTERVALS, CREDIT_ENTRY_TYPES, PLAN_CODES, SUBSCRIPTION_STATES, createPlanVersion, createSaasBillingEngine, reconstructCreditBalance } from './billing.js'

const starter = createPlanVersion({ planCode: PLAN_CODES.STARTER, version: '2026-08-25.v1', displayName: 'Starter', prices: [{ interval: BILLING_INTERVALS.MONTH, amountMinor: 9900 }], entitlements: { AI_IMAGE: { enabled: true, limit: 100, resetAt: '2026-09-25T00:00:00.000Z' }, STORES: { enabled: true, limit: 1 } } })

test('plan versions are immutable snapshots and entitlement checks are server-shaped', () => {
  const engine = createSaasBillingEngine({ plans: [starter] })
  const subscription = engine.subscribe({ tenantId: 'tenant_1', planVersionId: starter.id, state: SUBSCRIPTION_STATES.ACTIVE })
  assert.equal(engine.getEntitlement(subscription.id, 'AI_IMAGE', 2).allowed, true)
  assert.equal(engine.getEntitlement(subscription.id, 'STORES', 2).reasonCode, 'LIMIT_REACHED')
  assert.equal(engine.getEntitlement(subscription.id, 'AI_VIDEO').reasonCode, 'ENTITLEMENT_NOT_INCLUDED')
  const override = { code: 'AI_VIDEO', enabled: true, limit: 3, reason: 'approved pilot' }
  assert.equal(engine.getEntitlement(subscription.id, 'AI_VIDEO', 1, [override]).source, 'APPROVED_OVERRIDE')
  assert.equal(starter.prices[0].amountMinor, 9900)
})

test('usage and credit reservation are idempotent and reconstructable', () => {
  const engine = createSaasBillingEngine({ plans: [starter] })
  const subscription = engine.subscribe({ tenantId: 'tenant_2', planVersionId: starter.id, state: SUBSCRIPTION_STATES.ACTIVE })
  const account = engine.accounts.values().next().value
  engine.ledger.push({ ...{ id: 'grant_1', accountId: account.id, tenantId: 'tenant_2', type: CREDIT_ENTRY_TYPES.GRANT, amount: 50 } })
  const usageA = engine.recordUsage({ tenantId: 'tenant_2', capabilityCode: 'AI_IMAGE', quantity: 2, idempotencyKey: 'run:item:1' })
  const usageB = engine.recordUsage({ tenantId: 'tenant_2', capabilityCode: 'AI_IMAGE', quantity: 2, idempotencyKey: 'run:item:1' })
  assert.equal(usageA.eventId, usageB.eventId)
  const reservation = engine.reserveCredits({ tenantId: 'tenant_2', amount: 10, idempotencyKey: 'reserve:item:1', reason: 'image batch' })
  assert.equal(engine.reserveCredits({ tenantId: 'tenant_2', amount: 10, idempotencyKey: 'reserve:item:1' }).id, reservation.id)
  engine.captureReservation(reservation.id, 7)
  assert.equal(reconstructCreditBalance(engine.ledger, account.id), 43)
  assert.equal(subscription.tenantId, 'tenant_2')
})

test('SaaS orders use an independent namespace from consumer commerce orders', () => {
  const engine = createSaasBillingEngine({ plans: [starter] })
  const subscription = engine.subscribe({ tenantId: 'tenant_3', planVersionId: starter.id, state: SUBSCRIPTION_STATES.ACTIVE })
  const order = engine.createSaasOrder({ tenantId: 'tenant_3', subscriptionId: subscription.id, totalMinor: 9900, idempotencyKey: 'checkout:1' })
  assert.match(order.id, /^saas_order_/)
  assert.equal(order.paymentState, 'INIT')
})

