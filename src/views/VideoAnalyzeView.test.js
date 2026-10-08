import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createRenderer, nextTick } from 'vue'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createServer } from 'vite'

test('merchant edits immediately update the real view, copy, export and generation handoff and reset between records', async () => {
  const filename = resolve('src/views/VideoAnalyzeView.vue'), moduleId = filename.replaceAll('\\', '/').replace('.vue', '.interaction.js')
  const vite = await createServer({ configFile: false, cacheDir: 'node_modules/.vite-merchant-tests',
    optimizeDeps: { noDiscovery: true, entries: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom',
    plugins: [{ name: 'merchant-view-test', resolveId(id) { if (id === '/merchant-view-under-test') return moduleId },
      load(id) { if (id === moduleId) return compileScript(parse(readFileSync(filename, 'utf8'), { filename }).descriptor,
        { id: 'merchant-view', inlineTemplate: true }).content } }],
  })
  const clipboard = [], downloads = [], navigations = []
  const savedGlobals = Object.fromEntries(['window', 'document', 'navigator'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  const savedCreateUrl = URL.createObjectURL
  Object.defineProperty(globalThis, 'window', { configurable: true, value: { setTimeout: () => 1, clearTimeout() {} } })
  Object.defineProperty(globalThis, 'document', { configurable: true, value: { body: { appendChild() {} }, createElement: () => ({ click() {}, remove() {} }) } })
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { clipboard: { writeText: async text => { clipboard.push(text) } } } })
  URL.createObjectURL = blob => { downloads.push(blob); return 'blob:merchant-test' }
  const renderer = createRenderer({
    createElement: tag => ({ tag, tagName: tag.toUpperCase(), props: {}, children: [], addEventListener() {}, removeEventListener() {} }),
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
  const fixture = id => ({ id, mode: 'ai', status: 'SUCCEEDED', name: '界面测试视频', frames: [], durationMs: 6000, width: 1080, height: 1920,
    result: { schemaVersion: 4, productReferences: ['旧牌'], parameters: [], dimensions: [], shots: [], keyframes: [], highlights: [], suggestions: [], limitations: [],
      prompt: '生成竖屏六秒视频，如图中产品位于窗边，人物缓慢拿起，相机固定，柔和侧光。{{edit:e1}} {{edit:e2}}',
      reuseScript: '镜头 1｜00:00–00:06｜人物拿起如图中产品，相机固定，柔和侧光。{{edit:e1}} {{edit:e2}}',
      negativePrompt: '避免画面模糊和商品变形。', editableContent: [
        { id: 'e1', kind: 'brand', label: '品牌名称', original: '旧牌', source: 'video', start: 0, end: 6 },
        { id: 'e2', kind: 'subtitle', label: '活动字幕', original: '旧牌，活动 99 元', source: 'video', start: 0, end: 6 },
      ] } })
  let app
  try {
    const { videoAnalysisApi } = await vite.ssrLoadModule('/src/services/videoAnalysis.js')
    videoAnalysisApi.limits = async () => ({ configured: true, maxDurationSeconds: 60, maxBytes: 104857600 })
    videoAnalysisApi.list = async () => ({ items: [] })
    videoAnalysisApi.get = async id => fixture(Number(id))
    const { default: View } = await vite.ssrLoadModule('/merchant-view-under-test')
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/video/analyze', component: View }, { path: '/video/workbench', component: { render: () => null } }] })
    await router.push('/video/analyze?history=1')
    const realPush = router.push.bind(router)
    router.push = target => { navigations.push(target); return Promise.resolve() }
    const root = { children: [] }
    app = renderer.createApp(View).use(router); app.mount(root)
    const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }
    await settle()
    const nodes = function* (node) { yield node; for (const child of node.children) yield* nodes(child) }
    const find = predicate => [...nodes(root)].find(predicate)
    const text = node => [node.text || '', ...node.children.map(text)].join('')
    const field = label => find(node => node.tag === 'textarea' && node.props['aria-label'] === label)
    const block = label => find(node => node.tag === 'p' && node.props['aria-label'] === label)
    const button = label => find(node => node.tag === 'button' && text(node).includes(label))
    const edit = async (label, value) => { field(label).props.onInput({ target: { value } }); await nextTick() }
    const copyText = () => text(block('整体复刻提示词正文'))

    await edit('品牌名称修改内容', '欢喜')
    assert.match(copyText(), /品牌：“欢喜”/)
    assert.equal(field('活动字幕修改内容').props.value, '欢喜，活动 99 元')
    assert.doesNotMatch(copyText(), /旧牌|\{\{edit:/)
    await edit('活动字幕修改内容', ' 欢喜新品 59 元 ')
    assert.equal(field('活动字幕修改内容').props.value, ' 欢喜新品 59 元 ', 'editing keeps spaces while the copy text is trimmed')
    assert.match(copyText(), /画面字幕：“欢喜新品 59 元”/)
    assert.match(text(block('复刻脚本正文')), /欢喜新品 59 元/)
    await button('复制中文提示词').props.onClick()
    assert.equal(clipboard.at(-1), copyText())
    await button('复制脚本').props.onClick()
    assert.equal(clipboard.at(-1), text(block('复刻脚本正文')))
    button('导出拆解报告').props.onClick()
    const report = await downloads.at(-1).text()
    assert.match(report, /商家文案修改对照/)
    assert.match(report, /欢喜新品 59 元/)
    assert.match(report, /旧牌，活动 99 元/)
    button('一键生成同款').props.onClick()
    assert.equal(navigations.at(-1).path, '/video/workbench')
    assert.ok(navigations.at(-1).state.recreationPrompt.includes(copyText()))
    assert.ok(navigations.at(-1).state.recreationPrompt.includes(text(block('复刻脚本正文'))))
    assert.deepEqual(navigations.at(-1).query, { ratio: '9:16', duration: '6' })
    button('恢复默认').props.onClick(); await nextTick()
    assert.equal(field('品牌名称修改内容').props.value, '')
    assert.equal(field('活动字幕修改内容').props.value, '本店，活动 99 元')
    await edit('品牌名称修改内容', '上条记录的改名')
    await realPush('/video/analyze?history=2'); await settle()
    assert.equal(field('品牌名称修改内容').props.value, '')
    assert.doesNotMatch(copyText(), /上条记录的改名|欢喜/)
  } finally {
    app?.unmount()
    URL.createObjectURL = savedCreateUrl
    for (const [key, descriptor] of Object.entries(savedGlobals)) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor)
      else delete globalThis[key]
    }
    await vite.close()
  }
})
