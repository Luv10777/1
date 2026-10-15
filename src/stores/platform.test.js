import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'

const memoryRouter = { name: 'platform-permission-router', enforce: 'pre', transform(code, id) {
  if (/\/(?:src|admin)\/router\.js$/.test(id.replaceAll('\\', '/'))) return code
    .replace('createRouter, createWebHistory', 'createRouter, createMemoryHistory').replace('createWebHistory()', 'createMemoryHistory()')
} }

test('standalone admin routes require explicit operator access and do not exist in the merchant app', async () => {
  const vite = await createServer({ server: { middlewareMode: true, hmr: false }, optimizeDeps: { noDiscovery: true, include: [] }, appType: 'custom', plugins: [memoryRouter] })
  const originalFetch = globalThis.fetch
  let operator = false
  globalThis.fetch = async () => ({ ok: true, status: 200, json: async () => ({ code: 200, data: { operator } }) })
  const { auth } = await vite.ssrLoadModule('/src/stores/auth.js')
  try {
    const { default: publicRouter } = await vite.ssrLoadModule('/src/router.js')
    for (const path of ['/admin/information', '/admin/identities']) {
      await publicRouter.push(path)
      assert.equal(publicRouter.currentRoute.value.name, 'not-found', 'the merchant app must not contain admin pages')
    }
    await publicRouter.push('/identity')
    assert.equal(publicRouter.currentRoute.value.name, 'login', 'personal identity submission remains in the merchant app')
    const { default: router } = await vite.ssrLoadModule('/admin/router.js')
    for (const path of ['/information', '/merchants', '/identities']) {
      await router.push(path)
      assert.equal(router.currentRoute.value.name, 'admin-login')
      assert.equal(router.currentRoute.value.query.redirect, path)
    }
    auth.state.token = { accessToken: 'permission-test-only' }
    auth.state.user = { id: 7, tenantId: 2, roles: ['owner'] }
    for (const path of ['/information', '/merchants', '/identities', '/admin/real-name']) {
      await router.push(path)
      assert.equal(router.currentRoute.value.name, 'admin-forbidden', 'merchant ownership grants no platform rights')
    }
    await publicRouter.push('/identity')
    assert.equal(publicRouter.currentRoute.value.name, 'identity', 'own identity submission remains available')
    operator = true
    auth.state.user = { id: 1, tenantId: 1, roles: ['owner'] }
    await router.push('/admin/information')
    assert.equal(router.currentRoute.value.name, 'admin-information')
    await router.push('/admin/identities')
    assert.equal(router.currentRoute.value.name, 'admin-identities')
    await router.push('/merchants')
    assert.equal(router.currentRoute.value.name, 'admin-merchants')
    operator = false
    auth.state.user = { id: 8, tenantId: 2, roles: ['staff'] }
    await router.push('/admin/information')
    assert.equal(router.currentRoute.value.name, 'admin-forbidden', 'changing account clears operator access')
  } finally {
    auth.state.user = null; auth.state.token = null
    globalThis.fetch = originalFetch
    await vite.close()
  }
})

test('admin permission outages block protected pages and return locations stay inside the admin app', async () => {
  const vite = await createServer({ server: { middlewareMode: true, hmr: false }, optimizeDeps: { noDiscovery: true, include: [] }, appType: 'custom', plugins: [memoryRouter] })
  const originalFetch = globalThis.fetch
  const originalStorage = globalThis.localStorage
  const storage = new Map()
  globalThis.localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) }
  globalThis.fetch = async () => { throw new Error('test service unavailable') }
  const { auth } = await vite.ssrLoadModule('/src/stores/auth.js')
  try {
    const { default: router, adminDestination, handleAdminAuthExpired } = await vite.ssrLoadModule('/admin/router.js')
    auth.state.token = { accessToken: 'permission-test-only' }
    auth.state.user = { id: 1, tenantId: 1 }
    await router.push('/identities?status=pending')
    assert.equal(router.currentRoute.value.name, 'admin-unavailable')
    assert.equal(router.currentRoute.value.query.redirect, '/identities?status=pending')
    for (const value of ['//evil.test', 'https://evil.test', '/image/create', '/login', '/api/auth/wechat/start', null]) {
      assert.equal(adminDestination(value), '/information')
    }
    assert.equal(adminDestination('/merchants?status=PENDING_REVIEW'), '/merchants?status=PENDING_REVIEW')
    await handleAdminAuthExpired()
    assert.equal(auth.isAuthenticated, false)
    assert.equal(router.currentRoute.value.name, 'admin-login')
  } finally {
    auth.state.user = null; auth.state.token = null
    globalThis.fetch = originalFetch
    if (originalStorage === undefined) delete globalThis.localStorage
    else globalThis.localStorage = originalStorage
    await vite.close()
  }
})

test('admin sessions use separate storage from the merchant app', async () => {
  const originalStorage = globalThis.localStorage
  const storage = new Map([['wuyao-ai-token', JSON.stringify({ accessToken: 'merchant-session' })], ['wuyao-ai-auth', JSON.stringify({ id: 7 })]])
  globalThis.localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) }
  const vite = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom', define: { 'import.meta.env.VITE_APP_CONTEXT': JSON.stringify('admin') } })
  try {
    const tokens = await vite.ssrLoadModule('/src/utils/tokenStore.js')
    assert.equal(tokens.SESSION_NAMESPACE, 'wuyao-admin')
    assert.equal(tokens.readTokens(), null, 'merchant credentials must not sign into admin')
    tokens.writeTokens({ accessToken: 'admin-session' })
    tokens.writeUser({ id: 1 })
    assert.equal(JSON.parse(storage.get('wuyao-admin-token')).accessToken, 'admin-session')
    assert.equal(JSON.parse(storage.get('wuyao-ai-token')).accessToken, 'merchant-session')
    tokens.clearAll()
    assert.equal(storage.has('wuyao-admin-token'), false)
    assert.equal(storage.has('wuyao-admin-auth'), false)
    assert.equal(storage.has('wuyao-ai-token'), true, 'admin sign-out must preserve merchant sessions')
  } finally {
    if (originalStorage === undefined) delete globalThis.localStorage
    else globalThis.localStorage = originalStorage
    await vite.close()
  }
})

test('a stale permission response cannot grant the next account platform access', async () => {
  const vite = await createServer({ server: { middlewareMode: true, hmr: false }, optimizeDeps: { noDiscovery: true, include: [] }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  let resolveOld
  let requests = 0
  globalThis.fetch = () => {
    requests++
    if (requests === 1) return new Promise(resolve => { resolveOld = resolve })
    return Promise.resolve({ ok: true, status: 200, json: async () => ({ code: 200, data: { operator: false } }) })
  }
  const { auth } = await vite.ssrLoadModule('/src/stores/auth.js')
  const { platform, loadPlatformAccess } = await vite.ssrLoadModule('/src/stores/platform.js')
  try {
    auth.state.token = { accessToken: 'permission-test-only' }
    auth.state.user = { id: 1, tenantId: 1 }
    const previous = loadPlatformAccess()
    auth.state.user = { id: 2, tenantId: 2 }
    assert.equal(await loadPlatformAccess(), false)
    resolveOld({ ok: true, status: 200, json: async () => ({ code: 200, data: { operator: true } }) })
    assert.equal(await previous, false)
    assert.equal(platform.operator, false)
    assert.equal(platform.loaded, true)
  } finally {
    auth.state.user = null; auth.state.token = null
    globalThis.fetch = originalFetch
    await vite.close()
  }
})
