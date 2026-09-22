import test from 'node:test'
import assert from 'node:assert/strict'
import { bandFor, dateKey, dueTasks, matchDraft, nextOccurrence, weekDates } from './publishingPlans.js'
import { seedPlans, seedTasks } from '../data/publishingPlans.js'

test('Shanghai calendar starts Monday and handles month boundaries', () => {
  assert.deepEqual(weekDates('2026-09-17T10:20:48+08:00'), ['2026-09-14', '2026-09-15', '2026-09-16', '2026-09-17', '2026-09-18', '2026-09-19', '2026-09-20'])
  assert.equal(weekDates('2026-09-28T00:00:00+08:00')[6], '2026-10-04')
  assert.equal(weekDates('2026-09-17T10:20:48+08:00', 1)[0], '2026-09-21')
  assert.equal(dateKey('2026-09-16T17:00:00Z'), '2026-09-17')
})
test('next recurring slot skips elapsed times and respects selected weekdays', () => {
  const plan = { time: '18:00', days: [4, 5, 6] }
  assert.deepEqual(nextOccurrence(plan, '2026-09-17T10:00:00+08:00'), { date: '2026-09-18', time: '18:00' })
  assert.deepEqual(nextOccurrence(plan, '2026-09-20T18:01:00+08:00'), { date: '2026-09-25', time: '18:00' })
  assert.equal(nextOccurrence({ ...plan, days: [] }, '2026-09-17'), null)
})
test('matching filters unsupported platforms and prevents reuse on the same account', () => {
  const pool = [{ id: 'one', platforms: ['抖音'] }, { id: 'two', platforms: ['小红书'] }, { id: 'three', platforms: ['抖音'] }]
  const account = { id: 'dy', platform: '抖音' }
  assert.equal(matchDraft(pool, [{ accountId: 'dy', draftId: 'one' }], account, '顺序消耗').id, 'three')
  assert.equal(matchDraft(pool, [], account, '随机消耗', () => 0.99).id, 'three')
  assert.equal(matchDraft(pool, [{ accountId: 'dy', draftId: 'one' }, { accountId: 'dy', draftId: 'three' }], account, '顺序消耗'), null)
})
test('engine only executes due pending tasks with enabled rules or independent schedules', () => {
  const base = { date: '2026-09-17', time: '11:30', status: 'pending' }
  const tasks = [{ ...base, id: 'active', planId: 'on' }, { ...base, id: 'paused', planId: 'off' }, { ...base, id: 'manual' }, { ...base, id: 'future', time: '18:00' }, { ...base, id: 'done', status: 'published' }, { ...base, id: 'blocked', status: 'blocked' }]
  assert.deepEqual(dueTasks(tasks, [{ id: 'on', enabled: true }, { id: 'off', enabled: false }], '2026-09-17T12:00:00+08:00').map(t => t.id), ['active', 'manual'])
  assert.equal(bandFor('11:45'), 'lunch')
  assert.equal(bandFor('18:00'), 'dinner')
  assert.equal(bandFor('21:30'), 'night')
})
test('demo has eight pending works today and rule-linked works honor their rule', () => {
  const plans = seedPlans()
  const tasks = seedTasks()
  assert.equal(plans.filter(p => p.enabled).length, 4)
  assert.equal(tasks.filter(t => t.date === '2026-09-17' && t.status === 'pending').length, 8)
  for (const task of tasks.filter(t => t.planId)) {
    const plan = plans.find(p => p.id === task.planId)
    const weekday = (new Date(`${task.date}T12:00:00+08:00`).getUTCDay() + 6) % 7
    assert.ok(plan.days.includes(weekday))
    assert.ok(plan.accounts.includes(task.accountId))
    assert.equal(plan.store, task.store)
    assert.equal(bandFor(task.time), bandFor(plan.time))
  }
})
