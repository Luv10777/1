import test from 'node:test'
import assert from 'node:assert/strict'
import { createUiMode } from './uiMode.js'

const memoryStorage = () => { const values = new Map(); return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) } }

test('defaults to the full interface and remembers the choice per account', () => {
  const storage = memoryStorage()
  const mode = createUiMode(storage)
  mode.setAccount('7:1')
  assert.equal(mode.current.value, 'pro')
  mode.set('novice')
  assert.equal(mode.isNovice, true)
  mode.setAccount('7:2')
  assert.equal(mode.current.value, 'pro', 'another account keeps its own default')
  mode.setAccount('7:1')
  assert.equal(mode.current.value, 'novice')
})

test('ignores unknown modes and survives unavailable storage', () => {
  const mode = createUiMode({ getItem() { throw new Error('blocked') }, setItem() { throw new Error('blocked') } })
  mode.setAccount('7:1')
  assert.equal(mode.current.value, 'pro')
  mode.set('expert')
  assert.equal(mode.current.value, 'pro')
  mode.set('novice')
  assert.equal(mode.current.value, 'novice', 'the switch still applies for this session')
})

test('signed-out visitors are not persisted', () => {
  const storage = memoryStorage()
  const mode = createUiMode(storage)
  mode.setAccount('')
  mode.set('novice')
  assert.equal(mode.current.value, 'novice')
  mode.setAccount('7:1')
  assert.equal(mode.current.value, 'pro')
})
