import test from 'node:test'
import assert from 'node:assert/strict'
import { taskLogQuery, taskDate, taskModel, taskStatus, taskResultLink, taskElapsed, taskModules, taskWorkflow } from './taskUsage.js'

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

test('creative models have the same public names across all task outcomes', () => {
  assert.equal(taskModel('internal-image-v9', 'IMAGE_GENERATION'), '一方志Image')
  assert.equal(taskModel(null, 'IMAGE_GENERATION'), '一方志Image')
  assert.equal(taskModel('gpt-6-luna', 'VIDEO_ANALYSIS'), '一方志反推')
  assert.equal(taskModel(null, 'VIDEO_PLAN'), '一方志提示词')
  assert.equal(taskModel('seedance-2.0-fast', 'VIDEO_GENERATION'), 'Seedance 2.0 Fast')
  assert.equal(taskModules.VIDEO_PLAN, '视频方案')
  assert.equal(taskWorkflow({ module: 'VIDEO_PLAN', workflow: 'REGENERATE' }), '换一批方案')
})

test('elapsed time distinguishes active tasks from completed tasks and missing history', () => {
  assert.equal(taskElapsed({ previewElapsedMillis: 55000, elapsedMillis: 118000, status: 'RUNNING' }), '55 秒')
  assert.equal(taskElapsed({ previewElapsedMillis: 55000, elapsedMillis: 118000, taskStatus: 'SUCCEEDED' }), '55 秒')
  assert.equal(taskElapsed({ previewElapsedMillis: null, elapsedMillis: 118000, status: 'SUCCEEDED' }), '1 分 58 秒')
  assert.equal(taskElapsed({ elapsedMillis: 900, status: 'SUCCEEDED' }), '不足 1 秒')
  assert.equal(taskElapsed({ elapsedMillis: 4200, status: 'FAILED' }), '4 秒')
  assert.equal(taskElapsed({ elapsedMillis: 60000, status: 'SUCCEEDED' }), '1 分')
  assert.equal(taskElapsed({ elapsedMillis: 125000, status: 'SUCCEEDED' }), '2 分 5 秒')
  assert.equal(taskElapsed({ elapsedMillis: 3600000, status: 'CANCELLED' }), '1 小时')
  assert.equal(taskElapsed({ elapsedMillis: 3661000, taskStatus: 'RUNNING' }), '已用 1 小时 1 分 1 秒')
  assert.equal(taskElapsed({ elapsedMillis: null, status: 'SUCCEEDED' }), '—')
  assert.equal(taskElapsed({ elapsedMillis: -100 }), '—')
  assert.equal(taskElapsed({ elapsedMillis: NaN }), '—')
})
