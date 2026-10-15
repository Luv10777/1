import test from 'node:test'
import assert from 'node:assert/strict'
import { identityValidation, managementDate } from './platformManagement.js'

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
