import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createRenderer, h, nextTick, reactive } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createServer } from 'vite'

test('product carousel, submission progress and generated images occupy the workspace exclusively', async () => {
  const filename = resolve('src/components/ImageCreationWorkspace.vue')
  const moduleId = filename.replaceAll('\\', '/').replace('.vue', '.render-test.js')
  const vite = await createServer({ configFile: false, cacheDir: 'node_modules/.vite-workspace-tests',
    optimizeDeps: { noDiscovery: true }, server: { middlewareMode: true, hmr: false }, appType: 'custom',
    plugins: [{ name: 'workspace-test', resolveId(id) { if (id === '/workspace-under-test') return moduleId },
      load(id) { if (id === moduleId) return compileScript(parse(readFileSync(filename, 'utf8'), { filename }).descriptor,
        { id: 'workspace-test', inlineTemplate: true }).content } }],
  })
  const renderer = createRenderer({
    createElement: tag => ({ tag, props: {}, children: [], addEventListener() {}, removeEventListener() {} }),
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
      parent.children.splice(index < 0 ? parent.children.length : index, 0, node); return [node, node]
    },
  })
  let app
  try {
    const { default: Workspace } = await vite.ssrLoadModule('/workspace-under-test')
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/works', component: { render: () => null } }] })
    await router.push('/works')
    const studio = reactive({ product: true, workspaceTab: 'inspiration', history: [], thread: [], current: null,
      historyOpen: false, busy: false, active: false, pending: null, configured: true, revision: '', error: '' })
    const root = { children: [] }
    app = renderer.createApp({ render: () => h(Workspace, { studio }, { default: () => h('div', { class: 'test-carousel' }) }) }).use(router)
    app.mount(root)
    const nodes = function* (node) { yield node; for (const child of node.children) yield* nodes(child) }
    const exists = className => [...nodes(root)].some(node => typeof node.props?.class === 'string'
      && node.props.class.split(' ').includes(className))
    assert.equal(exists('test-carousel'), true)
    assert.equal(exists('creation-empty-result'), false, 'empty result must not consume carousel space')
    studio.workspaceTab = 'result'; studio.busy = true; await nextTick()
    assert.equal(exists('test-carousel'), false)
    assert.equal(exists('creation-empty-result'), true)
    studio.busy = false; studio.active = true
    studio.current = { id: 1, quality: '2K', ratio: '1:1', status: 'QUEUED', completed: 0, count: 3, items: [] }
    await nextTick()
    assert.equal(exists('test-carousel'), false)
    assert.equal(exists('creation-output'), true, 'billing note must not suppress progress or results')
    assert.equal([...nodes(root)].filter(node => node.props?.class === 'creation-image-placeholder').length, 3)
    studio.active = false; studio.current.status = 'SUCCEEDED'; studio.current.completed = 1
    studio.current.items = [{ id: 1, ordinal: 0, role: '主图', width: 2048, height: 2048, url: '/test-only.png', status: 'SUCCEEDED' }]
    studio.canUseAsset = () => true
    await nextTick()
    assert.equal([...nodes(root)].some(node => node.tag === 'img' && node.props.src === '/test-only.png'), true)
    studio.workspaceTab = 'inspiration'; await nextTick()
    assert.equal(exists('test-carousel'), true)
    assert.equal(exists('creation-output'), false, 'returning to inspiration must restore the complete carousel')
  } finally { app?.unmount(); await vite.close() }
})
