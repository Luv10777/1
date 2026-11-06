import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { hidesAiCreation } from './site.js'

test('only browsers on the main domain hide AI creation', () => {
  assert.equal(hidesAiCreation({ hostname: 'yifangzhi.com' }), true)
  assert.equal(hidesAiCreation({ hostname: 'www.yifangzhi.com' }), true)
  assert.equal(hidesAiCreation({ hostname: 'wy.yifangzhi.com' }), false)
  assert.equal(hidesAiCreation({ hostname: 'localhost' }), false)
  assert.equal(hidesAiCreation({ hostname: 'yifangzhi.com', desktop: true }), false, 'old desktop clients still go live here')
  assert.equal(hidesAiCreation({ hostname: 'yifangzhi.com', userAgent: 'Mozilla/5.0 YifangzhiAndroid/0.1.1' }), false, 'old android clients still go live here')
})

test('main domain sends AI creation routes to the mall and keeps the rest', async () => {
  globalThis.location = { hostname: 'yifangzhi.com' }
  const vite = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom',
    plugins: [{ name: 'memory-router-test', enforce: 'pre', transform(code, id) {
      if (id.replaceAll('\\', '/').endsWith('/src/router.js')) return code
        .replace('createRouter, createWebHistory', 'createRouter, createMemoryHistory').replace('createWebHistory()', 'createMemoryHistory()')
    } }],
  })
  try {
    const { default: router } = await vite.ssrLoadModule('/src/router.js')
    for (const path of ['/', '/help', '/image/create', '/image/create/poster', '/image/create/product-set', '/video/create', '/video/workbench', '/video/analyze', '/digital-human', '/digital-human/studio', '/copy', '/copy/create', '/copy/extract', '/assets', '/knowledge', '/referral']) {
      await router.push('/information')
      await router.push(path)
      assert.equal(router.currentRoute.value.path, '/merchant-alliance', path)
    }
    for (const path of ['/merchant-alliance/join', '/information', '/store/5', '/login', '/policies/terms']) {
      await router.push(path)
      assert.equal(router.currentRoute.value.path, path)
    }
  } finally {
    await vite.close()
    delete globalThis.location
  }
})
