import test from 'node:test'
import assert from 'node:assert/strict'
import { memberGateState } from './memberGate.js'

const base = { authenticated: true, aiCreationHidden: false, routeName: 'image-create', summary: { memberActive: false }, error: '' }

test('accounts without membership can only open billing and settings on the AI creation site', () => {
  assert.equal(memberGateState(base), 'locked')
  for (const routeName of ['video-create', 'works', 'tasks', 'team', 'referral', 'notifications', 'dashboard'])
    assert.equal(memberGateState({ ...base, routeName }), 'locked', routeName)
  for (const routeName of ['billing', 'settings', 'forbidden', 'not-found'])
    assert.equal(memberGateState({ ...base, routeName }), '', routeName)
})

test('members, guests, the main domain and an unreadable account are not locked', () => {
  assert.equal(memberGateState({ ...base, summary: { memberActive: true } }), '')
  assert.equal(memberGateState({ ...base, authenticated: false }), '')
  assert.equal(memberGateState({ ...base, aiCreationHidden: true }), '')
  assert.equal(memberGateState({ ...base, summary: null, error: '账户暂时无法读取' }), '')
})

test('pages wait for the account instead of flashing content before locking', () => {
  assert.equal(memberGateState({ ...base, summary: null }), 'loading')
})
