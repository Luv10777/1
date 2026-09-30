import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, h, reactive } from 'vue'
import { routerKey, routeLocationKey } from 'vue-router'
import { createServer } from 'vite'

test('mounting image creation checks service capabilities', async () => {
  const vite = await createServer({ server: { middlewareMode: true }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  let capabilityRequests = 0
  try {
    const { useImageCreation } = await vite.ssrLoadModule('/src/composables/useImageCreation.js')
    globalThis.fetch = async url => {
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
    const app = renderer.createApp({
      setup() {
        studio = useImageCreation('POSTER')
        return () => h('div', studio.capabilityState)
      },
    })
    app.provide(routerKey, { replace: async () => {} })
    app.provide(routeLocationKey, reactive({ query: {} }))
    app.mount({ children: [] })
    await new Promise(resolve => setTimeout(resolve, 20))
    assert.equal(capabilityRequests, 1)
    assert.equal(studio.capabilityState, 'ready')
    assert.equal(studio.configured, true)
    app.unmount()
  } finally {
    globalThis.fetch = originalFetch
    await vite.close()
  }
})
