import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, h } from 'vue'
import { createServer } from 'vite'

const input = () => ({ prompt: '生成数字人口播视频', images: [{ assetId: 11 }], videos: [], audios: [],
  model: 'SEEDANCE_2_5', ratio: '9:16', durationSeconds: 10, resolution: '720p' })
const response = data => ({ ok: true, status: 200, json: async () => ({ code: 200, data }) })
const approved = () => ({ quote: { available: true, quoteId: 'server-test-quote', credits: 17 }, requestKey: `video-${crypto.randomUUID()}`,
  signature: JSON.stringify({ prompt: input().prompt, referenceImageAssetIds: [11], referenceVideoAssetIds: [], referenceAudioAssetIds: [],
    model: input().model, ratio: input().ratio, durationSeconds: input().durationSeconds, resolution: input().resolution,
    sourceModule: 'DIGITAL_HUMAN' }) })

test('digital human task lifecycle follows the real video API', async t => {
  const vite = await createServer({ configFile: false, cacheDir: 'node_modules/.vite-dh-tests',
    server: { middlewareMode: true, hmr: false }, appType: 'custom', optimizeDeps: { noDiscovery: true } })
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
        const pending = studio.start(input(), approved())
        assert.equal(studio.previewStarted.value, true)
        assert.equal(studio.isGenerating.value, true)
        await studio.start(input(), approved())
        await new Promise(resolve => setImmediate(resolve))
        assert.equal(posts.length, 1)
        assert.deepEqual(posts[0].referenceImageAssetIds, [11])
        assert.equal(posts[0].sourceModule, 'DIGITAL_HUMAN')
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
    await t.test('completed output keeps actual metadata and shows a simple completion notice without submitting again', async () => {
      let posts = 0
      globalThis.fetch = async () => {
        posts++
        return response({ id: 75, status: 'SUCCEEDED', stage: 'DONE', outputUrl: '/test/finished.mp4',
          actualWidth: 720, actualHeight: 1280, actualDurationMs: 5200,
          qaWarnings: [{ code: 'VIDEO_RESOLUTION_MISMATCH', message: '分辨率与请求不一致' }] })
      }
      const { app, studio } = mount()
      try {
        await studio.start(input(), approved())
        assert.equal(studio.isGenerating.value, false)
        assert.equal(studio.outputUrl.value, '/test/finished.mp4')
        assert.equal(studio.workflow.value.actualWidth, 720)
        assert.equal(studio.workflow.value.actualHeight, 1280)
        assert.equal(studio.workflow.value.actualDurationMs, 5200)
        assert.equal(studio.notice.value, '视频已完成并保存到作品库。')
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
        await studio.start(input(), approved())
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
    await t.test('opening a record shows it in the preview, keeps polling a running one, and reset returns to the studio', async () => {
      const gets = []
      globalThis.fetch = async (url, options) => {
        assert.notEqual(options.method, 'POST')
        gets.push(url)
        return response({ id: 80, status: 'SUCCEEDED', stage: 'DONE', outputUrl: '/test/record.mp4' })
      }
      const { app, studio } = mount()
      try {
        studio.open({ id: 80, status: 'RUNNING', stage: 'POLL', model: 'SEEDANCE_2_0', ratio: '9:16', durationSeconds: 8, resolution: '480p' })
        assert.equal(studio.previewStarted.value, true)
        assert.equal(studio.isGenerating.value, true)
        assert.deepEqual(studio.submittedForm.value, { model: 'SEEDANCE_2_0', ratio: '9:16', durationSeconds: 8, resolution: '480p' })
        const [id, callback] = timers.entries().next().value
        timers.delete(id)
        await callback()
        assert.deepEqual(gets, ['/api/video/workflows/80'])
        assert.equal(studio.outputUrl.value, '/test/record.mp4')
        assert.equal(timers.size, 0)
        studio.reset()
        assert.equal(studio.previewStarted.value, false)
        assert.equal(studio.workflow.value, null)
        assert.equal(studio.submittedForm.value, null)
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
      const approval = approved()
      try {
        await studio.start(input(), approval)
        assert.equal(studio.isGenerating.value, false)
        assert.match(studio.error.value, /网络中断/)
        await studio.start(input(), approval)
        assert.equal(posts[0].requestKey, posts[1].requestKey)
        assert.equal(studio.isGenerating.value, false)
        assert.equal(studio.error.value, '供应商拒绝任务')
        await studio.start(input(), approved())
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
        await studio.start(input(), approved())
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
    await t.test('requests a server quote before generation and submits exactly the approved quote', async () => {
      const calls = []
      globalThis.fetch = async (url, options) => {
        const body = JSON.parse(options.body)
        calls.push({ url, body })
        if (url === '/api/video/quote') return response({ available: true, quoteId: 'verified-server-quote', credits: 29 })
        return response({ id: 79, status: 'SUCCEEDED', chargedCredits: 27 })
      }
      const { app, studio } = mount()
      try {
        await studio.start(input())
        assert.equal(calls.length, 0)
        assert.match(studio.error.value, /先预估/)
        await studio.estimate(input())
        assert.equal(calls.length, 1)
        assert.equal(calls[0].url, '/api/video/quote')
        assert.equal(studio.previewStarted.value, false)
        assert.equal(studio.quoted.value.quote.credits, 29)
        await studio.confirmQuote()
        assert.equal(calls.length, 2)
        assert.equal(calls[1].url, '/api/video/workflows')
        assert.equal(calls[1].body.quoteId, 'verified-server-quote')
        assert.equal(calls[1].body.requestKey, calls[0].body.requestKey)
        assert.equal(studio.workflow.value.chargedCredits, 27)
        assert.equal(studio.quoted.value, null)
      } finally { app.unmount() }
    })
    await t.test('unverified pricing and changed inputs cannot create a video', async () => {
      const calls = []
      globalThis.fetch = async url => {
        calls.push(url)
        return response({ available: false, message: '计价尚未核验' })
      }
      const { app, studio } = mount()
      try {
        await studio.estimate(input())
        await studio.confirmQuote()
        assert.deepEqual(calls, ['/api/video/quote'])
        assert.equal(studio.workflow.value, null)
        await studio.start({ ...input(), resolution: '1080p' }, approved())
        assert.match(studio.error.value, /配置已变化/)
        assert.deepEqual(calls, ['/api/video/quote'])
      } finally { app.unmount() }
    })
    await t.test('quotes and submits every selected video and audio without losing references', async () => {
      const calls = []
      globalThis.fetch = async (url, options) => {
        if (/^\/api\/assets\/\d+$/.test(url)) return response({ id: Number(url.split('/')[3]), status: 'READY' })
        calls.push({ url, body: JSON.parse(options.body) })
        return response(url.endsWith('/quote') ? { available: true, quoteId: 'all-materials', credits: 20 } : { id: 81, status: 'SUCCEEDED' })
      }
      const { app, studio } = mount()
      try {
        await studio.estimate({ ...input(), videos: [{ assetId: 12 }, { assetId: 13 }], audios: [{ assetId: 14 }, { assetId: 15 }] })
        await studio.confirmQuote()
        assert.equal(calls.length, 2)
        for (const { body } of calls) {
          assert.deepEqual(body.referenceVideoAssetIds, [12, 13])
          assert.deepEqual(body.referenceAudioAssetIds, [14, 15])
        }
        assert.equal(calls[0].body.requestKey, calls[1].body.requestKey)
      } finally { app.unmount() }
    })
  } finally {
    globalThis.fetch = originalFetch
    if (originalWindow === undefined) delete globalThis.window
    else globalThis.window = originalWindow
    await vite.close()
  }
})
