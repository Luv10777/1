import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createRenderer, createSSRApp, h, nextTick } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createServer } from 'vite'
import { informationDate, informationFilters, informationQuery, publicationState } from '../utils/informationPresentation.js'

const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }

test('information filters preserve a safe public return location and show dates in China time', () => {
  assert.deepEqual(informationFilters({ category: 'supply', keyword: '  直播  ', page: '2', redirect: 'https://example.com' }), { category: 'supply', keyword: '直播', page: 2 })
  assert.deepEqual(informationFilters({ category: 'invalid', keyword: ['a'], page: '-1' }), { category: '', keyword: '', page: 0 })
  assert.equal(informationFilters({ page: '99999999999999999999' }).page, 0)
  assert.deepEqual(informationQuery(informationFilters({ category: 'notice', keyword: '订单', page: '1', unrelated: 'ignored' })), { category: 'notice', keyword: '订单', page: '1' })
  assert.equal(informationDate('2026-10-14T18:00:00Z'), '2026/10/15')
  assert.equal(informationDate(null), '—')
  assert.equal(publicationState({ status: 'OFFLINE' }).visible, false)
  assert.deepEqual(publicationState({ status: 'PENDING_REVIEW' }), { label: '待审核', className: 'is-pending', visible: false })
  assert.deepEqual(publicationState({ status: 'REJECTED' }), { label: '已驳回', className: 'is-rejected', visible: false })
  assert.equal(publicationState({ status: 'PUBLISHED', validUntil: '2000-01-01' }).label, '已过期')
  assert.equal(publicationState({ status: 'PUBLISHED', validUntil: null }).visible, true)
})

test('public feed and detail keep the latest request and preserve filters through navigation', async () => {
  const vite = await createServer({ optimizeDeps: { noDiscovery: true, entries: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom', plugins: [{
    name: 'information-interaction-test',
    resolveId(id) {
      if (['/feed-under-test', '/detail-under-test'].includes(id)) return resolve(`src/views/${id.startsWith('/feed') ? 'PublicInformationView' : 'PublicInformationDetailView'}.interaction.js`).replaceAll('\\', '/')
    },
    load(id) {
      if (!id.endsWith('.interaction.js')) return
      const filename = id.replace('.interaction.js', '.vue')
      return compileScript(parse(readFileSync(filename, 'utf8'), { filename }).descriptor, { id: 'information-test' }).content
        .replace('__expose();', '').replace('return __returned__', '__expose(__returned__); return __returned__')
    },
  }] })
  let app
  try {
    const { businessCommunityService } = await vite.ssrLoadModule('/src/services/businessCommunity.js')
    const requests = []
    businessCommunityService.publicFeed = values => new Promise((resolve, reject) => requests.push({ values, resolve, reject }))
    const { default: Feed } = await vite.ssrLoadModule('/feed-under-test')
    const { default: Detail } = await vite.ssrLoadModule('/detail-under-test')
    Feed.render = Detail.render = () => null
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/information', name: 'public-information', component: Feed },
      { path: '/information/:id', name: 'public-information-detail', component: Detail },
    ] })
    const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {} })
    await router.push('/information')
    app = renderer.createApp(Feed).use(router)
    const view = app.mount({})
    assert.equal(requests.length, 1)
    await view.changeFilters({ category: 'supply' }); await settle()
    assert.equal(requests.length, 2)
    assert.equal(requests[1].values.category, 'supply')
    requests[1].resolve({ items: [{ id: 3, title: '已发布的商品信息' }], total: 1, totalPages: 1 })
    await settle()
    requests[0].reject(new Error('较早的请求失败'))
    await settle()
    assert.equal(view.feed.items[0].id, 3)
    assert.equal(view.error, '', 'an obsolete request cannot replace the latest feed with an error')
    assert.equal(view.loading, false)
    view.query = '  直播  '
    await view.search(); await settle()
    assert.equal(router.currentRoute.value.query.keyword, '直播')
    assert.equal(requests[2].values.keyword, '直播')
    requests[2].resolve({ items: [], total: 0, totalPages: 0 }); await settle()
    await router.back(); await settle()
    assert.equal(view.query, '')
    assert.equal(view.filters.category, 'supply')
    requests[3].resolve({ items: [], total: 0, totalPages: 0 }); await settle()
    app.unmount()

    const details = []
    businessCommunityService.publicInformation = id => new Promise((resolve, reject) => details.push({ id, resolve, reject }))
    await router.push('/information/3?category=supply&keyword=直播&page=2')
    app = renderer.createApp(Detail).use(router)
    const detail = app.mount({})
    assert.deepEqual(detail.back, { name: 'public-information', query: { category: 'supply', keyword: '直播', page: '2' } })
    await router.push('/information/4'); await settle()
    details[1].resolve({ id: 4, title: '新的公开信息' }); await settle()
    details[0].resolve({ id: 3, title: '较早的信息' }); await settle()
    assert.equal(detail.item.id, 4, 'rapid navigation must not show the previous article under a new URL')
    await router.push('/information/5'); await settle()
    details[2].reject(new Error('信息不存在或已过期')); await settle()
    assert.equal(detail.item, null)
    assert.equal(detail.error, '信息不存在或已过期')
  } finally {
    app?.unmount()
    await vite.close()
  }
})

test('article text renders safe paragraphs, working store links and escaped HTML', async () => {
  const vite = await createServer({ optimizeDeps: { noDiscovery: true, entries: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  try {
    const { default: Content } = await vite.ssrLoadModule('/src/components/InformationContent.vue')
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }] })
    await router.push('/information/3')
    const app = createSSRApp({ render: () => h(Content, { content: '商品详情\nhttps://yifangzhi.com/store/5。\n\n<script>alert(1)</script>\njavascript:alert(1)\n\n外部说明 https://example.com/help。' }) }).use(router)
    const html = await renderToString(app)
    assert.match(html, /href="\/store\/5"/)
    assert.match(html, /href="https:\/\/example.com\/help" target="_blank" rel="noopener noreferrer"/)
    assert.match(html, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/)
    assert.doesNotMatch(html, /href="javascript:|<script>/)
    assert.equal((html.match(/<p(?:\s[^>]*)?>/g) || []).length, 3)
  } finally { await vite.close() }
})
