import test from 'node:test'
import assert from 'node:assert/strict'
import { ledgerChange } from './ledgerAdminApi.js'

test('ledger changes show what each entry did to the available credits', () => {
  assert.deepEqual(ledgerChange({ type: 'RESERVE', availableDelta: -120, heldDelta: 120 }), { text: '-120', tone: 'hold' })
  assert.deepEqual(ledgerChange({ type: 'CAPTURE', availableDelta: 0, heldDelta: -120 }), { text: '-120', tone: 'out' })
  assert.deepEqual(ledgerChange({ type: 'RELEASE', availableDelta: 3, heldDelta: -3 }), { text: '+3', tone: 'in' })
  assert.deepEqual(ledgerChange({ type: 'PURCHASE', availableDelta: 100, heldDelta: 0 }), { text: '+100', tone: 'in' })
  assert.deepEqual(ledgerChange({ type: 'GRANT', availableDelta: 3000, heldDelta: 0 }), { text: '+3000', tone: 'in' })
})
