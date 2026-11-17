import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRenderer, nextTick, reactive } from 'vue'
import { routeLocationKey, routerKey } from 'vue-router'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createServer } from 'vite'

const memoryStorage = () => { const values = new Map(); return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, String(value)), removeItem: key => values.delete(key) } }

test('novice studio drives the poster and video workflows from one composer', async () => {
  const saved = Object.fromEntries(['window', 'document', 'Document', 'ShadowRoot', 'fetch', 'sessionStorage', 'localStorage', 'requestAnimationFrame'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  const events = []
  // <Transition> 需要的最少 DOM：类名、动画帧和计算样式（时长都是 0，切换立即完成）。
  const noTransition = { transitionDelay: '0s', transitionDuration: '0s', animationDelay: '0s', animationDuration: '0s' }
  globalThis.window = { history: { state: {} }, setTimeout, clearTimeout, dispatchEvent: event => events.push(event.type), addEventListener() {}, removeEventListener() {}, getComputedStyle: () => noTransition }
  globalThis.requestAnimationFrame = callback => setTimeout(callback, 0)
  globalThis.document = { body: { offsetHeight: 0 } }
  globalThis.Document = class {}
  globalThis.ShadowRoot = class {}
  globalThis.sessionStorage = memoryStorage()
  globalThis.localStorage = memoryStorage()
  const creations = [], quotes = [], submissions = [], ideas = []
  let releasePlan = null
  const planBatch = { id: 5, status: 'SUCCEEDED', createdAt: '2026-11-17T10:00:00Z', plans: [
    { title: '暖光手冲', idea: '清晨窗边的暖光', prompt: '暖光下的手冲咖啡，木质桌面' },
    { title: '极简白', idea: '大面积留白', prompt: '白底极简的咖啡杯' },
    { title: '街角', idea: '城市街角的氛围', prompt: '街角咖啡馆外摆' }] }
  const response = data => ({ ok: true, status: 200, json: async () => ({ code: 200, data }) })
  const creation = { id: 11, workflow: 'POSTER', parentId: null, status: 'SUCCEEDED', brief: '一杯手冲咖啡', ratio: '3:4', quality: '2K', count: 1, style: '电影质感',
    references: [{ assetId: 9, role: 'SUBJECT', name: '咖啡.png', url: '/reference.png' }],
    items: [{ id: 1, ordinal: 0, generation: 1, status: 'SUCCEEDED', url: '/generated.png', width: 1536, height: 2048, persisted: true, previewedAt: 'now' }] }
  globalThis.fetch = async (url, options = {}) => {
    const method = options.method || 'GET'
    if (url === '/api/billing/summary') return response({ memberActive: true, available: 100, imageCredits: 2, plan: { videoEnabled: true } })
    if (url === '/api/image-creations/capabilities') return response({ configured: true, qualities: ['1K', '2K', '4K'] })
    if (url.startsWith('/api/image-creations?')) return response({ items: [], totalPages: 0 })
    if (url === '/api/image-creations' && method === 'POST') { creations.push(JSON.parse(options.body)); return response(creation) }
    if (url === '/api/image-creations/11/thread') return response([creation])
    if (url === '/api/image/inspirations/config' || url === '/api/video/plans/config') return response({ configured: true, credits: 1, maxImages: 6 })
    if (url.startsWith('/api/image/inspirations?') || url.startsWith('/api/video/plans?')) return response({ items: [], totalPages: 0 })
    if (url === '/api/image/inspirations' && method === 'POST') { ideas.push(JSON.parse(options.body)); return new Promise(resolve => { releasePlan = () => resolve(response(planBatch)) }) }
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
        // 动效不在这里测：<Transition> 换成直接渲染内容的组件，切换立即生效。
        if (id.endsWith('/NoviceStudioView.vue')) return compileScript(parse(readFileSync(id, 'utf8'), { filename: id }).descriptor,
          { id: 'novice-studio-test', inlineTemplate: true }).content
          .replace(/Transition as _Transition,?\s*/, '') + '\nfunction _Transition(_, { slots }) { return slots.default?.() }\n'
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
    createElement: tag => ({ tag, props: {}, children: [], style: {}, classList: { add() {}, remove() {} }, getRootNode: () => ({ activeElement: null }), addEventListener() {}, removeEventListener() {} }),
    createText: text => ({ text, children: [] }), createComment: () => ({ text: '', children: [] }),
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
  // 过渡用了 requestAnimationFrame（这里是 setTimeout），多等几帧让切换完成。
  const settle = async () => { for (let i = 0; i < 4; i++) { await new Promise(resolve => setTimeout(resolve, 5)); await nextTick() } }
  const submit = async () => { find(node => node.tag === 'form' && String(node.props.class).split(' ').includes('ns-composer')).props.onSubmit({ preventDefault() {} }); await settle() }
  try {
    app.mount(root); await settle()
    assert.match(text(root), /^(早上好|上午好|中午好|下午好|傍晚好|晚上好|夜深了)/, 'the headline greets by time of day')
    const cost = () => text(find(node => node.props?.class === 'ns-cost'))
    assert.equal(cost(), '2', 'the cost of one image sits next to the send button')

    // 灵感方案：在对话里一问一答，等的时候三个点在跳，出了方案「用这个」填进输入框。
    button('灵感方案').props.onClick(); await settle()
    assert.match(cost(), /^1/, 'a plan request shows its own price')
    input().props['onUpdate:modelValue']('新品咖啡，安静高级'); await settle()
    await submit()
    assert.equal(ideas.length, 1)
    assert.equal(ideas[0].idea, '新品咖啡，安静高级')
    assert.ok(find(node => node.props?.class === 'ns-typing'), 'three dots hop while the plans are being written')
    releasePlan(); await settle()
    assert.ok(!find(node => node.props?.class === 'ns-typing'))
    assert.match(text(root), /暖光手冲.*极简白.*街角/s, 'the three plans are a reply in the conversation')
    button('用这个').props.onClick(); await settle()
    assert.match(input().value, /画面方案：暖光下的手冲咖啡/, 'the chosen plan fills the composer')
    assert.equal(cost(), '2', 'back to generating images after a plan is chosen')

    // 图片：选风格、写描述、生成，结果作为一轮对话出现。
    button('帮我搭配').props.onClick(); await settle()
    button('电影质感').props.onClick(); await settle()
    input().props['onUpdate:modelValue']('一杯手冲咖啡'); await settle()
    const more = find(node => node.props?.['aria-label'] === '多一张')
    for (let i = 0; i < 6; i++) { more.props.onClick(); await settle() }
    assert.equal(more.props.disabled, true, 'at most 6 images per request')
    find(node => node.props?.['aria-label'] === '少一张').props.onClick(); await settle()
    find(node => node.props?.['aria-label'] === '少一张').props.onClick(); await settle()
    find(node => node.props?.['aria-label'] === '少一张').props.onClick(); await settle()
    assert.equal(cost(), '6', 'the cost follows the image count')
    await submit()
    assert.equal(creations.length, 1)
    assert.equal(creations[0].workflow, 'POSTER')
    assert.equal(creations[0].brief, '一杯手冲咖啡')
    assert.equal(creations[0].style, '电影质感')
    assert.equal(creations[0].ratio, '3:4')
    assert.equal(creations[0].count, 3)
    assert.ok(find(node => node.tag === 'img' && node.props.src === '/reference.png'), 'the reference image is shown with the message')
    assert.ok(find(node => node.tag === 'img' && node.props.src === '/generated.png'), 'the generated image is shown in the thread')
    assert.equal(route.query.creation, '11')
    assert.ok(events.includes('novice-recents-changed'))

    // 视频：切到视频、选场景、先报价，确认后才提交；提交后输入框清空。
    button('视频').props.onClick(); await settle()
    button('自由创作').props.onClick(); await settle()
    button('电商带货').props.onClick(); await settle()
    input().props['onUpdate:modelValue']('新品香水上新，安静高级一点'); await settle()
    assert.match(text(root), /选择画面比例/, 'no ratio is picked by default')
    await submit()
    assert.equal(quotes.length, 0, 'nothing is quoted before a ratio is picked')
    assert.match(text(root), /请先选择画面比例/)
    button('9:16').props.onClick(); await settle()
    await submit()
    assert.equal(quotes.length, 1)
    assert.equal(quotes[0].ratio, '9:16')
    assert.equal(quotes[0].prompt, '新品香水上新，安静高级一点')
    assert.equal(quotes[0].sceneKey, 'ecommerce')
    assert.equal(submissions.length, 0, 'nothing is charged before the quote is confirmed')
    assert.match(text(root), /确认视频费用/)
    button('确认费用，开始生成').props.onClick(); await settle()
    assert.equal(submissions.length, 1)
    assert.equal(submissions[0].quoteId, 'quote-1')
    assert.ok(find(node => node.tag === 'video' && node.props.src === '/output.mp4#t=0.1'), 'the finished video is shown in the thread')
    assert.equal(input().value, '', 'the composer is ready for the next request')
    assert.match(text(root), /一杯手冲咖啡/, 'the earlier image turn stays in the conversation')
  } finally {
    await settle()
    app.unmount()
    await settle(); auth.state.user = null; auth.state.token = null
    await vite.close()
    for (const [key, descriptor] of Object.entries(saved)) { if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key] }
  }
})
