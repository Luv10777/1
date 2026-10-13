import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'

test('all business pages require a verified membership and the subscription page remains reachable', async () => {
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
    for (const route of router.getRoutes().filter(route => route.meta.requiresMembership && !route.meta.ownerOnly)) {
      await router.push(route.path)
      assert.equal(router.currentRoute.value.name, 'billing', route.path)
      assert.equal(router.currentRoute.value.query.membership, 'required')
      assert.equal(router.currentRoute.value.query.redirect, route.path)
    }
    account = { memberActive: true }
    for (const path of ['/image/create/product-set', '/video/analyze', '/assets/products', '/digital-human']) {
      await router.push(path)
      assert.equal(router.currentRoute.value.path, path)
    }
    failed = true
    await router.push('/video/analyze')
    assert.equal(router.currentRoute.value.name, 'billing')
    assert.equal(router.currentRoute.value.query.membership, 'unavailable', 'stale active membership must not permit entry')
    await router.push('/billing')
    assert.equal(router.currentRoute.value.name, 'billing')
    auth.state.token = null
    await router.push('/video/analyze')
    assert.equal(router.currentRoute.value.name, 'login')
  } finally {
    auth.state.user = null; auth.state.token = null
    globalThis.fetch = originalFetch; await vite.close()
  }
})
