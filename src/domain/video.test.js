import test from 'node:test'
import assert from 'node:assert/strict'
import { createMockVideoProvider, createVideoBrief, createVideoTaskSpec, runVideoConsistencyQa } from './video.js'

test('video spec locks storyboard characters and constraints', () => {
  const brief = createVideoBrief({ durationSeconds: 15, constraints: ['不改变产品外观'] })
  const spec = createVideoTaskSpec(brief)
  assert.equal(spec.lockedCharacters[0], 'host')
  assert.equal(spec.lockedConstraints[0], '不改变产品外观')
  assert.equal(spec.storyboard.length, 4)
})

test('video QA blocks character drift', () => {
  const spec = createVideoTaskSpec(createVideoBrief({ durationSeconds: 15 }))
  const report = runVideoConsistencyQa({ spec, output: { assetId: 'asset_1', durationSeconds: 15, characterIds: [] } })
  assert.equal(report.status, 'BLOCKED')
  assert.equal(report.issues[0].code, 'CHARACTER_DRIFT')
})

test('mock video provider exposes async task contract', async () => {
  const provider = createMockVideoProvider({ latency: 0 })
  const spec = createVideoTaskSpec(createVideoBrief({ durationSeconds: 15 }))
  const task = await provider.create(spec)
  const status = await provider.getStatus(task.taskId)
  assert.equal(status.status, 'SUCCEEDED')
  assert.equal(status.output.durationSeconds, 15)
})
