import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRenderer, nextTick } from 'vue'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createServer } from 'vite'

async function mountPanel(config = { configured: true, credits: 1 }, props = {}, { history = [], storage = {} } = {}) {
  const saved = Object.fromEntries(['window', 'document', 'Document', 'ShadowRoot', 'fetch', 'sessionStorage'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  const timers = [], applied = [], requests = []
  Object.defineProperty(globalThis, 'window', { configurable: true, value: { setTimeout: callback => { timers.push(callback); return timers.length } } })
  Object.defineProperty(globalThis, 'document', { configurable: true, value: { activeElement: null } })
  globalThis.Document = class {}
  globalThis.ShadowRoot = class {}
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: {
    getItem: key => storage[key] ?? null, setItem: (key, value) => { storage[key] = value }, removeItem: key => { delete storage[key] } } })
  globalThis.fetch = async () => ({ ok: true, json: async () => ({ code: 200, data: { memberActive: true, plan: { videoEnabled: true } } }) })
  const vite = await createServer({ configFile: false, cacheDir: 'node_modules/.vite-video-plan-tests',
    optimizeDeps: { noDiscovery: true, entries: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom',
    plugins: [{ name: 'video-plan-interaction-test', load(id) {
      if (id.endsWith('.vue')) return compileScript(parse(readFileSync(id, 'utf8'), { filename: id }).descriptor,
        { id: 'video-plan-test', inlineTemplate: true }).content
    } }],
  })
  const { auth } = await vite.ssrLoadModule('/src/stores/auth.js')
  auth.state.user = { id: 1, tenantId: 7 }
  auth.state.token = { accessToken: 'test-only' }
  const { videoPlanApi } = await vite.ssrLoadModule('/src/services/videoPlans.js')
  videoPlanApi.config = async () => config
  videoPlanApi.create = async body => { requests.push(body); return { id: 7, status: 'QUEUED' } }
  videoPlanApi.history = async () => ({ items: history, totalPages: 1 })
  const { default: Panel } = await vite.ssrLoadModule('/src/components/VideoPlanPanel.vue')
  const renderer = createRenderer({
    createElement: tag => ({ tag, props: {}, children: [], getRootNode: () => ({ activeElement: null }), addEventListener() {}, removeEventListener() {} }),
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
    insertStaticContent(content, parent, anchor) {
      const node = { text: content, children: [], parent }, index = anchor ? parent.children.indexOf(anchor) : -1
      parent.children.splice(index < 0 ? parent.children.length : index, 0, node)
      return [node, node]
    },
  })
  const root = { children: [] }
  const app = renderer.createApp(Panel, { upload: async entry => entry.assetId, maxDuration: 10, ratios: ['auto', '9:16'],
    onApply: (...args) => applied.push(args), ...props })
  app.component('RouterLink', { render: () => null })
  app.mount(root)
  const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }
  await settle()
  const nodes = function* (node) { yield node; for (const child of node.children) yield* nodes(child) }
  const text = node => [node.text || '', ...node.children.map(text)].join('')
  const find = predicate => [...nodes(root)].find(predicate)
  const button = label => find(node => node.tag === 'button' && text(node).includes(label))
  const idea = value => find(node => node.props?.id === 'vp-idea').props['onUpdate:modelValue'](value)
  const answer = value => find(node => node.props?.id === 'vp-answer').props['onUpdate:modelValue'](value)
  return { videoPlanApi, storage, timers, applied, requests, root, text, button, idea, answer, settle,
    async close() {
      app.unmount(); auth.state.user = null; auth.state.token = null; await vite.close()
      for (const [key, descriptor] of Object.entries(saved)) {
        if (descriptor) Object.defineProperty(globalThis, key, descriptor)
        else delete globalThis[key]
      }
    },
  }
}

