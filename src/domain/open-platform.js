/**
 * Phase 5 open-platform sandbox contracts.
 *
 * These are pure in-memory guards for API versioning, quotas, webhook delivery
 * and connector review. They deliberately do not make network calls or run
 * third-party code; a future service layer must persist and enforce them.
 */

export const API_VERSION_STATES = Object.freeze({ CURRENT: 'CURRENT', DEPRECATED: 'DEPRECATED', SUNSET: 'SUNSET' })
export const WEBHOOK_STATES = Object.freeze({ ACTIVE: 'ACTIVE', PAUSED: 'PAUSED', DISABLED: 'DISABLED' })
export const DELIVERY_STATES = Object.freeze({ PENDING: 'PENDING', RETRYING: 'RETRYING', DELIVERED: 'DELIVERED', DEAD_LETTER: 'DEAD_LETTER' })
export const CONNECTOR_STATES = Object.freeze({ DRAFT: 'DRAFT', REVIEW_REQUIRED: 'REVIEW_REQUIRED', APPROVED: 'APPROVED', PAUSED: 'PAUSED', REVOKED: 'REVOKED' })
export const CONNECTOR_CAPABILITIES = Object.freeze(['READ', 'WRITE', 'WEBHOOK'])

function id(prefix) { return `${prefix}_${crypto.randomUUID()}` }
function iso(ms = Date.now()) { return new Date(ms).toISOString() }

export function createApiVersion(input = {}) {
  if (!input.version || !/^v\d+$/.test(input.version)) throw new Error('API_VERSION_INVALID')
  return { version: input.version, state: input.state || API_VERSION_STATES.CURRENT, releasedAt: input.releasedAt || iso(), sunsetAt: input.sunsetAt || null, successor: input.successor || null }
}

export function resolveApiVersion(requested, versions = []) {
  const available = versions.filter((version) => version.state !== API_VERSION_STATES.SUNSET)
  const exact = available.find((version) => version.version === requested)
  if (exact) return { version: exact, decision: exact.state === API_VERSION_STATES.DEPRECATED ? 'DEPRECATED' : 'EXACT' }
  const current = available.find((version) => version.state === API_VERSION_STATES.CURRENT)
  if (!current) throw new Error('API_VERSION_UNAVAILABLE')
  return { version: current, decision: 'FALLBACK_CURRENT' }
}

export function createQuotaPolicy(input = {}) {
  if (!Number.isInteger(input.limit) || input.limit <= 0 || !Number.isInteger(input.windowMs) || input.windowMs <= 0) throw new Error('QUOTA_POLICY_INVALID')
  return { id: input.id || id('quota'), scope: input.scope || 'app', limit: input.limit, windowMs: input.windowMs, burst: Math.max(0, input.burst || 0) }
}

export function consumeQuota(counter = {}, policy, atMs = Date.now()) {
  if (!policy) throw new Error('QUOTA_POLICY_REQUIRED')
  const windowStart = counter.windowStart && atMs - counter.windowStart < policy.windowMs ? counter.windowStart : atMs
  const used = windowStart === counter.windowStart ? counter.used || 0 : 0
  const allowance = policy.limit + policy.burst
  if (used >= allowance) return { allowed: false, used, remaining: 0, retryAfterMs: Math.max(1, policy.windowMs - (atMs - windowStart)), counter: { windowStart, used } }
  const nextUsed = used + 1
  return { allowed: true, used: nextUsed, remaining: allowance - nextUsed, retryAfterMs: 0, counter: { windowStart, used: nextUsed } }
}

export function createWebhookSubscription(input = {}) {
  if (!input.tenantId || !input.eventTypes?.length || !input.endpoint) throw new Error('WEBHOOK_SUBSCRIPTION_REQUIRED')
  if (!/^https:\/\//i.test(input.endpoint) && !/^http:\/\/localhost(?::\d+)?(?:\/|$)/i.test(input.endpoint)) throw new Error('WEBHOOK_ENDPOINT_UNSAFE')
  return { id: input.id || id('webhook'), tenantId: input.tenantId, eventTypes: [...new Set(input.eventTypes)], endpoint: input.endpoint, status: input.status || WEBHOOK_STATES.ACTIVE, secretRef: input.secretRef || 'vault://pending', createdAt: iso(), failureCount: 0 }
}

export function createWebhookDelivery(subscription, event = {}, input = {}) {
  if (!subscription || subscription.status !== WEBHOOK_STATES.ACTIVE) throw new Error('WEBHOOK_NOT_ACTIVE')
  if (!event.id || !event.type) throw new Error('WEBHOOK_EVENT_REQUIRED')
  if (!subscription.eventTypes.includes(event.type)) throw new Error('WEBHOOK_EVENT_NOT_SUBSCRIBED')
  return { id: input.id || id('delivery'), subscriptionId: subscription.id, eventId: event.id, idempotencyKey: `${subscription.id}:${event.id}`, attempt: 0, state: DELIVERY_STATES.PENDING, nextAttemptAt: iso(), lastError: null }
}

export function recordWebhookAttempt(delivery, result = {}, atMs = Date.now()) {
  if (!delivery || [DELIVERY_STATES.DELIVERED, DELIVERY_STATES.DEAD_LETTER].includes(delivery.state)) return delivery
  const attempt = delivery.attempt + 1
  if (result.ok) return { ...delivery, attempt, state: DELIVERY_STATES.DELIVERED, deliveredAt: iso(atMs), lastError: null }
  const maxAttempts = result.maxAttempts || 5
  if (attempt >= maxAttempts) return { ...delivery, attempt, state: DELIVERY_STATES.DEAD_LETTER, lastError: result.error || 'DELIVERY_FAILED' }
  const backoffMs = result.backoffMs || Math.min(60_000, 1_000 * 2 ** (attempt - 1))
  return { ...delivery, attempt, state: DELIVERY_STATES.RETRYING, nextAttemptAt: iso(atMs + backoffMs), lastError: result.error || 'DELIVERY_FAILED' }
}

export function createConnectorManifest(input = {}) {
  if (!input.id || !input.name || !input.version || !Array.isArray(input.capabilities)) throw new Error('CONNECTOR_MANIFEST_REQUIRED')
  if (input.capabilities.some((capability) => !CONNECTOR_CAPABILITIES.includes(capability))) throw new Error('CONNECTOR_CAPABILITY_INVALID')
  if (input.entrypoint || input.binaryUrl || input.installScript) throw new Error('CONNECTOR_CODE_NOT_ALLOWED')
  return { id: input.id, name: input.name, version: input.version, capabilities: [...new Set(input.capabilities)], requiredScopes: input.requiredScopes || [], auth: input.auth || 'OAUTH_REFERENCE', state: CONNECTOR_STATES.REVIEW_REQUIRED, createdAt: iso() }
}

export function transitionConnector(manifest, transition, input = {}) {
  if (!manifest || !Object.values(CONNECTOR_STATES).includes(transition)) throw new Error('CONNECTOR_TRANSITION_INVALID')
  if (transition === CONNECTOR_STATES.APPROVED && !input.reviewerId) throw new Error('CONNECTOR_REVIEWER_REQUIRED')
  if (transition === CONNECTOR_STATES.REVOKED && !input.reason) throw new Error('CONNECTOR_REVOCATION_REASON_REQUIRED')
  return { ...manifest, state: transition, reviewerId: input.reviewerId || manifest.reviewerId || null, reviewReason: input.reason || manifest.reviewReason || null, reviewedAt: input.reviewerId ? iso() : manifest.reviewedAt || null }
}
