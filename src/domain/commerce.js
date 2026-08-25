/**
 * Phase 3 commerce contracts.
 *
 * This module is intentionally provider- and database-agnostic. It gives the
 * consumer preview a deterministic, tenant-scoped contract that a future Core
 * API can implement without changing the page flow.
 */

export const OFFERING_KINDS = Object.freeze({ PRODUCT: 'PRODUCT', SERVICE: 'SERVICE' })

export const ORDER_STATES = Object.freeze({
  DRAFT: 'DRAFT',
  PENDING_PAYMENT: 'PENDING_PAYMENT',
  PAID: 'PAID',
  FULFILLING: 'FULFILLING',
  COMPLETED: 'COMPLETED',
  CANCELED: 'CANCELED',
  REFUNDING: 'REFUNDING',
  PARTIALLY_REFUNDED: 'PARTIALLY_REFUNDED',
  REFUNDED: 'REFUNDED',
})

export const PAYMENT_STATES = Object.freeze({
  INIT: 'INIT',
  PREPAY_CREATED: 'PREPAY_CREATED',
  PROCESSING: 'PROCESSING',
  SUCCESS: 'SUCCESS',
  CLOSED: 'CLOSED',
  FAILED: 'FAILED',
  REFUNDING: 'REFUNDING',
  PARTIALLY_REFUNDED: 'PARTIALLY_REFUNDED',
  REFUNDED: 'REFUNDED',
})

const orderTransitions = Object.freeze({
  [ORDER_STATES.DRAFT]: [ORDER_STATES.PENDING_PAYMENT, ORDER_STATES.CANCELED],
  [ORDER_STATES.PENDING_PAYMENT]: [ORDER_STATES.PAID, ORDER_STATES.CANCELED, ORDER_STATES.REFUNDING],
  [ORDER_STATES.PAID]: [ORDER_STATES.FULFILLING, ORDER_STATES.REFUNDING, ORDER_STATES.PARTIALLY_REFUNDED],
  [ORDER_STATES.FULFILLING]: [ORDER_STATES.COMPLETED, ORDER_STATES.REFUNDING, ORDER_STATES.PARTIALLY_REFUNDED],
  [ORDER_STATES.COMPLETED]: [ORDER_STATES.REFUNDING, ORDER_STATES.PARTIALLY_REFUNDED],
  [ORDER_STATES.REFUNDING]: [ORDER_STATES.PARTIALLY_REFUNDED, ORDER_STATES.REFUNDED],
  [ORDER_STATES.PARTIALLY_REFUNDED]: [ORDER_STATES.REFUNDING, ORDER_STATES.REFUNDED],
  [ORDER_STATES.CANCELED]: [],
  [ORDER_STATES.REFUNDED]: [],
})

function id(prefix) {
  return `${prefix}_${crypto.randomUUID()}`
}

function now() {
  return new Date().toISOString()
}

export function canTransitionOrder(from, to) {
  return orderTransitions[from]?.includes(to) ?? false
}

export function transitionOrder(order, next) {
  if (!canTransitionOrder(order.state, next)) {
    throw new Error(`INVALID_ORDER_TRANSITION:${order.state}->${next}`)
  }
  return { ...order, state: next, updatedAt: now() }
}

export function createConsumerContext(input = {}) {
  if (!input.tenantId || !input.merchantId || !input.storeId) throw new Error('CONSUMER_CONTEXT_REQUIRED')
  return {
    tenantId: input.tenantId,
    merchantId: input.merchantId,
    storeId: input.storeId,
    entryContext: input.entryContext || null,
    sessionId: input.sessionId || id('session'),
  }
}

export function createOffering(input = {}) {
  if (!input.name || !Number.isInteger(input.priceMinor) || input.priceMinor < 0) throw new Error('OFFERING_PRICE_REQUIRED')
  return {
    id: input.id || id('offering'),
    tenantId: input.tenantId,
    merchantId: input.merchantId,
    storeId: input.storeId,
    kind: input.kind || OFFERING_KINDS.PRODUCT,
    name: input.name,
    summary: input.summary || '',
    category: input.category || '推荐',
    priceMinor: input.priceMinor,
    currency: input.currency || 'CNY',
    inventory: input.inventory ?? null,
    durationMinutes: input.durationMinutes ?? null,
    slots: input.slots || [],
    cover: input.cover || null,
    rules: input.rules || [],
    status: input.status || 'ON_SALE',
  }
}

function assertBelongs(entity, context) {
  if (!entity || entity.tenantId !== context.tenantId || entity.merchantId !== context.merchantId || entity.storeId !== context.storeId) {
    throw new Error('CROSS_TENANT_RESOURCE')
  }
}

