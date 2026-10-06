import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, h } from 'vue'
import { createServer } from 'vite'

const input = () => ({ prompt: '生成数字人口播视频', images: [{ assetId: 11 }], video: null,
  model: 'SEEDANCE_2_5', ratio: '9:16', durationSeconds: 10, resolution: '720p' })
const response = data => ({ ok: true, status: 200, json: async () => ({ code: 200, data }) })

test('digital human task lifecycle follows the real video API', async t => {
  const vite = await createServer({ configFile: false, cacheDir: 'node_modules/.vite-dh-tests',
    server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  const originalWindow = globalThis.window
  const timers = new Map()
  let timerId = 0
  globalThis.window = { setTimeout: callback => { timers.set(++timerId, callback); return timerId }, clearTimeout: id => timers.delete(id) }
  const renderer = createRenderer({
    createElement: tag => ({ tag, children: [] }), createText: text => ({ text }), createComment: text => ({ text }),
    setText: (node, text) => { node.text = text }, setElementText: (node, text) => { node.text = text }, patchProp: () => {},
    insert: (node, parent) => parent.children.push(node), remove: () => {}, parentNode: () => null, nextSibling: () => null,
  })
  const { useDigitalHumanVideo } = await vite.ssrLoadModule('/src/composables/useDigitalHumanVideo.js')
  const mount = () => {
    let studio
    const app = renderer.createApp({ setup() { studio = useDigitalHumanVideo(); return () => h('div', studio.isGenerating.value) } })
    app.mount({ children: [] })
    return { app, studio }
  }
  try {
    await t.test('switches to preview immediately, blocks duplicate submits and displays the completed output', async () => {
      let resolveCreate
      const posts = []
      globalThis.fetch = async (url, options) => {
        if (options.method === 'POST') { posts.push(JSON.parse(options.body)); return new Promise(resolve => { resolveCreate = resolve }) }
        assert.equal(url, '/api/video/workflows/71')
        return response({ id: 71, status: 'SUCCEEDED', stage: 'QA', outputUrl: '/test/finished.mp4' })
      }
      const { app, studio } = mount()
      try {
        const pending = studio.start(input())
        assert.equal(studio.previewStarted.value, true)
        assert.equal(studio.isGenerating.value, true)
        await studio.start(input())
        await new Promise(resolve => setImmediate(resolve))
        assert.equal(posts.length, 1)
        assert.deepEqual(posts[0].referenceImageAssetIds, [11])
        resolveCreate(response({ id: 71, status: 'RUNNING', stage: 'POLL' }))
        await pending
        assert.equal(studio.stageLabel.value, '画面渲染中')
        const [id, callback] = timers.entries().next().value
        timers.delete(id)
        await callback()
        assert.equal(studio.isGenerating.value, false)
        assert.equal(studio.outputUrl.value, '/test/finished.mp4')
        assert.equal(studio.previewStarted.value, true)
        assert.equal(timers.size, 0)
      } finally { app.unmount() }
    })
    await t.test('completed output keeps actual metadata and warns about differences without submitting again', async () => {
      let posts = 0
      globalThis.fetch = async () => {
        posts++
        return response({ id: 75, status: 'SUCCEEDED', stage: 'DONE', outputUrl: '/test/finished.mp4',
          actualWidth: 720, actualHeight: 1280, actualDurationMs: 5200,
          qaWarnings: [{ code: 'VIDEO_RESOLUTION_MISMATCH', message: '分辨率与请求不一致' }] })
      }
      const { app, studio } = mount()
      try {
        await studio.start(input())
        assert.equal(studio.isGenerating.value, false)
        assert.equal(studio.outputUrl.value, '/test/finished.mp4')
        assert.equal(studio.workflow.value.actualWidth, 720)
        assert.equal(studio.workflow.value.actualHeight, 1280)
        assert.equal(studio.workflow.value.actualDurationMs, 5200)
        assert.match(studio.notice.value, /成片参数需核对/)
        assert.match(studio.notice.value, /重新生成可能再次计费/)
        assert.equal(posts, 1)
        assert.equal(timers.size, 0)
      } finally { app.unmount() }
    })
    await t.test('CANCELLED is terminal, displays the cancellation reason and stops polling', async () => {
      let gets = 0
      globalThis.fetch = async (url, options) => {
        if (options.method === 'POST') return response({ id: 76, status: 'GENERATING', stage: 'POLL' })
        assert.equal(url, '/api/video/workflows/76')
        gets++
        return response({ id: 76, status: 'CANCELLED', stage: 'CANCELLED', error: '已取消本次视频生成' })
      }
      const { app, studio } = mount()
      try {
        await studio.start(input())
        assert.equal(studio.isGenerating.value, true)
        const [id, callback] = timers.entries().next().value
        timers.delete(id)
        await callback()
        assert.equal(studio.workflow.value.status, 'CANCELLED')
        assert.equal(studio.isGenerating.value, false)
        assert.equal(studio.error.value, '已取消本次视频生成')
        assert.equal(studio.outputUrl.value, '')
        assert.equal(gets, 1)
        assert.equal(timers.size, 0)
      } finally { app.unmount() }
    })
    await t.test('an uncertain submission reuses its key while a known failed task starts a fresh generation', async () => {
      const posts = []
      globalThis.fetch = async (url, options) => {
        posts.push(JSON.parse(options.body))
        if (posts.length === 1) throw new Error('网络中断')
        return response({ id: 72, status: 'FAILED', error: '供应商拒绝任务' })
      }
      const { app, studio } = mount()
      try {
        await studio.start(input())
        assert.equal(studio.isGenerating.value, false)
        assert.match(studio.error.value, /网络中断/)
        await studio.start(input())
        assert.equal(posts[0].requestKey, posts[1].requestKey)
        assert.equal(studio.isGenerating.value, false)
        assert.equal(studio.error.value, '供应商拒绝任务')
        await studio.start(input())
        assert.notEqual(posts[1].requestKey, posts[2].requestKey)
      } finally { app.unmount() }
    })
    await t.test('poll errors keep the task active, and leaving the view stops polling', async () => {
      globalThis.fetch = async (url, options) => {
        if (options.method === 'POST') return response({ id: 73, status: 'RUNNING', stage: 'IMPORT' })
        throw new Error('连接暂时中断')
      }
      const { app, studio } = mount()
      try {
        await studio.start(input())
        assert.equal(studio.stageLabel.value, '正在保存成片')
        const [id, callback] = timers.entries().next().value
        timers.delete(id)
        await callback()
        assert.equal(studio.isGenerating.value, true)
        assert.match(studio.notice.value, /连接暂时中断/)
        assert.equal(timers.size, 1)
      } finally { app.unmount() }
      assert.equal(timers.size, 0)
    })
  } finally {
    globalThis.fetch = originalFetch
    if (originalWindow === undefined) delete globalThis.window
    else globalThis.window = originalWindow
    await vite.close()
  }
})
