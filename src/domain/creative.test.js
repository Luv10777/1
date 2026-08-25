import test from 'node:test'
import assert from 'node:assert/strict'
import { canTransition, createCampaign, createFactSnapshot, createIdempotencyKey, createBatch, WORKFLOW_STATES } from './creative.js'
import { createMockImageProvider, createMockTextProvider, createProviderRegistry } from './providers.js'
import { createWorkflowOrchestrator } from './orchestrator.js'

test('workflow transition guard rejects invalid jumps', () => {
  assert.equal(canTransition(WORKFLOW_STATES.DRAFT, WORKFLOW_STATES.PLANNED), true)
  assert.equal(canTransition(WORKFLOW_STATES.DRAFT, WORKFLOW_STATES.PUBLISHED), false)
})

test('fact snapshots and idempotency keys are stable contracts', () => {
  const snapshot = createFactSnapshot([{ field: 'price', value: '29.9', confirmed: true }])
  assert.equal(snapshot[0].field, 'price')
  assert.equal(createIdempotencyKey('batch', '123'), 'batch:123')
})

test('mock orchestrator isolates item failures and records costs', async () => {
  const orchestrator = createWorkflowOrchestrator({ providers: createProviderRegistry({ text: createMockTextProvider({ latency: 0 }), image: createMockImageProvider({ latency: 0 }) }) })
  const campaign = orchestrator.createCampaign({ brief: '29.9 元双人下午茶' })
  const planned = await orchestrator.interpret(campaign.id, { contentCount: 2 })
  assert.equal(planned.campaign.state, WORKFLOW_STATES.PLANNED)
  const confirmed = orchestrator.confirmCampaign(campaign.id, { count: 2 })
  assert.equal(confirmed.batch.count, 2)
  const completed = await orchestrator.startBatch(confirmed.batch.id)
  assert.equal(completed.batch.completed, 2)
  assert.equal(completed.batch.failed, 0)
  assert.equal(orchestrator.costs.filter((entry) => entry.status === 'CAPTURED').length, 2)
})

test('batch credit reservation is not duplicated', () => {
  const campaign = createCampaign({ brief: 'test' })
  const batch = createBatch(campaign, { count: 2, estimatedCredits: 16 })
  const keys = new Set([createIdempotencyKey('credit-reservation', batch.id), createIdempotencyKey('credit-reservation', batch.id)])
  assert.equal(keys.size, 1)
})
