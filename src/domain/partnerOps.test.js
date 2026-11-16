import test from 'node:test'
import assert from 'node:assert/strict'
import { accountSplit, displayNameError, followUpTodos, grantError, greeting, groupTodos, memberSegments, percent, permissionText, capabilityBars, niceMax, sinceText, codesCsv, lookupVerdict, membershipState, planBatchTransfer, renewedUntil, runBatchTransfers, tenantTimeline, transferError } from './partnerOps.js'

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
    transfers: [{ id: 1, credits: 2000, source: 'ACCOUNT', status: 'DONE', note: '活动', actorName: '小周', createdAt: '2026-11-10T00:00:00Z' }],
    codes: [{ id: 5, planName: '月卡', durationDays: 30, note: '直接续期', redeemedAt: '2026-11-12T00:00:00Z' },
      { id: 4, planName: '月卡', durationDays: 30, note: null, redeemedAt: '2026-11-01T00:00:00Z' }],
  })
  assert.deepEqual(rows.map(r => [r.kind, r.title]), [['renew', '直接续期 · 月卡 30 天'], ['transfer', '分配 2,000 烟火 · 小周'], ['code', '兑换激活码 · 月卡 30 天']])
})

test('lookup verdicts never reveal another partner and only allow free tenants', () => {
  assert.equal(lookupVerdict({ tenantState: 'NONE' }).can, true)
  assert.equal(lookupVerdict({ tenantState: 'MINE' }).can, false)
  assert.equal(lookupVerdict({ tenantState: 'OTHER' }).can, false)
  assert.doesNotMatch(lookupVerdict({ tenantState: 'OTHER' }).text, /服务商「/)
})

test('members can only hand out permissions they hold and never the money ones', () => {
  const owner = { role: 'OWNER' }
  const manager = { role: 'MEMBER', permissions: { tenants: true, codes: false, distribute: true, finance: false, team: true } }
  const none = { tenants: false, codes: false, distribute: false, finance: false, team: false }
  assert.equal(grantError(owner, none, { ...none, distribute: true, finance: true }, null, 500), '')
  assert.match(grantError(manager, none, { ...none, distribute: true }), /只有负责人/)
  assert.match(grantError(manager, { ...none, distribute: true }, { ...none, distribute: true }, 500, 800), /只有负责人/)
  assert.match(grantError(manager, none, { ...none, codes: true }), /激活码/)
  assert.equal(grantError(manager, none, { ...none, tenants: true, team: true }), '')
  assert.equal(grantError(manager, { ...none, codes: true }, { ...none, codes: true }), '')
  assert.equal(permissionText({ role: 'OWNER' }), '全部')
  assert.equal(permissionText({ role: 'MEMBER', permissions: { tenants: true, finance: true } }), '商户、财务查看')
  assert.equal(permissionText({ role: 'MEMBER', permissions: none }), '无')
})

test('team names are 1 to 20 characters and greetings follow the hour', () => {
  assert.match(displayNameError('  '), /请填写/)
  assert.match(displayNameError('一'.repeat(21)), /20/)
  assert.equal(displayNameError(' 小周 '), '')
  assert.deepEqual([3, 8, 12, 15, 20].map(greeting), ['夜深了', '早上好', '中午好', '下午好', '晚上好'])
})

test('code export is excel friendly and quotes notes with commas', () => {
  const csv = codesCsv([{ code: 'ABCD', planName: '月卡', durationDays: 30, status: 'UNUSED', createdBy: 9, note: '张三,李四', createdAt: 't' }],
    { statuses: { UNUSED: '未使用' }, creators: { 9: '小周' } })
  assert.ok(csv.startsWith('﻿激活码,套餐'))
  assert.match(csv, /\r\nABCD,月卡,30,未使用,小周,,,"张三,李四",t\r\n$/)
})

test('dashboard helpers rank capabilities, pick a round axis maximum and describe recency', () => {
  assert.deepEqual(capabilityBars({ IMAGE: 5, VIDEO: 900, COPY: 0 }).map(r => [r.label, r.ratio]), [['视频创作', 1], ['图片创作', 5 / 900]])
  assert.equal(niceMax([0, 0]), 1)
  assert.equal(niceMax([3, 905, 12]), 1000)
  assert.equal(niceMax([140]), 200)
  assert.equal(sinceText(null, now), '从未')
  assert.equal(sinceText('2026-11-12T10:00:00Z', now), '今天')
  assert.equal(sinceText('2026-11-03T00:00:00Z', now), '10 天前')
})

test('to-dos merge per tenant, most urgent first, and filter by reason', () => {
  const t1 = { tenantId: 1, memberUntil: '2026-11-20T00:00:00Z' }, t2 = { tenantId: 2, memberUntil: '2026-11-15T00:00:00Z' }, t3 = { tenantId: 3 }
  const rows = groupTodos([{ type: 'LOW_BALANCE', tenant: t1 }, { type: 'IDLE', tenant: t3 }, { type: 'EXPIRING', tenant: t1 }, { type: 'EXPIRING', tenant: t2 }])
  assert.deepEqual(rows.map(r => [r.tenant.tenantId, r.reasons.map(x => x.type)]), [[2, ['EXPIRING']], [1, ['EXPIRING', 'LOW_BALANCE']], [3, ['IDLE']]])
  assert.deepEqual(groupTodos([{ type: 'LOW_BALANCE', tenant: t1 }, { type: 'IDLE', tenant: t3 }], 'IDLE').map(r => r.tenant.tenantId), [3])
})

test('booked follow-ups join the to-do list, keeping the richer tenant row from the workbench', () => {
  const due = [
    { tenantId: 1, tenantName: '花间小馆', profile: { contactName: '王老板', nextFollowUpAt: '2026-11-15T05:00:00Z' } },
    { tenantId: 4, tenantName: '青禾花店', profile: { contactName: '', nextFollowUpAt: '2026-11-18T05:00:00Z' } },
    { tenantId: 5, tenantName: '没约时间', profile: { nextFollowUpAt: null } },
  ]
  const todos = followUpTodos(due)
  assert.deepEqual(todos.map(t => [t.tenant.tenantId, t.type, t.tenant.ownerName, t.tenant.followUpOnly]), [[1, 'FOLLOW_UP', '王老板', true], [4, 'FOLLOW_UP', null, true]])
  const rows = groupTodos([{ type: 'IDLE', tenant: { tenantId: 1, ownerName: '王某', addedByName: '小林' } }, ...todos])
  assert.deepEqual(rows.map(r => [r.tenant.tenantId, r.reasons.map(x => x.type)]), [[1, ['FOLLOW_UP', 'IDLE']], [4, ['FOLLOW_UP']]])
  assert.equal(rows[0].tenant.addedByName, '小林')
  assert.deepEqual(followUpTodos(null), [])
})

test('member segments split active from expiring and percentages handle zero', () => {
  const segs = memberSegments({ activeMembers: 5, expiringSoon: 2, expiredMembers: 1, neverActivated: 2 })
  assert.deepEqual(segs.map(s => [s.key, s.value]), [['active', 3], ['expiring', 2], ['expired', 1], ['never', 2]])
  assert.equal(segs[0].ratio, 3 / 8)
  assert.equal(percent(1, 4), '25%')
  assert.equal(percent(1, 0), '—')
})

test('team account credits go out gift first', () => {
  const account = { available: 800, gift: 300, purchased: 500 }
  assert.deepEqual(accountSplit(account, 500), { gift: 300, purchased: 200 })
  assert.deepEqual(accountSplit(account, 100), { gift: 100, purchased: 0 })
})
