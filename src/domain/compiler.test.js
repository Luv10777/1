import test from 'node:test'
import assert from 'node:assert/strict'
import { createCreativeCompiler, validateIntent } from './compiler.js'
import { createMockTextProvider } from './providers.js'

test('intent schema rejects unknown and malformed output', () => {
  const result = validateIntent({ intent: 'x', audience: '', channels: [], contentCount: 0, tone: 'x', secret: true })
  assert.equal(result.valid, false)
  assert.ok(result.errors.includes('unknown:secret'))
})

test('creative compiler blocks missing confirmed facts', async () => {
  const compiler = createCreativeCompiler({ providers: { text: createMockTextProvider({ latency: 0 }) } })
  const result = await compiler.compile({ brief: '做一组活动图', requiredFacts: ['price'], facts: [] })
  assert.equal(result.blocked, true)
  assert.deepEqual(result.missingFacts, ['price'])
})

test('creative compiler emits plan, prompt artifacts and QA report', async () => {
  const compiler = createCreativeCompiler({ providers: { text: createMockTextProvider({ latency: 0 }) } })
  const result = await compiler.compile({ brief: '做一组活动图', contentCount: 3, facts: [{ field: 'price', value: '29.9', confirmed: true }] })
  assert.equal(result.blocked, false)
  assert.equal(result.plan.matrix.length, 3)
  assert.equal(result.promptArtifacts.length, 3)
  assert.equal(result.qaReport.blockingIssues, 0)
})