const plans = ['晨光', '窗边', '静物'].map(title => ({ title, idea: '新品上新', ratio: '9:16', durationSeconds: 12,
  prompt: `${title}中的商品展示`, negativePrompt: '画面文字', materials: [],
  shots: [{ start: 0, end: 4, description: '特写' }, { start: 4, end: 12, description: '拉远' }] }))

test('a missing activity benefit asks for one answer and preserves the original requirement on resubmission', async () => {
  const panel = await mountPanel(undefined, { maxDuration: 30, duration: 20, brief: '使用门店参考图' })
  try {
    panel.idea('双十一充值活动持续10天，生成20s宣传视频')
    panel.videoPlanApi.create = async body => {
      panel.requests.push(body)
      return { id: 7, status: 'FAILED', clarification: { question: '充值有哪些赠送权益？', originalIdea: body.idea, sourceBrief: body.sourceBrief } }
    }
    await panel.button('生成剧本').props.onClick(); await panel.settle()
    assert.match(panel.text(panel.root), /充值有哪些赠送权益/)
    assert.match(panel.text(panel.root), /预占烟火已退回/)
    assert.equal(panel.storage['video-plan-pending:1'], '7', 'a question can be restored after leaving the page')
    await panel.button('补充并生成剧本').props.onClick(); await panel.settle()
    assert.equal(panel.requests.length, 1)
    panel.answer('充值1000元赠送200元余额'); await panel.settle()
    panel.videoPlanApi.create = async body => { panel.requests.push(body); return { id: 8, status: 'SUCCEEDED', plans } }
    await panel.button('补充并生成剧本').props.onClick(); await panel.settle()
    assert.equal(panel.requests[1].clarificationAnswer, '充值1000元赠送200元余额')
    assert.equal(panel.requests[1].idea, panel.requests[0].idea)
    assert.equal(panel.requests[1].durationSeconds, 20)
    assert.equal(panel.requests[1].sourceBrief, '使用门店参考图')
    assert.notEqual(panel.requests[1].requestKey, panel.requests[0].requestKey)
    assert.equal(panel.storage['video-plan-pending:1'], undefined)
    assert.match(panel.text(panel.root), /A晨光/)
  } finally { await panel.close() }
})

test('video plans validate inputs, poll, preserve the goal on apply and keep the previous batch on failure', async () => {
  const panel = await mountPanel(undefined, { maxDuration: 15 })
  try {
    await panel.button('生成剧本').props.onClick(); await panel.settle()
    assert.match(panel.text(panel.root), /请先在左侧添加参考图/)
    assert.equal(panel.requests.length, 0)
    panel.idea('新品上新'); await panel.settle()
    panel.videoPlanApi.get = async () => ({ id: 7, status: 'SUCCEEDED', plans })
    const generating = panel.button('生成剧本').props.onClick()
    await panel.settle()
    assert.equal(panel.button('正在出方案').props.disabled, true)
    assert.equal(panel.requests[0].idea, '新品上新')
    panel.timers.shift()(); await generating; await panel.settle()
    assert.match(panel.text(panel.root), /A晨光.*B窗边.*C静物/)
    panel.button('用这个').props.onClick(); await panel.settle()
    assert.deepEqual(panel.applied[0], [{ prompt: '原始需求（保留未修改的要求，下面最后一句需求为最新要求）：\n新品上新\n晨光中的商品展示', format: '9:16', duration: 12, shortened: false }, 'A', null, 7])
    assert.equal('sceneKey' in panel.requests[0], false)
    assert.match(panel.text(panel.root), /已填入描述框/)
    panel.videoPlanApi.get = async () => ({ id: 8, status: 'FAILED', errorMessage: '校验失败，烟火已退回' })
    const regenerating = panel.button('换一批').props.onClick()
    await panel.settle()
    assert.equal(panel.requests[1].previousPlanId, 7)
    assert.notEqual(panel.requests[1].requestKey, panel.requests[0].requestKey)
    panel.timers.shift()(); await regenerating; await panel.settle()
    assert.match(panel.text(panel.root), /校验失败，烟火已退回/)
    assert.match(panel.text(panel.root), /晨光/)
  } finally { await panel.close() }
})

