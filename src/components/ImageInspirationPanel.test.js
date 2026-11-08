import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRenderer, nextTick, reactive } from 'vue'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createServer } from 'vite'

async function mountPanel(studioState, { history = [], config = { configured: true, credits: 1, maxImages: 6 } } = {}) {
  const saved = Object.fromEntries(['window', 'document', 'Document', 'ShadowRoot', 'fetch', 'sessionStorage'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  const timers = [], requests = [], storage = {}
  Object.defineProperty(globalThis, 'window', { configurable: true, value: { setTimeout: callback => { timers.push(callback); return timers.length } } })
  Object.defineProperty(globalThis, 'document', { configurable: true, value: { activeElement: null } })
  globalThis.Document = class {}
  globalThis.ShadowRoot = class {}
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: {
    getItem: key => storage[key] ?? null, setItem: (key, value) => { storage[key] = value }, removeItem: key => { delete storage[key] } } })
  globalThis.fetch = async () => ({ ok: true, json: async () => ({ code: 200, data: { memberActive: true } }) })
  const vite = await createServer({ configFile: false, cacheDir: 'node_modules/.vite-image-inspiration-tests',
    optimizeDeps: { noDiscovery: true, entries: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom',
    plugins: [{ name: 'image-inspiration-test', load(id) {
      if (id.endsWith('.vue')) return compileScript(parse(readFileSync(id, 'utf8'), { filename: id }).descriptor,
        { id: 'image-inspiration-test', inlineTemplate: true }).content
    } }],
  })
  const { auth } = await vite.ssrLoadModule('/src/stores/auth.js')
  auth.state.user = { id: 1, tenantId: 7 }
  auth.state.token = { accessToken: 'test-only' }
  const { imageInspirationApi } = await vite.ssrLoadModule('/src/services/imageInspirations.js')
  imageInspirationApi.config = async () => config
  imageInspirationApi.create = async body => { requests.push(body); return { id: 7, status: 'QUEUED' } }
  imageInspirationApi.history = async () => ({ items: history, totalPages: 1 })
  const { default: Panel } = await vite.ssrLoadModule('/src/components/ImageInspirationPanel.vue')
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
  })
  const studio = reactive({ files: [], style: '帮我搭配', brief: '', locked: false, ...studioState })
  const root = { children: [] }
  const app = renderer.createApp(Panel, { studio })
  app.mount(root)
  const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }
  await settle()
  const nodes = function* (node) { yield node; for (const child of node.children) yield* nodes(child) }
  const text = node => [node.text || '', ...node.children.map(text)].join('')
  const find = predicate => [...nodes(root)].find(predicate)
  const button = label => find(node => node.tag === 'button' && text(node).includes(label))
  const idea = value => find(node => node.props?.id === 'poster-idea-input').props['onUpdate:modelValue'](value)
  const keydown = event => find(node => node.props?.id === 'poster-idea-input').props.onKeydown(event)
  return { api: imageInspirationApi, studio, timers, requests, root, text, button, idea, keydown, settle,
    async close() {
      app.unmount(); auth.state.user = null; auth.state.token = null; await vite.close()
      for (const [key, descriptor] of Object.entries(saved)) {
        if (descriptor) Object.defineProperty(globalThis, key, descriptor)
        else delete globalThis[key]
      }
    },
  }
}

const plans = ['晨光窗台', '暗调静物', '花园午后'].map(title => ({ title, idea: '新品上新', materials: [{ index: 1, use: '商品主体' }], prompt: `${title}，如图1中的香水瓶居中` }))

