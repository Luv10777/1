/** Tenant-scoped CRM/CDP primitives for the Phase 3 event chain. */

function id(prefix) { return `${prefix}_${crypto.randomUUID()}` }
function timestamp() { return new Date().toISOString() }

export const CONSENT_STATES = Object.freeze({ GRANTED: 'GRANTED', WITHDRAWN: 'WITHDRAWN', UNKNOWN: 'UNKNOWN' })

export function createCustomerProfile(context, input = {}) {
  if (!context?.tenantId || !context.merchantId) throw new Error('CUSTOMER_CONTEXT_REQUIRED')
  return {
    id: input.id || id('customer'),
    tenantId: context.tenantId,
    merchantId: context.merchantId,
    storeId: context.storeId || null,
    displayName: input.displayName || null,
    lifecycle: input.lifecycle || 'NEW',
    tags: input.tags || [],
    createdAt: timestamp(),
    updatedAt: timestamp(),
  }
}

export function createCustomerIdentity(profile, input = {}) {
  if (!profile?.id || !input.type || !input.value) throw new Error('CUSTOMER_IDENTITY_REQUIRED')
  return { id: input.id || id('identity'), tenantId: profile.tenantId, customerId: profile.id, type: input.type, value: input.value, verified: Boolean(input.verified), createdAt: timestamp() }
}

export function createConsent(profile, input = {}) {
  if (!profile?.id || !input.purpose || !input.channel) throw new Error('CONSENT_PURPOSE_REQUIRED')
  return { id: input.id || id('consent'), tenantId: profile.tenantId, customerId: profile.id, purpose: input.purpose, channel: input.channel, version: input.version || 'v1', state: input.state || CONSENT_STATES.UNKNOWN, grantedAt: input.state === CONSENT_STATES.GRANTED ? timestamp() : null, withdrawnAt: input.state === CONSENT_STATES.WITHDRAWN ? timestamp() : null }
}

export function createCustomerEvent(context, input = {}) {
  if (!context?.tenantId || !input.name) throw new Error('CUSTOMER_EVENT_REQUIRED')
  return { id: input.id || id('event'), name: input.name, occurredAt: input.occurredAt || timestamp(), tenantId: context.tenantId, merchantId: context.merchantId || null, storeId: context.storeId || null, customerId: input.customerId || null, anonymousId: input.anonymousId || null, sessionId: input.sessionId || null, properties: input.properties || {}, schemaVersion: input.schemaVersion || 'v1' }
}

export function mergeCustomerProfiles(target, source, evidence = {}) {
  if (!target || !source || target.tenantId !== source.tenantId || target.merchantId !== source.merchantId) throw new Error('CUSTOMER_MERGE_SCOPE_INVALID')
  if (!evidence.type || !evidence.reference) throw new Error('CUSTOMER_MERGE_EVIDENCE_REQUIRED')
  return { ...target, tags: [...new Set([...(target.tags || []), ...(source.tags || [])])], mergedFrom: [...(target.mergedFrom || []), source.id], updatedAt: timestamp(), mergeEvidence: [...(target.mergeEvidence || []), evidence] }
}

