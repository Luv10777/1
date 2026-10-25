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

test('temporary previews retry a lost visibility receipt and settle once without changing history timings', async () => {
  const vite = await createServer({ server: { middlewareMode: true }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  let auth, app, studio
  let previewRequests = 0
  let versions = [{ id: 42, workflow: 'POSTER', status: 'GENERATING', items: [{
    id: 12, generation: 0, url: 'https://source.example/temporary.png',
    providerImageUrl: 'https://source.example/temporary.png', persisted: false,
  }] }]
  try {
    ;({ auth } = await vite.ssrLoadModule('/src/stores/auth.js'))
    auth.state.user = { id: 1, tenantId: 7 }; auth.state.token = { accessToken: 'test-only' }
    const { useImageCreation } = await vite.ssrLoadModule('/src/composables/useImageCreation.js')
    globalThis.fetch = async (url, options) => {
      let data = { items: [], totalPages: 0 }
      if (String(url).endsWith('/capabilities')) data = { configured: true, qualities: ['2K'], qualityRatios: { '2K': ['3:4'] } }
      if (String(url).endsWith('/thread')) data = structuredClone(versions)
      if (String(url).endsWith('/preview-loaded')) {
        previewRequests++
        if (previewRequests === 1) throw new TypeError('test transient receipt failure')
        assert.equal(options.method, 'POST')
        assert.deepEqual(JSON.parse(options.body), { generation: 0 })
        data = true
      }
      return { ok: true, json: async () => ({ code: 200, data }) }
    }
    const renderer = createRenderer({
      createElement: tag => ({ tag, children: [] }), createText: text => ({ text }), createComment: text => ({ text }),
      setText: (node, text) => { node.text = text }, setElementText: (node, text) => { node.text = text },
      patchProp: () => {}, insert: (node, parent) => { parent.children.push(node) },
      remove: () => {}, parentNode: () => null, nextSibling: () => null,
    })
    app = renderer.createApp({ setup() { studio = useImageCreation('POSTER'); return () => h('div') } })
    app.provide(routerKey, { replace: async () => {} })
    app.provide(routeLocationKey, reactive({ query: {} }))
    app.mount({ children: [] })
    await studio.load(42)
    let item = studio.current.items[0]
    assert.equal(item.url, 'https://source.example/temporary.png')
    assert.equal(studio.canUseAsset(item), false)
    await studio.reportImageVisible(item)
    assert.equal(previewRequests, 1)
    await studio.load(42)
    await new Promise(resolve => setTimeout(resolve, 0))
    assert.equal(previewRequests, 2, 'polling must retry the receipt without another image load')
    item = studio.current.items[0]
    await studio.reportImageVisible(item)
    assert.equal(previewRequests, 2)
    studio.imageLoadFailed(item)
    assert.equal(item.url, null)
    await studio.load(42)
    assert.equal(studio.current.items[0].url, null, 'polling must not repeatedly load a failed temporary URL')
    versions[0].status = 'SUCCEEDED'
    versions[0].items[0] = { ...versions[0].items[0], url: 'https://storage.example/durable.png', providerImageUrl: null, persisted: true }
    await studio.load(42)
    item = studio.current.items[0]
    assert.equal(item.url, 'https://storage.example/durable.png')
    assert.equal(studio.canUseAsset(item), true)
    await studio.reportImageVisible(item)
    assert.equal(previewRequests, 2, 'switching to the durable URL must not restart the first-preview timer')
    versions = [{ id: 43, workflow: 'POSTER', status: 'SUCCEEDED', items: [{ id: 13, generation: 0, url: 'https://storage.example/old.png', persisted: true }] }]
    await studio.load(43)
    await studio.reportImageVisible(studio.current.items[0])
    assert.equal(previewRequests, 2, 'opening completed history must not invent a new generation duration')
  } finally {
    app?.unmount()
    if (auth) { auth.state.user = null; auth.state.token = null }
    globalThis.fetch = originalFetch
    await vite.close()
  }
})
