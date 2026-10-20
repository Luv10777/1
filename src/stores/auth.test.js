import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'

test('avatars persist through session changes and renew expired links without duplicate or stale account updates', async () => {
  const vite = await createServer({ configFile: false, cacheDir: 'node_modules/.vite-account-avatar-tests',
    server: { middlewareMode: true, hmr: false }, appType: 'custom', optimizeDeps: { noDiscovery: true } })
  const originalFetch = globalThis.fetch, originalStorage = globalThis.localStorage, originalNow = Date.now
  const values = new Map()
  globalThis.localStorage = { getItem: key => values.get(key) || null,
    setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
  let now = 1_000_000
  Date.now = () => now
  const pending = []
  const ok = data => ({ ok: true, status: 200, json: async () => ({ code: 200, data }) })
  globalThis.fetch = (url, options) => new Promise(resolve => pending.push({ url, options, resolve }))
  try {
    const { auth } = await vite.ssrLoadModule('/src/stores/auth.js')
    const user = { userId: 1, tenantId: 7, phone: '13800000001', name: '本人', role: 'OWNER', avatarUrl: 'old.png' }
    const session = user => ({ accessToken: 'test-only', refreshToken: 'refresh-test', expiresIn: 3600, user })
    auth.replaceSession(session(user))
    assert.equal(auth.user.avatarUrl, 'old.png')
    const renew = auth.refreshAvatar
    const first = renew(), second = renew()
    assert.equal(pending.length, 1)
    assert.equal(pending[0].url, '/api/auth/me')
    pending[0].resolve(ok({ ...user, avatarUrl: 'renewed.png' }))
    await Promise.all([first, second])
    assert.equal(auth.user.avatarUrl, 'renewed.png')
    assert.equal(JSON.parse(values.get('wuyao-ai-auth')).avatarUrl, 'renewed.png')
    auth.rename('改过名字')
    assert.equal(auth.user.avatarUrl, 'renewed.png')
    await auth.refreshAvatar()
    assert.equal(pending.length, 1, 'a failed image cannot repeatedly refresh within 30 seconds')
    now += 31_000
    const stale = auth.refreshAvatar()
    auth.replaceSession(session({ ...user, userId: 2, avatarUrl: 'other-account.png' }))
    pending[1].resolve(ok({ ...user, avatarUrl: 'late-old-account.png' }))
    await stale
    assert.equal(auth.user.avatarUrl, 'other-account.png')
    auth.updateAvatar('late-upload.png', 1)
    assert.equal(auth.user.avatarUrl, 'other-account.png')
    const restored = auth.restore()
    pending[2].resolve(ok({ ...user, userId: 2, avatarUrl: 'restored.png' }))
    assert.equal(await restored, true)
    assert.equal(auth.user.avatarUrl, 'restored.png')
    auth.replaceSession(session({ ...user, userId: 2, avatarUrl: 'password-session.png' }))
    assert.equal(auth.user.avatarUrl, 'password-session.png')
  } finally {
    globalThis.fetch = originalFetch
    Date.now = originalNow
    if (originalStorage === undefined) delete globalThis.localStorage
    else globalThis.localStorage = originalStorage
    await vite.close()
  }
})
