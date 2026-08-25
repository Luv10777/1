import test from 'node:test'
import assert from 'node:assert/strict'
import { createCommerceEngine, createConsumerContext, createOffering, OFFERING_KINDS, ORDER_STATES } from './commerce.js'

const context = createConsumerContext({ tenantId: 'tenant_1', merchantId: 'merchant_1', storeId: 'store_1', entryContext: { campaignId: 'cmp_1' } })
const tea = createOffering({ id: 'off_tea', tenantId: 'tenant_1', merchantId: 'merchant_1', storeId: 'store_1', name: '双人下午茶', priceMinor: 2990, inventory: 2, category: '套餐' })
const facial = createOffering({ id: 'off_facial', tenantId: 'tenant_1', merchantId: 'merchant_1', storeId: 'store_1', name: '舒缓护理', priceMinor: 16800, kind: OFFERING_KINDS.SERVICE, durationMinutes: 60, slots: ['2026-08-26 14:00'] })

test('consumer flow creates a tenant-scoped draft order and inventory reservation', () => {
  const engine = createCommerceEngine({ offerings: [tea, facial] })
  const cart = engine.createCart(context, 'customer_1')
  engine.addCartItem(context, cart.id, { offeringId: tea.id, quantity: 1 })
  const order = engine.createDraftOrder(context, { cartId: cart.id, idempotencyKey: 'order:cart-1' })
  assert.equal(order.state, ORDER_STATES.PENDING_PAYMENT)
  assert.equal(order.totalMinor, 2990)
  assert.equal(engine.getAvailability(context, tea.id).remaining, 1)
  assert.equal(engine.createDraftOrder(context, { cartId: cart.id, idempotencyKey: 'order:cart-1' }).id, order.id)
})

test('cross-tenant resources and invalid service slots are blocked', () => {
  const engine = createCommerceEngine({ offerings: [tea, facial] })
  const other = createConsumerContext({ tenantId: 'tenant_2', merchantId: 'merchant_2', storeId: 'store_2' })
  assert.throws(() => engine.getOffering(other, tea.id), /CROSS_TENANT_RESOURCE/)
  assert.throws(() => engine.createDraftBooking(context, { offeringId: facial.id, slot: '2026-08-27 10:00' }), /SLOT_UNAVAILABLE/)
})

test('handoff is scoped, expiring and one-time', () => {
  const engine = createCommerceEngine({ offerings: [tea] })
  const handoff = engine.createHandoff(context, { targetPath: '/checkout', payload: { orderId: 'order_1' } })
  assert.equal(engine.consumeHandoff(context, handoff.token).payload.orderId, 'order_1')
  assert.throws(() => engine.consumeHandoff(context, handoff.token), /HANDOFF_INVALID/)
  const other = createConsumerContext({ tenantId: 'tenant_2', merchantId: 'merchant_2', storeId: 'store_2' })
  const scoped = engine.createHandoff(context, { targetPath: '/booking' })
  assert.throws(() => engine.consumeHandoff(other, scoped.token), /CROSS_TENANT_HANDOFF/)
})

