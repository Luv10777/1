import test from 'node:test'
import assert from 'node:assert/strict'
import { createCustomerEvent } from './customer.js'
import { attributeOrder, createGrowthEvent } from './attribution.js'
import { approveGrowthPlan, createGrowthPlan, createInsight } from './growth.js'

const context = { tenantId: 'tenant_1', merchantId: 'merchant_1', storeId: 'store_1', sessionId: 'session_1' }

test('growth events keep the content-to-order chain and attribution is explainable', () => {
  const events = [
    createGrowthEvent({ ...context, name: 'CONTENT_CLICK', source: 'xiaohongshu', contentId: 'content_1', occurredAt: '2026-08-25T10:00:00.000Z' }),
    createGrowthEvent({ ...context, name: 'MINIPROGRAM_OPEN', source: 'xiaohongshu', sessionId: 'session_1', occurredAt: '2026-08-25T10:01:00.000Z' }),
    createGrowthEvent({ ...context, name: 'PAYMENT_SUCCESS', source: 'direct', sessionId: 'session_1', orderId: 'order_1', occurredAt: '2026-08-25T10:05:00.000Z' }),
  ]
  const result = attributeOrder(events, 'order_1', { sessionId: 'session_1' })
  assert.equal(result.lastTouch.source, 'xiaohongshu')
  assert.equal(result.model, 'LAST_NON_DIRECT')
  assert.equal(result.estimated, false)
})

test('customer events and growth plans require evidence and approval', () => {
  const event = createCustomerEvent(context, { name: 'PAYMENT_SUCCESS', customerId: 'customer_1', properties: { orderId: 'order_1' } })
  assert.equal(event.tenantId, context.tenantId)
  const insight = createInsight({ tenantId: context.tenantId, title: '咨询多但购买少', evidence: [{ metric: 'conversation_to_order', value: 0.08 }], confidence: 'MEDIUM' })
  const plan = createGrowthPlan([insight], { tenantId: context.tenantId, actions: [{ type: 'CREATE_CONTENT_DRAFT' }] })
  assert.equal(plan.status, 'DRAFT')
  assert.equal(approveGrowthPlan(plan, 'merchant_admin_1').status, 'APPROVED')
  assert.throws(() => approveGrowthPlan(plan), /GROWTH_APPROVAL_REQUIRED/)
})