test('an ambiguous network failure reuses the request key until a terminal response arrives', async () => {
  const panel = await mountPanel()
  try {
    panel.idea('新品上新')
    panel.videoPlanApi.create = async body => { panel.requests.push(body); throw new Error('网络中断') }
    await panel.button('生成剧本').props.onClick(); await panel.settle()
    await panel.button('生成剧本').props.onClick(); await panel.settle()
    assert.equal(panel.requests.length, 2)
    assert.equal(panel.requests[0].requestKey, panel.requests[1].requestKey)
    panel.videoPlanApi.create = async body => { panel.requests.push(body); return { id: 7, status: 'FAILED' } }
    await panel.button('生成剧本').props.onClick(); await panel.settle()
    await panel.button('生成剧本').props.onClick(); await panel.settle()
    assert.notEqual(panel.requests[2].requestKey, panel.requests[3].requestKey)
  } finally { await panel.close() }
})

test('an unconfigured planner stays disabled and explains that merchants can write their own prompt', async () => {
  const panel = await mountPanel({ configured: false, credits: 1 })
  try {
    assert.equal(panel.button('生成剧本').props.disabled, true)
    await panel.button('生成剧本').props.onClick()
    assert.equal(panel.requests.length, 0)
    assert.match(panel.text(panel.root), /出方案暂未开放，可以直接在左侧写描述/)
  } finally { await panel.close() }
})

test('a larger video image selection respects the planner limit without changing the video materials', async () => {
  const images = Array.from({ length: 30 }, (_, id) => ({ id, assetId: id + 1, name: `图${id + 1}`, url: `/image-${id}.png` }))
  const panel = await mountPanel({ configured: true, credits: 1, maxImages: 6 }, { images })
  try {
    assert.match(panel.text(panel.root), /方案仅参考前 6 张图片/)
    panel.videoPlanApi.create = async body => { panel.requests.push(body); return { id: 9, status: 'SUCCEEDED', plans } }
    await panel.button('生成剧本').props.onClick()
    assert.deepEqual(panel.requests[0].imageAssetIds, [1, 2, 3, 4, 5, 6])
    assert.equal(images.length, 30)
  } finally { await panel.close() }
})

test('every video reference image goes to the planner, even before its limit has loaded', async () => {
  const images = Array.from({ length: 30 }, (_, id) => ({ id, assetId: id + 1, name: `图${id + 1}`, url: `/image-${id}.png` }))
  const panel = await mountPanel({ configured: true, credits: 1 }, { images })
  try {
    assert.doesNotMatch(panel.text(panel.root), /方案仅参考前/)
    panel.videoPlanApi.create = async body => { panel.requests.push(body); return { id: 9, status: 'SUCCEEDED', plans } }
    await panel.button('生成剧本').props.onClick()
    assert.equal(panel.requests[0].imageAssetIds.length, 30)
  } finally { await panel.close() }
})

test('leaving the page while plans are generating picks the batch up again on return and opens it when done', async () => {
  const first = await mountPanel()
  const storage = first.storage
  try {
    first.idea('新品上新')
    first.videoPlanApi.get = async () => ({ id: 7, status: 'GENERATING' })
    first.button('生成剧本').props.onClick(); await first.settle()
    assert.equal(storage['video-plan-pending:1'], '7')
  } finally { await first.close() }

  const record = { id: 7, style: '高级质感', idea: '新品上新', images: [], plans, createdAt: new Date().toISOString() }
  const panel = await mountPanel(undefined, {}, { history: [record], storage })
  try {
    assert.match(panel.text(panel.root), /正在出方案/)
    panel.videoPlanApi.get = async () => ({ id: 7, status: 'SUCCEEDED', plans })
    panel.timers.shift()(); await panel.settle(); await panel.settle()
    assert.match(panel.text(panel.root), /A晨光.*B窗边.*C静物/)
    assert.match(panel.text(panel.root), /高级质感 · 今天/)
    assert.equal(storage['video-plan-pending:1'], undefined)
  } finally { await panel.close() }
})

