import { test } from 'node:test'
import assert from 'node:assert/strict'
import { webcrypto } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'

const source = readFileSync(new URL('./crypto-polyfill.js', import.meta.url), 'utf8')

test('HTTP-like crypto without randomUUID can generate UUID v4 IDs', () => {
  const crypto = { getRandomValues: bytes => webcrypto.getRandomValues(bytes) }
  runInNewContext(source, { crypto })

  const ids = Array.from({ length: 100 }, () => crypto.randomUUID())
  for (const id of ids) {
    assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  }
  assert.equal(new Set(ids).size, ids.length)
})

test('preserves native randomUUID when it is available', () => {
  const crypto = { randomUUID: () => 'native-uuid' }
  const nativeRandomUUID = crypto.randomUUID
  runInNewContext(source, { crypto })
  assert.equal(crypto.randomUUID, nativeRandomUUID)
})
