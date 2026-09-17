import test from 'node:test'
import assert from 'node:assert/strict'
import { authorizationStatus, canPublishAccount, createMockAccounts, filterAccounts, remainingDays } from './platformAccounts.js'

const now = Date.parse('2026-09-17T00:00:00Z')
test('fixtures match five accounts, three platforms and one renewal warning', () => {
  const list = createMockAccounts(now)
  assert.equal(list.length, 5)
  assert.equal(new Set(list.map(a => a.platform)).size, 3)
  assert.equal(list.filter(a => a.platform === '抖音').length, 2)
  assert.equal(list.filter(a => authorizationStatus(a, now) === 'expiring').length, 1)
  assert.ok(list.every(a => canPublishAccount(a, now)))
  assert.equal(remainingDays(list[0], now), 45)
})
test('authorization expires at its exact boundary and revoked accounts cannot publish', () => {
  const account = createMockAccounts(now)[0]
  assert.equal(authorizationStatus({ ...account, expiresAt: new Date(now + 7 * 86400000).toISOString() }, now), 'active')
  assert.equal(authorizationStatus({ ...account, expiresAt: new Date(now + 6 * 86400000).toISOString() }, now), 'expiring')
  assert.equal(canPublishAccount(account, Date.parse(account.expiresAt)), false)
  assert.equal(canPublishAccount({ ...account, revoked: true }, now), false)
})
test('publishing permission is required for every connected account', () => {
  const list = createMockAccounts(now)
  assert.equal(canPublishAccount({ ...list[0], permissions: ['数据回流'] }, now), false)
  assert.ok(list.every(a => canPublishAccount(a, now)))
})
test('platform and trimmed, case-insensitive account search combine without changing fixtures', () => {
  const list = createMockAccounts(now)
  assert.equal(filterAccounts(list, '全部', ' 西湖 ').length, 1)
  assert.equal(filterAccounts(list, '抖音', ' 西湖 ').length, 0)
  assert.equal(filterAccounts(list, '视频号', ' 7740912 ').length, 2)
  assert.equal(filterAccounts(list, '全部', 'no-such-account').length, 0)
  assert.equal(list.length, 5)
})
