import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, h, reactive } from 'vue'
import { routerKey, routeLocationKey } from 'vue-router'
import { createServer } from 'vite'

test('image creation reads capabilities for members and lets guests browse without private requests', async () => {
  const vite = await createServer({ server: { middlewareMode: true }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  let capabilityRequests = 0
  let apiRequests = 0
  let auth
  try {
    ;({ auth } = await vite.ssrLoadModule('/src/stores/auth.js'))
    auth.state.user = { id: 1, tenantId: 7 }
    auth.state.token = { accessToken: 'test-only' }
    const { useImageCreation } = await vite.ssrLoadModule('/src/composables/useImageCreation.js')
    globalThis.fetch = async url => {
      apiRequests++
      if (String(url).endsWith('/capabilities')) {
        capabilityRequests++
        return { ok: true, json: async () => ({ code: 200, data: {
          configured: true, qualities: ['2K'], qualityRatios: { '2K': ['3:4'] },
        } }) }
      }
      return { ok: true, json: async () => ({ code: 200, data: { items: [], totalPages: 0 } }) }
    }
    const renderer = createRenderer({
      createElement: tag => ({ tag, children: [] }),
      createText: text => ({ text }),
      createComment: text => ({ text }),
      setText: (node, text) => { node.text = text },
      setElementText: (node, text) => { node.text = text },
      patchProp: () => {},
      insert: (node, parent) => { parent.children.push(node) },
      remove: () => {},
      parentNode: () => null,
      nextSibling: () => null,
    })
    let studio
    const mountStudio = query => {
      const app = renderer.createApp({
        setup() {
          studio = useImageCreation('POSTER')
          return () => h('div', studio.capabilityState)
        },
      })
      app.provide(routerKey, { replace: async () => {} })
      app.provide(routeLocationKey, reactive({ query }))
      app.mount({ children: [] })
      return app
    }
    const app = mountStudio({})
    await new Promise(resolve => setTimeout(resolve, 20))
    assert.equal(capabilityRequests, 1)
    assert.equal(studio.capabilityState, 'ready')
    assert.equal(studio.configured, true)
    app.unmount()
    auth.state.user = null; auth.state.token = null
    const beforeGuest = apiRequests
    const guestApp = mountStudio({ creation: '99' })
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(studio.requiresLogin, true)
    assert.equal(studio.configured, false)
    studio.generate()
    await studio.loadCapabilities()
    assert.equal(apiRequests, beforeGuest, 'browsing and clicking generate as a guest must not call private APIs')
    assert.equal(studio.pending, null)
    guestApp.unmount()
  } finally {
    if (auth) { auth.state.user = null; auth.state.token = null }
    globalThis.fetch = originalFetch
    await vite.close()
  }
})
