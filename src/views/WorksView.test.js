import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createRenderer, nextTick } from 'vue'
import { routerKey } from 'vue-router'
import { createServer } from 'vite'

test('image and video works download from cards and details, with busy and retry states', async t => {
  const saved = Object.fromEntries(['window', 'document', 'fetch'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  const files = [], requests = []
  let pendingDownload, failDownload = false
  const body = { children: [], appendChild() {} }
  globalThis.window = { addEventListener() {}, removeEventListener() {} }
  globalThis.document = { body, createElement: () => ({ click() { files.push(this.download) }, remove() {} }) }
  const response = data => ({ ok: true, json: async () => ({ code: 200, data }) })
  globalThis.fetch = async (url, options) => {
    if (url.startsWith('/api/image-creations/works')) return response({ items: [
      { id: 1, creationId: 9, url: 'https://bucket.test/image.png', createdAt: '2026-11-12T08:00:00Z', quality: 'HD' },
    ], totalPages: 1 })
    if (url.startsWith('/api/video/works')) return response({ items: [
      { id: 'video-id', outputUrl: 'https://bucket.test/video.mp4', createdAt: '2026-11-12T09:00:00Z', durationSeconds: 5 },
    ], totalPages: 1 })
    requests.push({ url, options })
    if (failDownload) return { ok: false, status: 403 }
    if (url.endsWith('video.mp4')) return new Promise(resolve => { pendingDownload = resolve })
    return { ok: true, blob: async () => new Blob(['image']) }
  }
  t.mock.method(URL, 'createObjectURL', () => 'blob:works-download')
  t.mock.method(URL, 'revokeObjectURL', () => {})
  const vite = await createServer({ configFile: false, optimizeDeps: { noDiscovery: true, entries: [] },
    server: { middlewareMode: true, hmr: false }, appType: 'custom',
    plugins: [{ name: 'works-download-interaction', load(id) {
      if (id.endsWith('/WorksView.vue')) return compileScript(parse(readFileSync(id, 'utf8'), { filename: id }).descriptor,
        { id: 'works-download-test', inlineTemplate: true }).content
    } }],
  })
  let app
  try {
    const { auth } = await vite.ssrLoadModule('/src/stores/auth.js')
    auth.state.user = { id: 1, tenantId: 7 }
    auth.state.token = { accessToken: 'test-only' }
    const { default: Works } = await vite.ssrLoadModule('/src/views/WorksView.vue')
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
      querySelector: () => body,
      insertStaticContent(text, parent, anchor) {
        const node = { text, children: [], parent }
        const index = anchor ? parent.children.indexOf(anchor) : -1
        parent.children.splice(index < 0 ? parent.children.length : index, 0, node)
        return [node, node]
      },
    })
    const root = { children: [] }
    app = renderer.createApp(Works)
    app.provide(routerKey, { push() {} })
    const nodes = function* (node) { yield node; for (const child of node.children) yield* nodes(child) }
    const text = node => [node.text || '', ...node.children.map(text)].join('')
    const find = predicate => [...nodes(root), ...nodes(body)].find(predicate)
    const button = label => find(node => node.tag === 'button' && (node.props['aria-label'] === label || text(node).includes(label)))
    const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }
    const click = node => node.props.onClick({ stopPropagation() {} })
    app.mount(root)
    await settle()
    t.mock.timers.enable({ apis: ['setTimeout'] })
    const videoButton = button('下载视频'), imageButton = button('下载图片')
    assert.ok(videoButton)
    assert.ok(imageButton)
    const videoCard = find(node => node.tag === 'article')
    videoCard.props.onKeydown({ key: 'Enter', target: videoButton, currentTarget: videoCard })
    assert.equal(button('关闭详情'), undefined, 'download keyboard events do not open the detail dialog')
    const downloading = click(videoButton)
    await nextTick()
    assert.equal(videoButton.props.disabled, true)
    await click(videoButton)
    assert.equal(requests.length, 1, 'repeated clicks must not create a second download')
    pendingDownload({ ok: true, blob: async () => new Blob(['video']) })
    await downloading; await nextTick()
    assert.deepEqual(files, ['一方志作品-video-video-id.mp4'])
    assert.equal(videoButton.props.disabled, false)
    assert.equal(button('关闭详情'), undefined, 'card download does not open the detail dialog')
    await click(imageButton); await nextTick()
    assert.deepEqual(files, ['一方志作品-video-video-id.mp4', '一方志作品-image-1.png'])
    assert.deepEqual(requests.map(row => row.options), [{ cache: 'no-store' }, { cache: 'no-store' }])

    videoCard.props.onClick(); await nextTick()
    const detailDownload = find(node => node.tag === 'button' && text(node).includes('下载视频'))
    assert.ok(detailDownload)
    failDownload = true
    await click(detailDownload); await nextTick()
    assert.match(text(body), /下载地址可能已过期/)
    assert.equal(detailDownload.props.disabled, false, 'failed download can be retried')
    assert.equal(files.length, 2)
    failDownload = false
    const retry = click(detailDownload)
    pendingDownload({ ok: true, blob: async () => new Blob(['video']) })
    await retry; await nextTick()
    assert.equal(files.length, 3)
    assert.doesNotMatch(text(body), /下载地址可能已过期/)
    click(button('关闭详情')); await nextTick()
    const imageCard = [...nodes(root)].filter(node => node.tag === 'article')[1]
    imageCard.props.onClick(); await nextTick()
    const imageDetailDownload = find(node => node.tag === 'button' && text(node).includes('下载图片'))
    assert.ok(imageDetailDownload)
    await click(imageDetailDownload)
    assert.equal(files.at(-1), '一方志作品-image-1.png')
  } finally {
    t.mock.timers.reset()
    app?.unmount(); await vite.close()
    for (const [key, descriptor] of Object.entries(saved)) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor)
      else delete globalThis[key]
    }
  }
})
