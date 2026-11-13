import test from 'node:test'
import assert from 'node:assert/strict'
import { codesCsv, membershipState, planBatchTransfer, runBatchTransfers } from './partnerOps.js'

const now = Date.parse('2026-11-13T00:00:00Z')

test('membership state warns within seven days and separates expired from never activated', () => {
  assert.deepEqual(membershipState({ memberActive: null }, now), { text: '—', tone: '' })
  assert.equal(membershipState({ memberActive: false, memberUntil: null }, now).text, '从未开通')
  assert.match(membershipState({ memberActive: false, memberUntil: '2026-11-01T00:00:00Z' }, now).text, /^已过期/)
  const soon = membershipState({ memberActive: true, memberUntil: '2026-11-18T00:00:00Z', planName: '月卡' }, now)
  assert.equal(soon.tone, 'warn')
  assert.match(soon.text, /月卡 .* 到期（剩 5 天）/)
  assert.equal(membershipState({ memberActive: true, memberUntil: '2026-12-30T00:00:00Z' }, now).tone, '')
})

test('batch transfers skip blank rows, reject bad numbers and totals above the balance', () => {
  const rows = [{ tenantId: 1, tenantName: '甲', credits: '2000' }, { tenantId: 2, tenantName: '乙', credits: '' }, { tenantId: 3, tenantName: '丙', credits: 2000 }]
  assert.deepEqual(planBatchTransfer(rows, 50000), { entries: [{ ...rows[0], credits: 2000 }, { ...rows[2], credits: 2000 }], total: 4000, error: '' })
  assert.match(planBatchTransfer(rows, 3000).error, /超过可分配的 3,000/)
  assert.match(planBatchTransfer([{ tenantId: 1, tenantName: '甲', credits: '1.5' }], 10).error, /「甲」/)
  assert.match(planBatchTransfer([{ tenantId: 1, credits: ' ' }], 10).error, /至少/)
})

test('a failed transfer keeps going and reports per tenant with its own request key', async () => {
  const calls = []
  const results = await runBatchTransfers([{ tenantId: 1, credits: 5 }, { tenantId: 2, credits: 6 }], { 1: 'k1', 2: 'k2' }, async (id, body) => {
    calls.push([id, body])
    if (id === 1) throw new Error('网络中断')
    return { tenantId: id }
  })
  assert.deepEqual(calls, [[1, { requestKey: 'k1', credits: 5 }], [2, { requestKey: 'k2', credits: 6 }]])
  assert.deepEqual(results.map(r => [r.tenantId, r.ok, r.error]), [[1, false, '网络中断'], [2, true, '']])
})

test('code export is excel friendly and quotes notes with commas', () => {
  const csv = codesCsv([{ code: 'ABCD', planName: '月卡', durationDays: 30, status: 'UNUSED', createdBy: 9, note: '张三,李四', createdAt: 't' }],
    { statuses: { UNUSED: '未使用' }, creators: { 9: '小周' } })
  assert.ok(csv.startsWith('﻿激活码,套餐'))
  assert.match(csv, /\r\nABCD,月卡,30,未使用,小周,,,"张三,李四",t\r\n$/)
})
