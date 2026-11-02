import test from 'node:test'
import assert from 'node:assert/strict'
import { formatRedeemCode, redeemCodeExpiry } from './redeemCodeAdminApi.js'

test('redeem codes are grouped in fours for copying', () => {
  assert.equal(formatRedeemCode('ABCDEFGHJKLMNPQR'), 'ABCD-EFGH-JKLM-NPQR')
  assert.equal(formatRedeemCode(''), '')
})

test('an expiry date lasts until the end of that local day', () => {
  assert.equal(redeemCodeExpiry(''), null)
  const expiry = new Date(redeemCodeExpiry('2026-12-31'))
  assert.deepEqual([expiry.getFullYear(), expiry.getMonth(), expiry.getDate(), expiry.getHours(), expiry.getMinutes()], [2026, 11, 31, 23, 59])
})
