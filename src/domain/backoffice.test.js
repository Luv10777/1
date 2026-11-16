import test from 'node:test'
import assert from 'node:assert/strict'
import { moneyMinor, localDateTime, instantFromLocal } from './backoffice.js'
import { canAdminPage } from './adminPermissions.js'

test('currency entry preserves cents and rejects rounding, non-finite values and unsupported amounts', () => {
  for (const [input, expected] of [['0.01', 1], ['500.1', 50010], ['500.00', 50000], ['1000000', 100000000]]) assert.equal(moneyMinor(input), expected)
  for (const input of ['', '0', '-1', '0.001', '1e3', 'Infinity', '1000000.01', 'abc']) assert.equal(moneyMinor(input), null)
})
test('follow-up date round-trips in the local timezone and can explicitly clear an appointment', () => {
  assert.equal(instantFromLocal(localDateTime('2026-11-15T03:30:00Z')), '2026-11-15T03:30:00.000Z')
  assert.equal(instantFromLocal(''), null)
})
test('administrator menus separate finance, reviews, support and role management', () => {
  assert.equal(canAdminPage('admin-orders', ['REVIEW']), false)
  assert.equal(canAdminPage('admin-orders', ['FINANCE']), true)
  assert.equal(canAdminPage('admin-security', ['FINANCE']), false)
  assert.equal(canAdminPage('admin-security', ['SECURITY']), true)
  assert.equal(canAdminPage('admin-users', ['USERS']), true)
  assert.equal(canAdminPage('admin-plans', ['USERS']), false)
  assert.equal(canAdminPage('admin-merchants', ['USERS']), true)
  assert.equal(canAdminPage('unknown', ['SECURITY']), false)
})
