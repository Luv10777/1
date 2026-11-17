import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRenderer, nextTick, reactive } from 'vue'
import { routeLocationKey, routerKey } from 'vue-router'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createServer } from 'vite'

const memoryStorage = () => { const values = new Map(); return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, String(value)), removeItem: key => values.delete(key) } }

test('novice studio drives the poster and video workflows from one composer', async () => {
  const saved = Object.fromEntries(['window', 'document', 'Document', 'ShadowRoot', 'fetch', 'sessionStorage', 'localStorage'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  const events = []
  globalThis.window = { history: { state: {} }, setTimeout, clearTimeout, dispatchEvent: event => events.push(event.type), addEventListener() {}, removeEventListener() {} }
  globalThis.document = {}
  globalThis.Document = class {}
  globalThis.ShadowRoot = class {}
  globalThis.sessionStorage = memoryStorage()
  globalThis.localStorage = memoryStorage()
  const creations = [], quotes = [], submissions = []
  const response = data => ({ ok: true, status: 200, json: async () => ({ code: 200, data }) })
  const creation = { id: 11, workflow: 'POSTER', parentId: null, status: 'SUCCEEDED', brief: '一杯手冲咖啡', ratio: '3:4', quality: '2K', count: 1, style: '电影质感',
    items: [{ id: 1, ordinal: 0, generation: 1, status: 'SUCCEEDED', url: '/generated.png', width: 1536, height: 2048, persisted: true, previewedAt: 'now' }] }
  globalThis.fetch = async (url, options = {}) => {
    const method = options.method || 'GET'
    if (url === '/api/billing/summary') return response({ memberActive: true, available: 100, imageCredits: 2, plan: { videoEnabled: true } })
    if (url === '/api/image-creations/capabilities') return response({ configured: true, qualities: ['1K', '2K', '4K'] })
    if (url.startsWith('/api/image-creations?')) return response({ items: [], totalPages: 0 })
    if (url === '/api/image-creations' && method === 'POST') { creations.push(JSON.parse(options.body)); return response(creation) }
    if (url === '/api/image-creations/11/thread') return response([creation])
    if (url === '/api/video/capabilities') return response([])
    if (url === '/api/video/scenes') return response([{ key: 'free', name: '自由创作', summary: '', icon: 'edit_note' }, { key: 'ecommerce', name: '电商带货', summary: '商品主图视频、带货短视频', icon: 'shopping_bag' }])
    if (url.startsWith('/api/video/workflows?')) return response({ items: [], totalPages: 0 })
    if (url === '/api/video/quote') {
      quotes.push(JSON.parse(options.body))
      return response({ available: true, credits: 30, quoteId: 'quote-1', message: '按实际时长计费', providerModel: 'test-model', estimatedTokens: 1000, verifiedAt: '2026-11-17T08:00:00Z' })
    }
    if (url === '/api/video/workflows' && method === 'POST') {
      const body = JSON.parse(options.body)
      submissions.push(body)
      return response({ id: 5, status: 'SUCCEEDED', prompt: body.prompt, outputUrl: '/output.mp4', model: body.model, ratio: body.ratio, durationSeconds: body.durationSeconds, resolution: body.resolution, sceneKey: body.sceneKey, chargedCredits: 30 })
    }
    throw new Error(`Unexpected request: ${method} ${url}`)
  }
  const vite = await createServer({ configFile: false, cacheDir: 'node_modules/.vite-novice-studio-tests',
    optimizeDeps: { noDiscovery: true, entries: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom',
    plugins: [{ name: 'novice-studio-interaction',
      load(id) {
        if (id.endsWith('.css')) return ''
        if (id.endsWith('/NoviceStudioView.vue')) return compileScript(parse(readFileSync(id, 'utf8'), { filename: id }).descriptor,
          { id: 'novice-studio-test', inlineTemplate: true }).content
        if (id.endsWith('.vue')) return 'export default { render() { return null } }'
      },
    }],
  })
  const { auth } = await vite.ssrLoadModule('/src/stores/auth.js')
  auth.state.user = { id: 1, tenantId: 7 }
  auth.state.token = { accessToken: 'test-only' }
  // 页面外壳（AppShell）负责读账户余额；这里直接读一次。
  await (await vite.ssrLoadModule('/src/stores/billing.js')).refreshBilling()
  const { default: Studio } = await vite.ssrLoadModule('/src/views/NoviceStudioView.vue')
  const renderer = createRenderer({
    createElement: tag => ({ tag, props: {}, children: [], style: {}, getRootNode: () => ({ activeElement: null }), addEventListener() {}, removeEventListener() {} }),
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
  const route = reactive({ name: 'studio', path: '/studio', query: {} })
  const root = { children: [] }, app = renderer.createApp(Studio)
  app.provide(routeLocationKey, route)
  app.provide(routerKey, { currentRoute: { value: route }, push() {}, replace: async to => { route.query = { ...(to.query || {}) } } })
  app.component('RouterLink', { render: () => null })
  const nodes = function* (node) { yield node; for (const child of node.children) yield* nodes(child) }
  const text = node => [node.text || '', ...node.children.map(text)].join('')
  const find = predicate => [...nodes(root)].find(predicate)
  const button = label => find(node => node.tag === 'button' && text(node).includes(label))
  const input = () => find(node => node.props?.id === 'ns-input')
  const settle = async () => { for (let i = 0; i < 4; i++) { await new Promise(resolve => setImmediate(resolve)); await nextTick() } }
  const submit = async () => { find(node => node.tag === 'form' && node.props.class === 'ns-composer').props.onSubmit({ preventDefault() {} }); await settle() }
  try {
    app.mount(root); await settle()
    assert.match(text(root), /今天，想创作点什么？/)
    assert.match(text(root), /每张 2 烟火/)

    // 图片：选风格、写描述、生成，结果作为一轮对话出现。
    button('帮我搭配').props.onClick(); await settle()
    button('电影质感').props.onClick(); await settle()
    input().props['onUpdate:modelValue']('一杯手冲咖啡'); await settle()
    await submit()
    assert.equal(creations.length, 1)
    assert.equal(creations[0].workflow, 'POSTER')
    assert.equal(creations[0].brief, '一杯手冲咖啡')
    assert.equal(creations[0].style, '电影质感')
    assert.equal(creations[0].ratio, '3:4')
    assert.ok(find(node => node.tag === 'img' && node.props.src === '/generated.png'), 'the generated image is shown in the thread')
    assert.equal(route.query.creation, '11')
    assert.ok(events.includes('novice-recents-changed'))

    // 视频：切到视频、选场景、先报价，确认后才提交；提交后输入框清空。
    button('视频').props.onClick(); await settle()
    button('自由创作').props.onClick(); await settle()
    button('电商带货').props.onClick(); await settle()
    input().props['onUpdate:modelValue']('新品香水上新，安静高级一点'); await settle()
    await submit()
    assert.equal(quotes.length, 1)
    assert.equal(quotes[0].prompt, '新品香水上新，安静高级一点')
    assert.equal(quotes[0].sceneKey, 'ecommerce')
    assert.equal(submissions.length, 0, 'nothing is charged before the quote is confirmed')
    assert.match(text(root), /确认视频费用/)
    button('确认费用，开始生成').props.onClick(); await settle()
    assert.equal(submissions.length, 1)
    assert.equal(submissions[0].quoteId, 'quote-1')
    assert.ok(find(node => node.tag === 'video' && node.props.src === '/output.mp4'), 'the finished video is shown in the thread')
    assert.equal(input().value, '', 'the composer is ready for the next request')
    assert.match(text(root), /一杯手冲咖啡/, 'the earlier image turn stays in the conversation')
  } finally {
    app.unmount(); auth.state.user = null; auth.state.token = null
    await vite.close()
    for (const [key, descriptor] of Object.entries(saved)) { if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key] }
  }
})
