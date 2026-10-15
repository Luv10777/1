import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'

test('business pages remain visible without membership while login and owner permissions are enforced', async () => {
  const vite = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom',
    plugins: [{ name: 'memory-router-test', enforce: 'pre', transform(code, id) {
      if (id.replaceAll('\\', '/').endsWith('/src/router.js')) return code
        .replace('createRouter, createWebHistory', 'createRouter, createMemoryHistory').replace('createWebHistory()', 'createMemoryHistory()')
    } }],
  })
  const originalFetch = globalThis.fetch
  let account = { memberActive: false }
  let failed = false
  globalThis.fetch = async () => {
    if (failed) throw new Error('test network interruption')
    return { ok: true, status: 200, json: async () => ({ code: 200, data: account }) }
  }
  const { auth } = await vite.ssrLoadModule('/src/stores/auth.js')
  try {
    const { default: router } = await vite.ssrLoadModule('/src/router.js')
    auth.state.user = { id: 1, tenantId: 7 }
    auth.state.token = { accessToken: 'test-only' }
    for (const route of router.getRoutes().filter(route => route.name && !route.redirect && !route.meta.public && !route.meta.ownerOnly)) {
      await router.push(route.path)
      assert.equal(router.currentRoute.value.name, route.name, route.path)
    }
    for (const path of ['/copy/extract', '/copy/rewrite', '/notifications', '/help']) {
      await router.push(path)
      assert.equal(router.currentRoute.value.path, '/image/create', `${path} should leave the hidden module`)
    }
    account = { memberActive: true }
    for (const path of ['/image/create/product-set', '/video/analyze', '/assets/products', '/digital-human']) {
      await router.push(path)
      assert.equal(router.currentRoute.value.path, path)
    }
    failed = true
    await router.push('/video/analyze')
    assert.equal(router.currentRoute.value.name, 'video-analyze', 'an unavailable billing service must not hide the page')
    await router.push('/billing')
    assert.equal(router.currentRoute.value.name, 'billing')
    auth.state.user.roles = ['staff']
    await router.push('/team')
    assert.equal(router.currentRoute.value.name, 'forbidden')
    auth.state.token = null
    for (const path of ['/', '/image/create', '/image/create/poster', '/image/create/product-set', '/video/create', '/video/workbench', '/video/analyze', '/digital-human', '/digital-human/studio', '/merchant-alliance', '/information', '/information/7?category=supply']) {
      await router.push(path)
      assert.equal(router.currentRoute.value.path, path === '/' ? '/image/create' : path.split('?')[0], 'guests can browse ' + path)
    }
    for (const path of ['/information-publishing', '/works', '/assets/products', '/tasks', '/team', '/billing', '/settings']) {
      await router.push(path)
      assert.equal(router.currentRoute.value.name, 'login', path + ' requires login')
      assert.equal(router.currentRoute.value.query.redirect, path, 'login returns to the intended page')
    }
  } finally {
    auth.state.user = null; auth.state.token = null
    globalThis.fetch = originalFetch; await vite.close()
  }
})

test('expired sessions preserve guest browsing and require login only for private pages', async () => {
  const originalFetch = globalThis.fetch
  const originalStorage = globalThis.localStorage
  const storage = new Map()
  globalThis.localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) }
  globalThis.fetch = async () => ({ ok: true, status: 200, json: async () => ({ code: 200, data: [] }) })
  const vite = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom',
    plugins: [{ name: 'expired-session-router-test', enforce: 'pre', transform(code, id) {
      if (id.replaceAll('\\', '/').endsWith('/src/router.js')) return code
        .replace('createRouter, createWebHistory', 'createRouter, createMemoryHistory').replace('createWebHistory()', 'createMemoryHistory()')
    } }],
  })
  const { auth } = await vite.ssrLoadModule('/src/stores/auth.js')
  const signedIn = () => {
    auth.state.user = { id: 1, tenantId: 7, roles: ['owner'] }
    auth.state.token = { accessToken: 'expired-test-session' }
    storage.set('wuyao-ai-token', JSON.stringify(auth.state.token))
    storage.set('wuyao-ai-auth', JSON.stringify(auth.state.user))
  }
  try {
    const { default: router, handleAuthExpired } = await vite.ssrLoadModule('/src/router.js')
    signedIn()
    await handleAuthExpired()
    assert.equal(router.currentRoute.value.matched.length, 0, 'restoring an expired session must leave the initial URL for the router to resolve')
    assert.equal(auth.isAuthenticated, false)
    assert.equal(storage.has('wuyao-ai-token'), false)
    await router.push('/login?redirect=/dashboard')
    assert.equal(router.currentRoute.value.name, 'image-create', 'a saved login link to the former home opens guest browsing')
    await router.push('/login?redirect=/dashboard')
    assert.equal(router.currentRoute.value.name, 'login', 'opening login from within the app preserves login intent')
    for (const path of ['/image/create', '/image/create/poster', '/video/analyze', '/digital-human', '/merchant-alliance', '/information?category=supply', '/information/7', '/store/5', '/order/receipt', '/login']) {
      signedIn()
      await router.push(path)
      const previous = router.currentRoute.value.fullPath
      await handleAuthExpired()
      assert.equal(auth.isAuthenticated, false)
      assert.equal(router.currentRoute.value.fullPath, previous, 'session expiry must preserve guest access to ' + path)
    }
    for (const path of ['/dashboard', '/works', '/information-publishing', '/tasks?status=running']) {
      signedIn()
      await router.push(path)
      await handleAuthExpired()
      assert.equal(auth.isAuthenticated, false)
      assert.equal(router.currentRoute.value.name, 'login')
      assert.equal(router.currentRoute.value.query.redirect, path, 'private pages retain the return location')
    }
  } finally {
    auth.state.user = null; auth.state.token = null
    globalThis.fetch = originalFetch
    if (originalStorage === undefined) delete globalThis.localStorage
    else globalThis.localStorage = originalStorage
    await vite.close()
  }
})
