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
    if (url.startsWith('/api/library/folders')) return response({ folders: [], rootCount: 2 })
    if (url.startsWith('/api/library/works')) return response({ items: [
      { id: 21, kind: 'VIDEO', sourceId: 'video-id', folderId: null, video:
        { id: 'video-id', outputUrl: 'https://bucket.test/video.mp4', createdAt: '2026-11-12T09:00:00Z', durationSeconds: 5, billingWallet: 'TEAM' } },
      { id: 20, kind: 'IMAGE', sourceId: 1, folderId: null, image:
        { id: 1, creationId: 9, url: 'https://bucket.test/image.png', createdAt: '2026-11-12T08:00:00Z', quality: 'HD', billingWallet: 'PERSONAL' } },
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
      // 作品库页面和它用到的弹窗组件都按内联模板编译，样式不参与测试。
      if (id.endsWith('.vue')) return compileScript(parse(readFileSync(id, 'utf8'), { filename: id }).descriptor,
        { id: `works-test-${id.split('/').pop()}`, inlineTemplate: true }).content
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
    assert.match(text(videoCard), /团队账户扣款/)
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
    assert.match(text(imageCard), /个人账户扣款/)
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

test('works are filed into folders: create, open, move, remove, rename and delete', async () => {
  const saved = Object.fromEntries(['window', 'document', 'fetch', 'Document', 'ShadowRoot'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  const body = { children: [], appendChild() {} }
  // 输入框的 v-model 指令会检查焦点所在的根节点。
  globalThis.Document = class {}
  globalThis.ShadowRoot = class {}
  globalThis.window = { addEventListener() {}, removeEventListener() {} }
  globalThis.document = { body, createElement: () => ({ click() {}, remove() {} }) }
  // 后端的作品库，用内存模拟：两件作品，一个文件夹都没有。
  let folders = [], nextFolder = 1
  let library = [
    { id: 21, kind: 'VIDEO', sourceId: 5, folderId: null, video: { id: 5, outputUrl: 'https://bucket.test/video.mp4', createdAt: '2026-11-12T09:00:00Z', durationSeconds: 5, prompt: '开业视频' } },
    { id: 20, kind: 'IMAGE', sourceId: 1, folderId: null, image: { id: 1, creationId: 9, title: '夏日海报', url: 'https://bucket.test/image.png', createdAt: '2026-11-12T08:00:00Z' } },
  ]
  const calls = []
  const response = data => ({ ok: true, json: async () => ({ code: 200, data }) })
  const count = id => library.filter(work => work.folderId === id).length
  globalThis.fetch = async (url, options = {}) => {
    const method = options.method || 'GET'
    const path = url.split('?')[0]
    const params = new URLSearchParams(url.split('?')[1] || '')
    const data = options.body ? JSON.parse(options.body) : null
    calls.push(`${method} ${path}`)
    if (method === 'GET' && path === '/api/library/folders') {
      return response({ folders: folders.map(folder => ({ ...folder, workCount: count(folder.id) })), rootCount: count(null) })
    }
    if (method === 'POST' && path === '/api/library/folders') {
      const folder = { id: nextFolder++, name: data.name }
      folders.push(folder)
      return response({ ...folder, workCount: 0 })
    }
    const folderMatch = path.match(/^\/api\/library\/folders\/(\d+)$/)
    if (folderMatch && method === 'PUT') {
      const folder = folders.find(item => item.id === Number(folderMatch[1]))
      folder.name = data.name
      return response({ ...folder, workCount: count(folder.id) })
    }
    if (folderMatch && method === 'DELETE') {
      const id = Number(folderMatch[1])
      folders = folders.filter(item => item.id !== id)
      library = library.filter(work => work.folderId !== id)
      return response(null)
    }
    if (method === 'GET' && path === '/api/library/works') {
      const folder = params.get('folder')
      const items = library.filter(work => folder === 'all' || (folder === 'root' ? work.folderId === null : work.folderId === Number(folder)))
      return response({ items, totalPages: 1 })
    }
    const moveMatch = path.match(/^\/api\/library\/works\/(\d+)\/folder$/)
    if (moveMatch) {
      library.find(work => work.id === Number(moveMatch[1])).folderId = data.folderId
      return response(null)
    }
    const removeMatch = path.match(/^\/api\/library\/works\/(\d+)$/)
    if (removeMatch && method === 'DELETE') {
      library = library.filter(work => work.id !== Number(removeMatch[1]))
      return response(null)
    }
    throw new Error(`unexpected request ${method} ${url}`)
  }
  const vite = await createServer({ configFile: false, optimizeDeps: { noDiscovery: true, entries: [] },
    server: { middlewareMode: true, hmr: false }, appType: 'custom',
    plugins: [{ name: 'works-folder-interaction', load(id) {
      if (id.endsWith('.vue')) return compileScript(parse(readFileSync(id, 'utf8'), { filename: id }).descriptor,
        { id: `works-folder-${id.split('/').pop()}`, inlineTemplate: true }).content
    } }],
  })
  let app
  try {
    const { auth } = await vite.ssrLoadModule('/src/stores/auth.js')
    auth.state.user = { id: 1, tenantId: 7 }
    auth.state.token = { accessToken: 'test-only' }
    const { default: Works } = await vite.ssrLoadModule('/src/views/WorksView.vue')
    const renderer = createRenderer({
      createElement: tag => ({ tag, props: {}, children: [], addEventListener() {}, removeEventListener() {}, focus() {}, getRootNode() { return { activeElement: null } } }),
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
    const all = () => [...nodes(root), ...nodes(body)]
    const button = (label, within) => [...(within ? nodes(within) : all())]
      .find(node => node.tag === 'button' && (node.props['aria-label'] === label || text(node).includes(label)))
    const settle = async () => { for (let i = 0; i < 4; i++) { await new Promise(resolve => setImmediate(resolve)); await nextTick() } }
    const click = async node => { assert.ok(node, 'button exists'); await node.props.onClick({ stopPropagation() {}, preventDefault() {} }); await settle() }
    const cards = () => [...nodes(root)].filter(node => node.tag === 'article')
    const folderBar = () => [...nodes(root)].find(node => node.tag === 'nav')
    const typeInto = async value => {
      const input = [...nodes(body)].find(node => node.tag === 'input')
      input.props['onUpdate:modelValue'](value)
      await settle()
    }
    const submitForm = async () => {
      const form = [...nodes(body)].find(node => node.tag === 'form')
      await form.props.onSubmit({ preventDefault() {} })
      await settle()
    }
    app.mount(root)
    await settle()
    assert.equal(cards().length, 2)
    assert.match(text(folderBar()), /全部作品2/)
    assert.match(text(folderBar()), /未归类2/)

    // 新建文件夹后直接打开它，是空的。
    await click(button('新建文件夹', folderBar()))
    await typeInto('七月海报')
    await submitForm()
    assert.deepEqual(folders, [{ id: 1, name: '七月海报' }])
    assert.equal(cards().length, 0)
    assert.match(text(root), /这个文件夹还是空的/)

    // 回到全部作品，把图片移进文件夹。
    await click(button('全部作品', folderBar()))
    const imageCard = cards().find(card => text(card).includes('夏日海报'))
    await click(button('移动到文件夹', imageCard))
    const dialog = [...nodes(body)].find(node => node.props?.role === 'dialog' && node.props['aria-label'] === '移动到')
    await click([...nodes(dialog)].find(node => node.props?.role === 'radio' && text(node).includes('七月海报')))
    await click(button('移到这里', dialog))
    assert.equal(library.find(work => work.id === 20).folderId, 1)
    assert.match(text(imageCard), /七月海报/, 'the card shows where it is filed')
    assert.match(text(folderBar()), /七月海报1/)
    assert.match(text(folderBar()), /未归类1/)

    await click(button('七月海报', folderBar()))
    assert.equal(cards().length, 1)

    // 改名
    await click(button('重命名'))
    await typeInto('暑期海报')
    await submitForm()
    assert.equal(folders[0].name, '暑期海报')
    assert.match(text(folderBar()), /暑期海报1/)

    // 未归类里的视频移出作品库
    await click(button('未归类', folderBar()))
    assert.equal(cards().length, 1)
    await click(button('移出作品库', cards()[0]))
    await click([...nodes(body)].find(node => node.tag === 'button' && text(node).trim() === '移出'))
    assert.equal(library.some(work => work.id === 21), false)
    assert.equal(cards().length, 0)

    // 删除文件夹，里面的作品一起移出作品库
    await click(button('暑期海报', folderBar()))
    await click(button('删除文件夹'))
    assert.match(text(body), /1 件作品会一起移出作品库/)
    await click([...nodes(body)].find(node => node.tag === 'button' && text(node).trim() === '删除'))
    assert.deepEqual(folders, [])
    assert.deepEqual(library, [])
    assert.match(text(folderBar()), /全部作品0/)
    assert.ok(calls.includes('DELETE /api/library/folders/1'))
  } finally {
    app?.unmount(); await vite.close()
    for (const [key, descriptor] of Object.entries(saved)) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor)
      else delete globalThis[key]
    }
  }
})
