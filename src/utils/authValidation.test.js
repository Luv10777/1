import test from 'node:test'
import assert from 'node:assert/strict'
import { phoneError, accountError, passwordError, codeError, safeRedirect } from './authValidation.js'

test('rejects invalid phone numbers and unsupported country codes', () => {
  assert.equal(phoneError(' 13800138000 '), '')
  for (const value of ['', '12800138000', '1380013800', '138001380000', '1380013800a']) {
    assert.ok(phoneError(value))
  }
  assert.ok(phoneError('61234567', '+852'))
})

test('validates username and numeric mobile accounts', () => {
  assert.equal(accountError('growth.team'), '')
  assert.equal(accountError('13800138000'), '')
  for (const value of ['', 'a', 'a b', '12345', 'a'.repeat(65)]) assert.ok(accountError(value))
})

test('password length boundaries preserve spaces and code requires six digits', () => {
  assert.ok(passwordError('1234567'))
  assert.equal(passwordError('12345678'), '')
  assert.equal(passwordError('a'.repeat(64)), '')
  assert.ok(passwordError('a'.repeat(65)))
  assert.equal(codeError('012345'), '')
  for (const value of ['12345', '1234567', '12345a', ' 123456']) assert.ok(codeError(value))
})

test('redirects stay within the application and avoid login loops', () => {
  assert.equal(safeRedirect('/works?filter=draft'), '/works?filter=draft')
  for (const value of [undefined, '//example.com', 'https://example.com', '/\\example.com', '/login?mode=register']) {
    assert.equal(safeRedirect(value), '/dashboard')
  }
})
