import { test } from 'node:test'
import assert from 'node:assert/strict'
import { commerceSnapshot, loadAccountCommerce } from './publishingCommerce.js'

test('merchant packages are scoped to both the Douyin account and current store', () => {
  const record = { authorized: true, enabled: true, packages: [
    { id: 'valid', accountId: 'a', store: 'store-1' },
    { id: 'other-account', accountId: 'b', store: 'store-1' },
    { id: 'other-store', accountId: 'a', store: 'store-2' },
  ] }
  const result = commerceSnapshot({ id: 'a', platform: '抖音' }, 'store-1', record)
  assert.equal(result.state, 'ready')
  assert.deepEqual(result.packages.map(p => p.id), ['valid'])
})

test('unavailable merchant states never expose packages', () => {
  const account = { id: 'a', platform: '抖音' }
  for (const [record, state] of [
    [null, 'not_connected'], [{ authorized: false }, 'expired'],
    [{ authorized: true, enabled: false }, 'not_enabled'],
    [{ authorized: true, error: true }, 'error'],
    [{ authorized: true, enabled: true, packages: [] }, 'empty'],
  ]) {
    const result = commerceSnapshot(account, 'store-1', record)
    assert.equal(result.state, state)
    assert.deepEqual(result.packages, [])
  }
  assert.equal(commerceSnapshot({ platform: '小红书' }, 'store-1', {}).state, 'no_account')
})

test('demo adapter distinguishes enabled, unopened and unknown accounts', async () => {
  assert.equal((await loadAccountCommerce({ id: 'dy-main', platform: '抖音' }, '炭火烧鸟·湖滨店')).state, 'ready')
  assert.equal((await loadAccountCommerce({ id: 'dy-food', platform: '抖音' }, '炭火烧鸟·湖滨店')).state, 'not_enabled')
  assert.equal((await loadAccountCommerce({ id: 'dy-main', platform: '抖音' }, '炭火烧鸟·钱江店')).state, 'empty')
  assert.equal((await loadAccountCommerce({ id: 'new', platform: '抖音' }, '炭火烧鸟·湖滨店')).state, 'not_connected')
})
