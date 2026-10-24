import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRenderer, nextTick } from 'vue'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createServer } from 'vite'

async function mountPanel(config = { configured: true, credits: 1 }, props = {}) {
  const saved = Object.fromEntries(['window', 'document', 'Document', 'ShadowRoot', 'fetch'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  const timers = [], applied = [], requests = []
  Object.defineProperty(globalThis, 'window', { configurable: true, value: { setTimeout: callback => { timers.push(callback); return timers.length } } })
  Object.defineProperty(globalThis, 'document', { configurable: true, value: { activeElement: null } })
  globalThis.Document = class {}
  globalThis.ShadowRoot = class {}
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
  return { videoPlanApi, timers, applied, requests, root, text, button, idea, settle,
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

test('video plans validate inputs, poll, apply with model limits and preserve the previous batch on failure', async () => {
  const panel = await mountPanel()
  try {
    await panel.button('生成提示词').props.onClick(); await panel.settle()
    assert.match(panel.text(panel.root), /请先在左侧添加参考图/)
    assert.equal(panel.requests.length, 0)
    panel.idea('新品上新'); await panel.settle()
    panel.videoPlanApi.get = async () => ({ id: 7, status: 'SUCCEEDED', plans })
    const generating = panel.button('生成提示词').props.onClick()
    await panel.settle()
    assert.equal(panel.button('正在出方案').props.disabled, true)
    assert.equal(panel.requests[0].idea, '新品上新')
    panel.timers.shift()(); await generating; await panel.settle()
    assert.match(panel.text(panel.root), /方案 A.*方案 B.*方案 C/)
    panel.button('用这个').props.onClick(); await panel.settle()
    assert.deepEqual(panel.applied[0], [{ prompt: '晨光中的商品展示\n避免：画面文字', format: '9:16', duration: 10, shortened: true }, 'A'])
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
    await panel.button('生成提示词').props.onClick(); await panel.settle()
    await panel.button('生成提示词').props.onClick(); await panel.settle()
    assert.equal(panel.requests.length, 2)
    assert.equal(panel.requests[0].requestKey, panel.requests[1].requestKey)
    panel.videoPlanApi.create = async body => { panel.requests.push(body); return { id: 7, status: 'FAILED' } }
    await panel.button('生成提示词').props.onClick(); await panel.settle()
    await panel.button('生成提示词').props.onClick(); await panel.settle()
    assert.notEqual(panel.requests[2].requestKey, panel.requests[3].requestKey)
  } finally { await panel.close() }
})

test('an unconfigured planner stays disabled and explains that merchants can write their own prompt', async () => {
  const panel = await mountPanel({ configured: false, credits: 1 })
  try {
    assert.equal(panel.button('生成提示词').props.disabled, true)
    await panel.button('生成提示词').props.onClick()
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
    await panel.button('生成提示词').props.onClick()
    assert.deepEqual(panel.requests[0].imageAssetIds, [1, 2, 3, 4, 5, 6])
    assert.equal(images.length, 30)
  } finally { await panel.close() }
})
