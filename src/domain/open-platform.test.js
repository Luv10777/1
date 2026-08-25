import test from 'node:test'
import assert from 'node:assert/strict'
import { API_VERSION_STATES, CONNECTOR_STATES, DELIVERY_STATES, createApiVersion, createConnectorManifest, createQuotaPolicy, createWebhookDelivery, createWebhookSubscription, consumeQuota, recordWebhookAttempt, resolveApiVersion, transitionConnector } from './open-platform.js'

test('API versions resolve exact, deprecated and current fallback contracts', () => {
  const current = createApiVersion({ version: 'v2' })
  const deprecated = createApiVersion({ version: 'v1', state: API_VERSION_STATES.DEPRECATED, successor: 'v2' })
  assert.equal(resolveApiVersion('v1', [deprecated, current]).decision, 'DEPRECATED')
  assert.equal(resolveApiVersion('v9', [deprecated, current]).version.version, 'v2')
  assert.throws(() => createApiVersion({ version: '2025' }), /API_VERSION_INVALID/)
})

test('quota consumption is bounded and returns retry metadata', () => {
  const policy = createQuotaPolicy({ id: 'quota_demo', limit: 2, burst: 1, windowMs: 60_000 })
  let counter = {}
  counter = consumeQuota(counter, policy, 1_000).counter
  counter = consumeQuota(counter, policy, 1_001).counter
  const allowed = consumeQuota(counter, policy, 1_002)
  assert.equal(allowed.allowed, true)
  const blocked = consumeQuota(allowed.counter, policy, 1_003)
  assert.equal(blocked.allowed, false)
  assert.ok(blocked.retryAfterMs > 0)
})

test('webhook subscriptions create idempotent deliveries and dead-letter after retries', () => {
  const subscription = createWebhookSubscription({ id: 'hook_demo', tenantId: 'tenant_demo', eventTypes: ['order.created'], endpoint: 'https://sandbox.invalid/hooks' })
  const delivery = createWebhookDelivery(subscription, { id: 'evt_1', type: 'order.created' })
  assert.equal(delivery.idempotencyKey, 'hook_demo:evt_1')
  let state = recordWebhookAttempt(delivery, { ok: false, maxAttempts: 2 }, 2_000)
  assert.equal(state.state, DELIVERY_STATES.RETRYING)
  state = recordWebhookAttempt(state, { ok: false, maxAttempts: 2 }, 4_000)
  assert.equal(state.state, DELIVERY_STATES.DEAD_LETTER)
  assert.throws(() => createWebhookSubscription({ tenantId: 'tenant_demo', eventTypes: ['order.created'], endpoint: 'http://10.0.0.4/hooks' }), /WEBHOOK_ENDPOINT_UNSAFE/)
})

test('connector manifest stays declarative and requires review for approval', () => {
  const manifest = createConnectorManifest({ id: 'connector_demo', name: '内部 CRM 适配器', version: '1.0.0', capabilities: ['READ'], requiredScopes: ['merchant.read'] })
  assert.equal(manifest.state, CONNECTOR_STATES.REVIEW_REQUIRED)
  assert.equal(transitionConnector(manifest, CONNECTOR_STATES.APPROVED, { reviewerId: 'reviewer_1' }).state, CONNECTOR_STATES.APPROVED)
  assert.throws(() => transitionConnector(manifest, CONNECTOR_STATES.APPROVED), /CONNECTOR_REVIEWER_REQUIRED/)
  assert.throws(() => createConnectorManifest({ id: 'bad', name: '任意代码', version: '1.0.0', capabilities: ['READ'], entrypoint: 'run.js' }), /CONNECTOR_CODE_NOT_ALLOWED/)
})