test('reusing a record with a missing image is blocked without shifting the remaining reference indexes', async () => {
  const record = { id: 5, style: '自定义港风', idea: '', createdAt: new Date().toISOString(), plans,
    images: [{ assetId: 11, name: '瓶身.png', url: '/a.png' }, { assetId: 12, name: '已删除', url: null }] }
  const panel = await mountPanel(undefined, { maxDuration: 15, images: [{ id: 'x', assetId: 99, name: '别的图', url: '/x.png' }] }, { history: [record] })
  try {
    panel.button('晨光').props.onClick(); await panel.settle()
    assert.match(panel.text(panel.root), /1 张参考图已删除/)
    panel.button('用这个').props.onClick(); await panel.settle()
    assert.equal(panel.applied.length, 0)
    assert.match(panel.text(panel.root), /图序号错位/)
  } finally { await panel.close() }
})

test('twenty second campaign requirements reach the planner and unsupported models do not start a request', async () => {
  const idea = '这个是双十一充值活动，开展10天的充值活动，为我这个充值活动生成20s的宣传推广视频'
  const panel = await mountPanel(undefined, { maxDuration: 30, duration: 10, ratio: '9:16', brief: '用参考人物口播宣传' })
  try {
    panel.idea(idea); await panel.settle()
    panel.videoPlanApi.get = async () => ({ id: 7, status: 'FAILED' })
    const running = panel.button('生成剧本').props.onClick(); await panel.settle()
    assert.equal(panel.requests[0].durationSeconds, 20)
    assert.equal(panel.requests[0].ratio, '9:16')
    assert.equal(panel.requests[0].sourceBrief, '用参考人物口播宣传')
    panel.timers.shift()(); await running
  } finally { await panel.close() }
  const short = await mountPanel(undefined, { maxDuration: 15 })
  try {
    short.idea(idea); await short.button('生成剧本').props.onClick(); await short.settle()
    assert.equal(short.requests.length, 0)
    assert.match(short.text(short.root), /不会自动缩短方案/)
  } finally { await short.close() }
})

test('plans follow the workbench scene, and opening a record switches the workbench back to its scene', async () => {
  const scenes = [{ key: 'free', name: '自由创作' }, { key: 'ecommerce', name: '电商带货' }, { key: 'drama', name: '剧情短片' }]
  const record = { id: 5, style: '', idea: '', sceneKey: 'drama', createdAt: new Date().toISOString(), plans, images: [] }
  const picked = []
  let pickerOpened = 0
  const panel = await mountPanel(undefined, { scene: 'ecommerce', scenes, onScene: key => picked.push(key), onPickScene: () => { pickerOpened++ } }, { history: [record] })
  try {
    assert.match(panel.text(panel.root), /场景电商带货更换场景/)
    assert.doesNotMatch(panel.text(panel.root), /自由创作/, 'scenes are not all laid out in the panel')
    panel.button('更换场景').props.onClick(); await panel.settle()
    assert.equal(pickerOpened, 1, 'the button opens the workbench scene picker')
    panel.button('晨光').props.onClick(); await panel.settle()
    assert.deepEqual(picked, ['drama'])
    assert.match(panel.text(panel.root), /剧情短片 · 今天/)
    panel.button('重选').props.onClick(); await panel.settle()
    panel.idea('新品上新'); await panel.settle()
    panel.videoPlanApi.get = async () => ({ id: 7, status: 'SUCCEEDED', plans, sceneKey: 'ecommerce' })
    const generating = panel.button('生成剧本').props.onClick()
    await panel.settle()
    assert.equal(panel.requests[0].sceneKey, 'ecommerce')
    panel.timers.shift()(); await generating; await panel.settle()
    assert.match(panel.text(panel.root), /重选.*电商带货|电商带货.*重选/)
  } finally { await panel.close() }
})
