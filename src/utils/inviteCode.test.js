import test from 'node:test'
import assert from 'node:assert/strict'
import { clearInviteCode, pendingInviteCode, rememberInviteCode } from './inviteCode.js'

test('an invite code survives the WeChat round trip for 30 days and is cleared after login', () => {
  const original = globalThis.localStorage
  const store = new Map()
  globalThis.localStorage = { getItem: key => store.get(key) ?? null, setItem: (key, value) => store.set(key, value), removeItem: key => store.delete(key) }
  try {
    const day = 24 * 60 * 60 * 1000
    assert.equal(rememberInviteCode(' ab3k9xyz ', 0), 'AB3K9XYZ')
    assert.equal(pendingInviteCode(29 * day), 'AB3K9XYZ')
    assert.equal(pendingInviteCode(30 * day), '')
    assert.equal(rememberInviteCode('<script>', 0), '')
    assert.equal(rememberInviteCode(undefined, 0), '')
    assert.equal(pendingInviteCode(1), 'AB3K9XYZ', 'an invalid link does not replace a saved code')
    clearInviteCode()
    assert.equal(pendingInviteCode(1), '')
    store.set('wuyao-invite-code', 'not json')
    assert.equal(pendingInviteCode(1), '')
  } finally {
    if (original === undefined) delete globalThis.localStorage
    else globalThis.localStorage = original
  }
})
