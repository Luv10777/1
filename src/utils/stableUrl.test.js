import test from 'node:test'
import assert from 'node:assert/strict'
import { createStableUrls, signedUrlExpiry } from './stableUrl.js'

const memoryStorage = () => { const values = new Map(); return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) } }
const cos = (start, end, sig) => `https://bucket.cos.ap-shanghai.myqcloud.com/t1/a.png?q-sign-algorithm=sha1&q-sign-time=${start};${end}&q-signature=${sig}`

test('reads expiry from COS and S3 style signatures', () => {
  assert.equal(signedUrlExpiry(cos(100, 1900, 'x')), 1900 * 1000)
  assert.equal(signedUrlExpiry('http://minio:9000/b/k.png?X-Amz-Date=20261117T100000Z&X-Amz-Expires=1800&X-Amz-Signature=y'), Date.UTC(2026, 10, 17, 10, 30, 0))
  assert.equal(signedUrlExpiry('/images/brand/mark.png'), null)
  assert.equal(signedUrlExpiry('https://provider.example/preview.png'), null)
})

test('keeps returning the first link for a file until it nearly expires', () => {
  let clock = 1000 * 1000
  const stable = createStableUrls(memoryStorage(), () => clock)
  const first = cos(1000, 2800, 'first')
  assert.equal(stable(first), first)
  assert.equal(stable(cos(1001, 2801, 'second')), first, 'a fresh signature for the same file reuses the cached link')
  clock = (2800 - 60) * 1000
  const third = cos(2740, 4540, 'third')
  assert.equal(stable(third), third, 'within two minutes of expiry the new link takes over')
})

test('survives a reload through storage and ignores unsigned or local links', () => {
  const storage = memoryStorage()
  const clock = () => 1000 * 1000
  const first = cos(1000, 2800, 'first')
  createStableUrls(storage, clock)(first)
  assert.equal(createStableUrls(storage, clock)(cos(1005, 2805, 'later')), first)
  const stable = createStableUrls(null, clock)
  assert.equal(stable('/images/drawing-styles/ink.webp'), '/images/drawing-styles/ink.webp')
  assert.equal(stable(undefined), undefined)
})
