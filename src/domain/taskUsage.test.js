import test from 'node:test'
import assert from 'node:assert/strict'
import { taskLogQuery, taskDate, taskModel, taskStatus, taskResultLink } from './taskUsage.js'

test('task queries use Beijing time regardless of the browser timezone and omit empty filters', () => {
  assert.deepEqual(taskLogQuery({ from: '2026-10-15T00:00', until: '2026-10-16T00:00', model: ' Seedance 2.0 ', module: '', status: '' }), {
    page: 0, size: 20, from: '2026-10-14T16:00:00.000Z', until: '2026-10-15T16:00:00.000Z', model: 'Seedance 2.0',
  })
  assert.deepEqual(taskLogQuery({}), { page: 0, size: 20 })
  assert.throws(() => taskLogQuery({ from: '2026-10-15T00:00', until: '2026-10-15T00:00' }), /结束时间/)
  assert.throws(() => taskLogQuery({ from: 'invalid date' }), /有效/)
})
test('model labels preserve unknown provider IDs and timestamp precision', () => {
  assert.equal(taskModel('seedance-2.0'), 'Seedance 2.0')
  assert.equal(taskModel('SEEDANCE_2_0_FAST'), 'Seedance 2.0 Fast')
  assert.equal(taskModel('gpt-image-2, provider-image-v9'), 'gpt-image-2 / provider-image-v9')
  assert.equal(taskModel(null), '未记录模型')
  assert.equal(taskDate('2026-10-14T16:01:02Z'), '2026-10-15 00:01:02')
  assert.equal(taskStatus('UPSTREAM_UNKNOWN').label, '待核对')
})
test('result links address the original image creation or video analysis', () => {
  assert.deepEqual(taskResultLink({ module: 'IMAGE_GENERATION', workflow: 'PRODUCT_SET', taskId: 21 }), {
    path: '/image/create/product-set', query: { creation: 21 },
  })
  assert.deepEqual(taskResultLink({ module: 'VIDEO_ANALYSIS', taskId: 6 }), { path: '/video/analyze', query: { history: 6 } })
})