test('inspiration plans send the left reference images and style, then fill only the picture description', async () => {
  const files = [{ file: null, name: '瓶身.png', role: 'SUBJECT', preview: '/a.png', assetId: 11 }]
  const panel = await mountPanel({ files, style: '写实摄影', brief: '原来写的描述' })
  try {
    assert.match(panel.text(panel.root), /风格写实摄影更换/)
    panel.idea('新品香水上新'); await panel.settle()
    panel.api.get = async () => ({ id: 7, status: 'SUCCEEDED', plans })
    const generating = panel.button('生成灵感方案').props.onClick()
    await panel.settle()
    assert.deepEqual({ ...panel.requests[0], requestKey: undefined },
      { requestKey: undefined, imageAssetIds: [11], style: '写实摄影', idea: '新品香水上新', previousId: null })
    panel.timers.shift()(); await generating; await panel.settle()
    assert.match(panel.text(panel.root), /A晨光窗台.*B暗调静物.*C花园午后/)
    panel.button('用这个').props.onClick(); await panel.settle()
    assert.equal(panel.studio.brief, '晨光窗台，如图1中的香水瓶居中')
    assert.deepEqual(panel.studio.files.map(file => file.assetId), [11], 'matching reference images stay as they are')
    assert.match(panel.text(panel.root), /已填入画面描述/)
  } finally { await panel.close() }
})

test('without a chosen style the request leaves style empty, and an empty form asks for an image or an idea', async () => {
  const panel = await mountPanel({})
  try {
    assert.match(panel.text(panel.root), /还没选风格/)
    await panel.button('生成灵感方案').props.onClick(); await panel.settle()
    assert.match(panel.text(panel.root), /请先在左侧添加参考图/)
    assert.equal(panel.requests.length, 0)
    panel.idea('开业海报'); await panel.settle()
    panel.api.get = async () => ({ id: 7, status: 'FAILED', errorMessage: '模型给出的方案不完整，烟火已退回，请重试' })
    const generating = panel.button('生成灵感方案').props.onClick()
    await panel.settle()
    assert.equal(panel.requests[0].style, '')
    panel.timers.shift()(); await generating; await panel.settle()
    assert.match(panel.text(panel.root), /烟火已退回/)
  } finally { await panel.close() }
})

test('enter generates plans, but not while an input method is still composing', async () => {
  const panel = await mountPanel({})
  try {
    panel.idea('开业海报'); await panel.settle()
    let prevented = 0
    const enter = extra => ({ key: 'Enter', ctrlKey: false, shiftKey: false, altKey: false, metaKey: false, preventDefault: () => { prevented++ }, ...extra })
    panel.keydown(enter({ isComposing: true })); await panel.settle()
    panel.keydown(enter({ keyCode: 229 })); await panel.settle()
    assert.equal(panel.requests.length, 0)
    assert.equal(prevented, 0, 'the input method keeps its own enter')
    panel.api.get = async () => ({ id: 7, status: 'SUCCEEDED', plans })
    panel.keydown(enter({})); await panel.settle()
    assert.equal(panel.requests.length, 1)
    assert.equal(prevented, 1)
  } finally { await panel.close() }
})

test('using a record brings back the reference images its description refers to', async () => {
  const record = { id: 5, style: '暗调光影', idea: '', createdAt: new Date().toISOString(), plans,
    images: [{ assetId: 11, name: '瓶身.png', url: '/a.png' }, { assetId: 12, name: '已删除', url: null }] }
  const panel = await mountPanel({ files: [{ file: null, name: '别的图', role: 'SUBJECT', preview: '/x.png', assetId: 99 }] }, { history: [record] })
  try {
    panel.button('晨光窗台').props.onClick(); await panel.settle()
    assert.match(panel.text(panel.root), /暗调光影 · 2 张参考图/)
    assert.match(panel.text(panel.root), /1 张参考图已删除/)
    panel.button('用这个').props.onClick(); await panel.settle()
    assert.deepEqual(panel.studio.files, [{ file: null, name: '瓶身.png', role: 'SUBJECT', preview: '/a.png', assetId: 11 }])
    assert.equal(panel.studio.brief, '晨光窗台，如图1中的香水瓶居中')
  } finally { await panel.close() }
})
