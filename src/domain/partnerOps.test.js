import test from 'node:test'
import assert from 'node:assert/strict'
import { codesCsv, lookupVerdict, memberVerdict, membershipState, planBatchTransfer, renewedUntil, runBatchTransfers, tenantTimeline, transferError } from './partnerOps.js'

const now = Date.parse('2026-11-13T00:00:00Z')

test('membership state warns within seven days and separates expired from never activated', () => {
  assert.deepEqual(membershipState({ memberActive: null }, now), { text: '—', short: '—', tone: '' })
  assert.equal(membershipState({ memberActive: false, memberUntil: null }, now).text, '从未开通')
  assert.match(membershipState({ memberActive: false, memberUntil: '2026-11-01T00:00:00Z' }, now).text, /^已过期/)
  const soon = membershipState({ memberActive: true, memberUntil: '2026-11-18T00:00:00Z', planName: '月卡' }, now)
  assert.equal(soon.tone, 'warn')
  assert.equal(soon.short, '剩 5 天')
  assert.match(soon.text, /月卡 .* 到期（剩 5 天）/)
  assert.equal(membershipState({ memberActive: true, memberUntil: '2026-12-30T00:00:00Z' }, now).tone, 'ok')
})

test('renewal extends an active membership from its end and an expired one from now', () => {
  assert.equal(renewedUntil({ memberActive: true, memberUntil: '2026-11-18T00:00:00Z' }, 30, now).toISOString(), '2026-12-18T00:00:00.000Z')
  assert.equal(renewedUntil({ memberActive: false, memberUntil: '2026-11-01T00:00:00Z' }, 30, now).toISOString(), '2026-12-13T00:00:00.000Z')
  assert.equal(renewedUntil({ memberActive: false }, 365, now).toISOString(), '2027-11-13T00:00:00.000Z')
})

test('single transfers need a whole number within the balance', () => {
  assert.match(transferError('', 100), /请输入/)
  assert.match(transferError('1.5', 100), /整数/)
  assert.match(transferError('200', 100), /超过可分配的 100/)
  assert.equal(transferError(' 100 ', 100), '')
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

test('tenant timeline merges transfers, codes and renewals newest first', () => {
  const rows = tenantTimeline({
    transfers: [{ id: 1, delta: -2000, note: '活动', createdAt: '2026-11-10T00:00:00Z' }],
    codes: [{ id: 5, planName: '月卡', durationDays: 30, note: '直接续期', redeemedAt: '2026-11-12T00:00:00Z' },
      { id: 4, planName: '月卡', durationDays: 30, note: null, redeemedAt: '2026-11-01T00:00:00Z' }],
  })
  assert.deepEqual(rows.map(r => [r.kind, r.title]), [['renew', '直接续期 · 月卡 30 天'], ['transfer', '分配 2,000 烟火'], ['code', '兑换激活码 · 月卡 30 天']])
})

test('lookup verdicts never reveal another partner and only allow free tenants or accounts', () => {
  assert.equal(lookupVerdict({ tenantState: 'NONE' }, true).can, true)
  assert.equal(lookupVerdict({ tenantState: 'OTHER' }, true).can, false)
  assert.match(lookupVerdict({ tenantState: 'TEAM', bdName: '小周' }, false).text, /小周/)
  assert.equal(memberVerdict({ memberState: 'NONE' }).can, true)
  assert.equal(memberVerdict({ memberState: 'BD' }).can, false)
})

test('code export is excel friendly and quotes notes with commas', () => {
  const csv = codesCsv([{ code: 'ABCD', planName: '月卡', durationDays: 30, status: 'UNUSED', createdBy: 9, note: '张三,李四', createdAt: 't' }],
    { statuses: { UNUSED: '未使用' }, creators: { 9: '小周' } })
  assert.ok(csv.startsWith('﻿激活码,套餐'))
  assert.match(csv, /\r\nABCD,月卡,30,未使用,小周,,,"张三,李四",t\r\n$/)
})