export function createCommerceEngine({ offerings = [], nowFn = now } = {}) {
  const catalog = new Map(offerings.map((item) => [item.id, item]))
  const carts = new Map()
  const orders = new Map()
  const reservations = new Map()
  const idempotency = new Map()
  const events = []

  const record = (name, payload) => events.push({ id: id('event'), name, payload, occurredAt: nowFn() })
  const getOfferingFor = (context, offeringId) => {
    const offering = catalog.get(offeringId)
    assertBelongs(offering, context)
    if (offering.status !== 'ON_SALE') throw new Error('OFFERING_UNAVAILABLE')
    return offering
  }

  return {
    catalog,
    carts,
    orders,
    reservations,
    events,
    listOfferings(context) {
      return [...catalog.values()].filter((item) => item.tenantId === context.tenantId && item.merchantId === context.merchantId && item.storeId === context.storeId && item.status === 'ON_SALE')
    },
    getOffering(context, offeringId) {
      return getOfferingFor(context, offeringId)
    },
    getAvailability(context, offeringId) {
      const offering = getOfferingFor(context, offeringId)
      if (offering.kind === OFFERING_KINDS.SERVICE) return { slots: offering.slots, remaining: offering.slots.length }
      const reserved = [...reservations.values()].filter((item) => item.offeringId === offeringId && item.expiresAt > nowFn()).reduce((sum, item) => sum + item.quantity, 0)
      return { remaining: Math.max(0, (offering.inventory ?? 0) - reserved) }
    },
    createCart(context, customerId = null) {
      const cart = { id: id('cart'), ...context, customerId, items: [], state: 'ACTIVE', createdAt: nowFn(), updatedAt: nowFn() }
      carts.set(cart.id, cart); record('CART_CREATED', { cartId: cart.id, ...context }); return cart
    },
    addCartItem(context, cartId, input = {}) {
      const cart = carts.get(cartId); assertBelongs(cart, context)
      const offering = getOfferingFor(context, input.offeringId)
      const quantity = input.quantity || 1
      if (!Number.isInteger(quantity) || quantity < 1) throw new Error('QUANTITY_INVALID')
      const availability = this.getAvailability(context, offering.id)
      if (offering.kind === OFFERING_KINDS.PRODUCT && availability.remaining < quantity) throw new Error('INSUFFICIENT_INVENTORY')
      const existing = cart.items.find((item) => item.offeringId === offering.id && item.slot === (input.slot || null))
      if (existing) existing.quantity += quantity
      else cart.items.push({ offeringId: offering.id, name: offering.name, quantity, unitPriceMinor: offering.priceMinor, slot: input.slot || null })
      cart.updatedAt = nowFn(); record('CART_ITEM_ADDED', { cartId, offeringId: offering.id, quantity }); return cart
    },
    createDraftBooking(context, input = {}) {
      const offering = getOfferingFor(context, input.offeringId)
      if (offering.kind !== OFFERING_KINDS.SERVICE) throw new Error('SERVICE_REQUIRED')
      if (!input.slot || !offering.slots.includes(input.slot)) throw new Error('SLOT_UNAVAILABLE')
      return { id: id('booking'), ...context, offeringId: offering.id, slot: input.slot, status: 'DRAFT', createdAt: nowFn() }
    },
    createDraftOrder(context, input = {}) {
      const key = input.idempotencyKey
      if (key && idempotency.has(key)) return idempotency.get(key)
      const cart = carts.get(input.cartId); assertBelongs(cart, context)
      if (!cart.items.length && !input.booking) throw new Error('CART_EMPTY')
      const items = cart.items.map((item) => ({ ...item }))
      const subtotalMinor = items.reduce((sum, item) => sum + item.unitPriceMinor * item.quantity, 0)
      const order = { id: id('order'), ...context, customerId: input.customerId || cart.customerId || null, items, booking: input.booking || null, subtotalMinor, discountMinor: 0, totalMinor: subtotalMinor, state: ORDER_STATES.PENDING_PAYMENT, paymentState: PAYMENT_STATES.INIT, createdAt: nowFn(), updatedAt: nowFn() }
      for (const item of items) {
        const offering = getOfferingFor(context, item.offeringId)
        if (offering.kind === OFFERING_KINDS.PRODUCT) {
          const availability = this.getAvailability(context, offering.id)
          if (availability.remaining < item.quantity) throw new Error('INSUFFICIENT_INVENTORY')
          reservations.set(`${order.id}:${item.offeringId}`, { orderId: order.id, offeringId: item.offeringId, quantity: item.quantity, expiresAt: new Date(Date.now() + 15 * 60 * 1000).toISOString() })
        }
      }
      orders.set(order.id, order); if (key) idempotency.set(key, order); record('ORDER_CREATED', { orderId: order.id, totalMinor: order.totalMinor }); return order
    },
    transitionOrder(orderId, context, next) {
      const current = orders.get(orderId); assertBelongs(current, context)
      const updated = transitionOrder(current, next); orders.set(orderId, updated); record('ORDER_STATE_CHANGED', { orderId, state: next }); return updated
    },
    createHandoff(context, input = {}, ttlMs = 5 * 60 * 1000) {
      if (!input.targetPath) throw new Error('HANDOFF_TARGET_REQUIRED')
      const token = id('handoff')
      const handoff = { token, ...context, targetPath: input.targetPath, payload: input.payload || {}, expiresAt: Date.now() + ttlMs, used: false }
      idempotency.set(`handoff:${token}`, handoff); record('HANDOFF_CREATED', { targetPath: input.targetPath, sessionId: context.sessionId }); return { token, targetPath: input.targetPath, query: { handoff_token: token }, card: input.card || null }
    },
    consumeHandoff(context, token) {
      const handoff = idempotency.get(`handoff:${token}`)
      if (!handoff || handoff.used || handoff.expiresAt <= Date.now()) throw new Error('HANDOFF_INVALID')
      if (handoff.tenantId !== context.tenantId || handoff.merchantId !== context.merchantId || handoff.storeId !== context.storeId) throw new Error('CROSS_TENANT_HANDOFF')
      handoff.used = true; record('HANDOFF_CONSUMED', { targetPath: handoff.targetPath, sessionId: context.sessionId }); return handoff
    },
  }
}

