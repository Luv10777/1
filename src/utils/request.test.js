import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'

test('HTTP error envelopes preserve errors and a 401 still refreshes and replays', async t => {
  const vite = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  const originalStorage = globalThis.localStorage
  const storage = new Map()
  globalThis.localStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
    removeItem: key => storage.delete(key),
  }
  try {
    const { request } = await vite.ssrLoadModule('/src/utils/request.js')
    const response = (status, body) => ({ ok: status >= 200 && status < 300, status, statusText: 'error', json: async () => body })
    await t.test('refreshes HTTP 401 and retries with the new access token', async () => {
      storage.set('wuyao-ai-token', JSON.stringify({ accessToken: 'old', refreshToken: 'refresh' }))
      const calls = []
      globalThis.fetch = async (url, options) => {
        calls.push([url, options])
        if (String(url).endsWith('/api/auth/refresh')) return response(200, { code: 200, data: { accessToken: 'new', refreshToken: 'rotated', expiresIn: 3600 } })
        return options.headers.Authorization === 'Bearer new'
          ? response(200, { code: 200, data: { name: 'user' } })
          : response(401, { code: 1401, message: 'expired' })
      }
      assert.deepEqual(await request('/api/auth/me'), { name: 'user' })
      assert.equal(calls.length, 3)
      assert.equal(calls[2][1].headers.Authorization, 'Bearer new')
    })
    await t.test('shows structured 400/404/429/502 errors and does not refresh', async () => {
      for (const [status, code] of [[400, 1400], [404, 3001], [429, 2001], [502, 4002]]) {
        let calls = 0
        globalThis.fetch = async () => { calls++; return response(status, { code, message: 'business error' }) }
        await assert.rejects(request('/api/test'), e => e.code === code && e.status === status && e.message === 'business error')
        assert.equal(calls, 1)
      }
    })
    await t.test('failed refresh clears login without recursively refreshing', async () => {
      storage.set('wuyao-ai-token', JSON.stringify({ accessToken: 'old', refreshToken: 'refresh' }))
      let calls = 0
      globalThis.fetch = async url => { calls++; return response(401, { code: String(url).endsWith('/refresh') ? 2005 : 1401, message: 'expired' }) }
      await assert.rejects(request('/api/auth/me'), e => e.code === 1401)
      assert.equal(calls, 2)
      assert.equal(storage.has('wuyao-ai-token'), false)
    })
    await t.test('unstructured gateway errors remain HTTP errors', async () => {
      globalThis.fetch = async () => response(502, null)
      await assert.rejects(request('/api/test'), e => e.code === 'HTTP_ERROR' && e.status === 502)
    })
  } finally {
    globalThis.fetch = originalFetch
    if (originalStorage === undefined) delete globalThis.localStorage
    else globalThis.localStorage = originalStorage
    await vite.close()
  }
})
