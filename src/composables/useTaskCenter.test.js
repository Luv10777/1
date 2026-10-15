import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, h } from 'vue'
import { createServer } from 'vite'

const response = data => ({ ok: true, status: 200, json: async () => ({ code: 200, data }) })
const report = (id, page = 0, totalPages = 1) => ({
  records: { items: [{ id, status: 'SUCCEEDED', chargedCredits: 3 }], page, size: 20, total: totalPages * 20, totalPages },
  summary: { tasks: 1, chargedCredits: 3, heldCredits: 0, releasedCredits: 0, succeeded: 1, failed: 0 },
})

test('task center keeps filters, pagination and details consistent during overlapping requests', async t => {
  const vite = await createServer({ configFile: false, cacheDir: 'node_modules/.vite-task-center-tests',
    server: { middlewareMode: true, hmr: false }, appType: 'custom', optimizeDeps: { noDiscovery: true } })
  const originalFetch = globalThis.fetch
  const renderer = createRenderer({
    createElement: tag => ({ tag, children: [] }), createText: text => ({ text }), createComment: text => ({ text }),
    setText: () => {}, setElementText: () => {}, patchProp: () => {}, insert: (node, parent) => parent.children.push(node),
    remove: () => {}, parentNode: () => null, nextSibling: () => null,
  })
  const { useTaskCenter } = await vite.ssrLoadModule('/src/composables/useTaskCenter.js')
  const mount = () => {
    let center
    const app = renderer.createApp({ setup() { center = useTaskCenter(); return () => h('div') } })
    app.mount({ children: [] })
    return { center, app }
  }
  const tick = () => new Promise(resolve => setImmediate(resolve))
  try {
    await t.test('a stale response cannot overwrite a new filter or its summary', async () => {
      let resolveOld
      const requests = []
      globalThis.fetch = async url => {
        requests.push(url)
        if (requests.length === 1) return new Promise(resolve => { resolveOld = resolve })
        return response(report('video-2'))
      }
      const { center, app } = mount()
      try {
        center.filters.module = 'VIDEO_GENERATION'
        await center.search()
        assert.match(requests[1], /module=VIDEO_GENERATION/)
        resolveOld(response(report('image-1')))
        await tick()
        assert.equal(center.result.value.records.items[0].id, 'video-2')
        assert.equal(center.loading.value, false)
      } finally { app.unmount() }
    })
    await t.test('paging uses applied filters until search and shrinking history returns to a valid page', async () => {
      const requests = []
      globalThis.fetch = async url => {
        requests.push(url)
        const page = Number(new URL(url, 'https://test.invalid').searchParams.get('page'))
        return response(report('image-1', page, requests.length === 1 ? 3 : 1))
      }
      const { center, app } = mount()
      try {
        await tick()
        center.filters.model = 'Unsubmitted filter'
        await center.load(2)
        assert.equal(requests.length, 3)
        assert.ok(requests.every(url => !url.includes('Unsubmitted')))
        assert.equal(center.result.value.records.page, 0)
        await center.search()
        assert.match(requests.at(-1), /model=Unsubmitted\+filter/)
      } finally { app.unmount() }
    })
    await t.test('opening another task discards an older detail and network failures never become zero consumption', async () => {
      let resolveOld
      globalThis.fetch = async url => {
        if (url.endsWith('/image-1')) return new Promise(resolve => { resolveOld = resolve })
        if (url.endsWith('/video-2')) return response({ task: { id: 'video-2' }, items: [], events: [] })
        return response(report('image-1'))
      }
      const { center, app } = mount()
      try {
        await tick()
        const old = center.toggleDetail('image-1')
        await center.toggleDetail('video-2')
        resolveOld(response({ task: { id: 'image-1' } }))
        await old
        assert.equal(center.detail.value.task.id, 'video-2')
        globalThis.fetch = async () => { throw new Error('network unavailable') }
        await center.load()
        assert.equal(center.result.value, null)
        assert.match(center.error.value, /network unavailable/)
      } finally { app.unmount() }
    })
    await t.test('unmount discards an in-flight response', async () => {
      let resolve
      globalThis.fetch = async () => new Promise(done => { resolve = done })
      const { center, app } = mount()
      app.unmount()
      resolve(response(report('image-1')))
      await tick()
      assert.equal(center.result.value, null)
    })
  } finally { globalThis.fetch = originalFetch; await vite.close() }
})
