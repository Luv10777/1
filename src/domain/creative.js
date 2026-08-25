/**
 * Phase 2 domain primitives. These functions are deliberately provider-agnostic:
 * they can be used by a future Core API or by the current mock UI without changing
 * the workflow contract.
 */

export const WORKFLOW_STATES = Object.freeze({
  DRAFT: 'DRAFT',
  PLANNED: 'PLANNED',
  CONFIRMED: 'CONFIRMED',
  QUEUED: 'QUEUED',
  RUNNING: 'RUNNING',
  QA: 'QA',
  NEEDS_REVIEW: 'NEEDS_REVIEW',
  APPROVED: 'APPROVED',
  PUBLISHED: 'PUBLISHED',
  FAILED: 'FAILED',
  CANCELLED: 'CANCELLED',
})

export const STEP_STATES = Object.freeze({
  PENDING: 'PENDING',
  RUNNING: 'RUNNING',
  SUCCEEDED: 'SUCCEEDED',
  FAILED: 'FAILED',
  RETRYING: 'RETRYING',
  CANCELLED: 'CANCELLED',
})

export const PROVIDER_CAPABILITIES = Object.freeze({
  API_AUTO: 'API_AUTO',
  USER_CONFIRM: 'USER_CONFIRM',
  EXPORT_ONLY: 'EXPORT_ONLY',
  UNAVAILABLE: 'UNAVAILABLE',
})

export const MODEL_ALIASES = Object.freeze([
  'TEXT_PLANNER', 'TEXT_WRITER', 'TEXT_REVIEWER', 'VISION_ANALYZER',
  'IMAGE_PRIMARY', 'VIDEO_DRAFT', 'VIDEO_PRIMARY', 'VIDEO_QUALITY',
  'TTS_PRIMARY', 'AVATAR_PRIMARY',
])

export const transitions = Object.freeze({
  [WORKFLOW_STATES.DRAFT]: [WORKFLOW_STATES.PLANNED, WORKFLOW_STATES.CONFIRMED, WORKFLOW_STATES.CANCELLED],
  [WORKFLOW_STATES.PLANNED]: [WORKFLOW_STATES.CONFIRMED, WORKFLOW_STATES.DRAFT, WORKFLOW_STATES.CANCELLED],
  [WORKFLOW_STATES.CONFIRMED]: [WORKFLOW_STATES.QUEUED, WORKFLOW_STATES.CANCELLED],
  [WORKFLOW_STATES.QUEUED]: [WORKFLOW_STATES.RUNNING, WORKFLOW_STATES.CANCELLED],
  [WORKFLOW_STATES.RUNNING]: [WORKFLOW_STATES.QA, WORKFLOW_STATES.FAILED, WORKFLOW_STATES.CANCELLED],
  [WORKFLOW_STATES.QA]: [WORKFLOW_STATES.NEEDS_REVIEW, WORKFLOW_STATES.APPROVED, WORKFLOW_STATES.FAILED],
  [WORKFLOW_STATES.NEEDS_REVIEW]: [WORKFLOW_STATES.RUNNING, WORKFLOW_STATES.APPROVED, WORKFLOW_STATES.CANCELLED],
  [WORKFLOW_STATES.APPROVED]: [WORKFLOW_STATES.PUBLISHED, WORKFLOW_STATES.CANCELLED],
  [WORKFLOW_STATES.FAILED]: [WORKFLOW_STATES.QUEUED, WORKFLOW_STATES.CANCELLED],
  [WORKFLOW_STATES.PUBLISHED]: [],
  [WORKFLOW_STATES.CANCELLED]: [],
})

export function canTransition(from, to) {
  return transitions[from]?.includes(to) ?? false
}

export function transition(state, next) {
  if (!canTransition(state, next)) {
    throw new Error(`INVALID_WORKFLOW_TRANSITION:${state}->${next}`)
  }
  return next
}

export function createCampaign(input = {}) {
  const now = new Date().toISOString()
  return {
    id: input.id || `cmp_${crypto.randomUUID()}`,
    tenantId: input.tenantId || 'tenant_demo_001',
    merchantId: input.merchantId || 'merchant_demo_001',
    storeId: input.storeId || 'store_demo_001',
    brandId: input.brandId || 'brand_demo_001',
    creatorId: input.creatorId || 'usr_demo_001',
    brief: input.brief || '',
    state: WORKFLOW_STATES.DRAFT,
    facts: input.facts || [],
    missingFacts: input.missingFacts || [],
    plan: input.plan || null,
    createdAt: now,
    updatedAt: now,
  }
}

export function createBatch(campaign, input = {}) {
  return {
    id: input.id || `bat_${crypto.randomUUID()}`,
    tenantId: campaign.tenantId,
    campaignId: campaign.id,
    count: input.count || 0,
    completed: 0,
    failed: 0,
    state: WORKFLOW_STATES.DRAFT,
    estimatedCredits: input.estimatedCredits || 0,
    reservedCredits: 0,
    createdAt: new Date().toISOString(),
  }
}

export function createBatchItem(batch, input = {}) {
  return {
    id: input.id || `item_${crypto.randomUUID()}`,
    tenantId: batch.tenantId,
    batchId: batch.id,
    index: input.index ?? 0,
    kind: input.kind || 'IMAGE',
    state: STEP_STATES.PENDING,
    retryCount: 0,
    qaReport: null,
    outputAssetId: null,
    errorCode: null,
  }
}

export function createStep(item, type, input = {}) {
  return {
    id: input.id || `step_${crypto.randomUUID()}`,
    tenantId: item.tenantId,
    itemId: item.id,
    type,
    state: STEP_STATES.PENDING,
    attempt: 0,
    provider: input.provider || null,
    providerTaskId: null,
    idempotencyKey: input.idempotencyKey || null,
    estimatedCost: input.estimatedCost || 0,
    actualCost: null,
  }
}

export function createFactSnapshot(facts = []) {
  return facts.map((fact, index) => ({
    id: fact.id || `fact_${index + 1}`,
    field: fact.field,
    value: fact.value,
    source: fact.source || 'merchant_context',
    sourceRecordId: fact.sourceRecordId || null,
    confirmed: Boolean(fact.confirmed),
    updatedAt: fact.updatedAt || new Date().toISOString(),
  }))
}

export function createIdempotencyKey(scope, key) {
  if (!scope || !key) throw new Error('IDEMPOTENCY_KEY_REQUIRED')
  return `${scope}:${key}`
}

export function createCostLedgerEntry(input) {
  return {
    id: input.id || `cost_${crypto.randomUUID()}`,
    tenantId: input.tenantId,
    workflowRunId: input.workflowRunId,
    itemId: input.itemId || null,
    provider: input.provider,
    modelAlias: input.modelAlias,
    kind: input.kind,
    estimatedCredits: input.estimatedCredits || 0,
    actualCredits: input.actualCredits || 0,
    status: input.status || 'RESERVED',
    createdAt: new Date().toISOString(),
  }
}
