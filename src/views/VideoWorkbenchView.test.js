import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRenderer, nextTick } from 'vue'
import { routeLocationKey, routerKey } from 'vue-router'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createServer } from 'vite'

test('workbench uploads, model changes, draft recall and quotes preserve the supported materials', async () => {
  const saved = Object.fromEntries(['window', 'document', 'Document', 'ShadowRoot', 'fetch'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  globalThis.window = { history: { state: {} }, setTimeout, clearTimeout }
  globalThis.document = {}
  globalThis.Document = class {}
  globalThis.ShadowRoot = class {}
  const quotes = [], uploads = [], pendingVideos = []
  let videosReady = false
  const response = data => ({ ok: true, status: 200, json: async () => ({ code: 200, data }) })
  globalThis.fetch = async (url, options = {}) => {
    if (options.method === 'PUT') return { ok: true }
    if (url === '/api/video/capabilities') return response([])
    if (url.startsWith('/api/video/workflows')) return response({ items: [], totalPages: 0 })
    if (url === '/api/billing/summary') return response({ memberActive: true, available: 100, plan: { videoEnabled: true } })
    if (url === '/api/assets/upload-url') {
      const body = JSON.parse(options.body), assetId = uploads.length + 1
      uploads.push({ ...body, assetId })
      return response({ assetId, uploadUrl: `/storage/${assetId}` })
    }
    if (/\/api\/assets\/\d+\/confirm/.test(url)) {
      const id = Number(url.split('/')[3])
      return response({ id, status: uploads.find(entry => entry.assetId === id).type === 'VIDEO' ? 'VERIFYING' : 'READY' })
    }
    if (/^\/api\/assets\/\d+$/.test(url)) {
      const id = Number(url.split('/')[3])
      return videosReady ? response({ id, status: 'READY' }) : new Promise(resolve => pendingVideos.push(() => resolve(response({ id, status: 'READY' }))))
    }
    if (url === '/api/video/quote') {
      quotes.push(JSON.parse(options.body))
      if (quotes.length === 1) return { ok: false, status: 503, json: async () => ({ code: 500, message: '测试费用预估暂时失败' }) }
      return response({ available: false, message: '本地测试报价' })
    }
    throw new Error(`Unexpected request: ${url}`)
  }
  const vite = await createServer({ configFile: false, cacheDir: 'node_modules/.vite-video-materials-tests',
    optimizeDeps: { noDiscovery: true, entries: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom',
    plugins: [{ name: 'video-materials-interaction',
      load(id) {
        if (id.endsWith('.css')) return ''
        if (id.endsWith('/VideoPreviewPanel.vue')) return "import { h } from 'vue'; export default { inheritAttrs: false, setup(_, { slots }) { return () => h('div', slots.default?.()) } }"
        if (id.endsWith('/VideoWorkbenchView.vue')) return compileScript(parse(readFileSync(id, 'utf8'), { filename: id }).descriptor,
          { id: 'video-materials-test', inlineTemplate: true }).content
        if (id.endsWith('.vue')) return 'export default { render() { return null } }'
      },
    }],
  })
  const { auth } = await vite.ssrLoadModule('/src/stores/auth.js')
  auth.state.user = { id: 1, tenantId: 7 }
  auth.state.token = { accessToken: 'test-only' }
  const { default: Workbench } = await vite.ssrLoadModule('/src/views/VideoWorkbenchView.vue')
  const renderer = createRenderer({
    createElement: tag => ({ tag, props: {}, children: [], style: {}, getRootNode: () => ({ activeElement: null }), addEventListener() {}, removeEventListener() {}, showModal() {}, close() {} }),
    createText: text => ({ text, children: [] }), createComment: text => ({ text, children: [] }),
    setText: (node, text) => { node.text = text }, setElementText: (node, text) => { node.text = text; node.children = [] },
    patchProp: (node, key, previous, value) => { node.props[key] = value },
    insert(node, parent, anchor) {
      if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
      const index = anchor ? parent.children.indexOf(anchor) : -1
      parent.children.splice(index < 0 ? parent.children.length : index, 0, node); node.parent = parent
    },
    remove(node) { if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1) },
    parentNode: node => node.parent, nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1],
  })
  const root = { children: [] }, app = renderer.createApp(Workbench)
  app.provide(routeLocationKey, { query: {} })
  app.provide(routerKey, { currentRoute: { value: { path: '/video/workbench' } }, push() {} })
  app.component('RouterLink', { render: () => null })
  const nodes = function* (node) { yield node; for (const child of node.children) yield* nodes(child) }
  const text = node => [node.text || '', ...node.children.map(text)].join('')
  const find = predicate => [...nodes(root)].find(predicate)
  const button = label => find(node => node.tag === 'button' && text(node).includes(label))
  const count = type => [...nodes(root)].filter(node => node.tag === 'button' && node.props['aria-label'] === `删除${type}`).length
  const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }
  const add = async (type, length) => {
    const input = find(node => node.tag === 'input' && node.props.accept === `${type}/*`)
    assert.ok(Object.hasOwn(input.props, 'multiple'))
    input.props.onChange({ target: { files: Array.from({ length }, (_, index) => new File(['fixture'], `${type}-${index}`, { type: `${type}/${type === 'image' ? 'png' : type === 'video' ? 'mp4' : 'wav'}` })), value: 'selected' } })
    await settle()
  }
  try {
    app.mount(root); await settle()
    assert.match(text(button('参考图片')), /最多 30 张/)
    assert.match(text(button('参考视频')), /最多 10 个/)
    assert.match(text(button('音色')), /最多 10 个/)
    await add('image', 31); await add('video', 11); await add('audio', 11)
    assert.deepEqual([count('参考图'), count('参考视频'), count('音色')], [30, 10, 10])
    assert.match(text(root), /当前模型最多支持 10 个音频/)
    button('Seedance 2.0 Mini').props.onClick(); await settle()
    assert.deepEqual([count('参考图'), count('参考视频'), count('音色')], [9, 3, 3])
    assert.match(text(button('参考图片')), /最多 9 张/)
    assert.match(text(button('参考视频')), /最多 3 个/)
    assert.match(text(root), /已按 Seedance 2.0 Mini 的素材上限/)
    find(node => node.props?.id === 'video-prompt').props['onUpdate:modelValue']('多素材草稿')
    button('创作记录').props.onClick(); await settle()
    find(node => node.props?.['aria-label'] === '新对话').props.onClick(); await settle()
    assert.deepEqual([count('参考图'), count('参考视频'), count('音色')], [0, 0, 0])
    button('创作记录').props.onClick(); await settle()
    await button('多素材草稿').props.onClick(); await settle()
    assert.deepEqual([count('参考图'), count('参考视频'), count('音色')], [9, 3, 3])
    find(node => node.props?.['aria-label'] === '删除参考视频').props.onClick(); await settle()
    assert.equal(count('参考视频'), 2)
    const submitting = button('预估费用并生成').props.onClick()
    await settle()
    assert.equal(pendingVideos.length, 2)
    assert.equal(quotes.length, 0)
    assert.match(text(root), /正在校验参考视频/)
    assert.equal(button('正在生成视频').props.disabled, true)
    videosReady = true
    pendingVideos.forEach(resolve => resolve())
    await submitting; await settle()
    assert.equal(quotes.length, 1)
    assert.match(text(root), /测试费用预估暂时失败/)
    const uploadCount = uploads.length
    button('创作记录').props.onClick(); await settle()
    find(node => node.props?.['aria-label'] === '新对话').props.onClick(); await settle()
    button('创作记录').props.onClick(); await settle()
    await button('多素材草稿').props.onClick(); await settle()
    await button('预估费用并生成').props.onClick(); await settle()
    assert.equal(quotes.length, 2)
    assert.equal(uploads.length, uploadCount, 'retry must reuse the uploaded draft materials')
    assert.deepEqual(quotes[1], quotes[0], 'retry must keep the same materials and request key')
    assert.equal(quotes[0].model, 'SEEDANCE_2_0_MINI')
    assert.equal(quotes[0].referenceImageAssetIds.length, 9)
    assert.equal(quotes[0].referenceVideoAssetIds.length, 2)
    assert.equal(quotes[0].referenceAudioAssetIds.length, 3)
    assert.deepEqual(quotes[0].referenceVideoAssetIds, uploads.filter(entry => entry.type === 'VIDEO').map(entry => entry.assetId))
    assert.deepEqual(quotes[0].referenceAudioAssetIds, uploads.filter(entry => entry.type === 'AUDIO').map(entry => entry.assetId))
  } finally {
    app.unmount(); auth.state.user = null; auth.state.token = null
    await vite.close()
    for (const [key, descriptor] of Object.entries(saved)) { if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key] }
  }
})
