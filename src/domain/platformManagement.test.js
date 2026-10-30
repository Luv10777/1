import test from 'node:test'
import assert from 'node:assert/strict'
import { IDENTITY_PROMPT_SNOOZE_MS, identityValidation, managementDate, shouldPromptIdentity } from './platformManagement.js'

const valid = { legalName: '测试用户', idNumber: '11010519491231002X', declarationAccepted: true }
test('identity form rejects malformed dates, checksum errors and missing consent before submitting', () => {
  assert.equal(identityValidation(valid), '')
  assert.equal(identityValidation({ ...valid, idNumber: valid.idNumber.toLowerCase() }), '')
  assert.match(identityValidation({ ...valid, idNumber: '11010519490231002X' }), /出生日期/)
  assert.match(identityValidation({ ...valid, idNumber: '110105194912310021' }), /校验/)
  assert.match(identityValidation({ ...valid, idNumber: '123' }), /18位/)
  assert.match(identityValidation({ ...valid, declarationAccepted: false }), /确认/)
  assert.match(identityValidation({ ...valid, legalName: '<script>' }), /真实姓名/)
})
test('missing management timestamps never render Invalid Date', () => {
  assert.equal(managementDate(null), '—')
  assert.equal(managementDate('invalid'), '—')
  assert.match(managementDate('2026-10-15T08:00:00Z'), /2026/)
})
test('the real-name prompt stays out of live, payment and settings pages and sleeps for a day after "later"', () => {
  const now = Date.parse('2026-10-30T16:00:00Z')
  assert.equal(shouldPromptIdentity({ missing: true, routeName: 'image-create', dismissedAt: null, now }), true)
  assert.equal(shouldPromptIdentity({ missing: false, routeName: 'image-create', dismissedAt: null, now }), false)
  for (const routeName of ['digital-human', 'billing', 'settings']) assert.equal(shouldPromptIdentity({ missing: true, routeName, dismissedAt: null, now }), false)
  assert.equal(shouldPromptIdentity({ missing: true, routeName: 'image-create', dismissedAt: now - 60_000, now }), false)
  assert.equal(shouldPromptIdentity({ missing: true, routeName: 'image-create', dismissedAt: now - IDENTITY_PROMPT_SNOOZE_MS, now }), true)
  assert.equal(shouldPromptIdentity({ missing: true, routeName: 'image-create', dismissedAt: 'garbage', now }), true)
})
