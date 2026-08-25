/** Event and explainable attribution contracts. */

function id(prefix) { return `${prefix}_${crypto.randomUUID()}` }

export const EVENT_NAMES = Object.freeze([
  'CONTENT_IMPRESSION', 'CONTENT_CLICK', 'MINIPROGRAM_OPEN', 'AI_CONVERSATION_START', 'OFFERING_VIEW', 'ADD_TO_CART', 'BOOKING_DRAFTED', 'ORDER_CREATED', 'PAYMENT_SUCCESS', 'VERIFICATION_SUCCESS', 'REFUND_SUCCESS', 'REVIEW_CREATED',
])

export function createGrowthEvent(input = {}) {
  if (!input.name || !EVENT_NAMES.includes(input.name)) throw new Error('EVENT_NAME_INVALID')
  if (!input.tenantId) throw new Error('EVENT_TENANT_REQUIRED')
  return { eventId: input.eventId || id('evt'), eventName: input.name, occurredAt: input.occurredAt || new Date().toISOString(), tenantId: input.tenantId, merchantId: input.merchantId || null, storeId: input.storeId || null, anonymousId: input.anonymousId || null, customerId: input.customerId || null, sessionId: input.sessionId || null, conversationId: input.conversationId || null, campaignId: input.campaignId || null, contentId: input.contentId || null, publishId: input.publishId || null, channel: input.channel || null, source: input.source || null, medium: input.medium || null, entryScene: input.entryScene || null, qrScene: input.qrScene || null, handoffId: input.handoffId || null, orderId: input.orderId || null, paymentId: input.paymentId || null, verificationId: input.verificationId || null, properties: input.properties || {}, schemaVersion: input.schemaVersion || 'v1' }
}

export function attributeOrder(events = [], orderId, options = {}) {
  const touches = events.filter((event) => event.orderId === orderId || (event.sessionId && options.sessionId && event.sessionId === options.sessionId)).sort((a, b) => a.occurredAt.localeCompare(b.occurredAt))
  const nonDirect = touches.filter((event) => event.source && event.source !== 'direct')
  const first = touches[0] || null
  const last = nonDirect.at(-1) || first
  return { orderId, model: options.model || 'LAST_NON_DIRECT', estimated: !touches.length || !touches.some((event) => event.orderId === orderId), firstTouch: first ? { eventId: first.eventId, source: first.source, contentId: first.contentId } : null, lastTouch: last ? { eventId: last.eventId, source: last.source, contentId: last.contentId } : null, assists: touches.slice(0, -1).map((event) => ({ eventId: event.eventId, source: event.source, contentId: event.contentId })), reason: touches.length ? '基于可见会话和内容触点关联' : '没有足够事件，归入自然/未知来源' }
}

