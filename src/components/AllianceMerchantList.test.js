import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createRenderer, nextTick } from 'vue'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createServer } from 'vite'

const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }

test('alliance tabs live in the URL, keep the product search and page through merchants', async () => {
  const vite = await createServer({ optimizeDeps: { noDiscovery: true, entries: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom', plugins: [{
    name: 'alliance-tabs-test',
    resolveId(id) {
      if (['/tabs-under-test', '/merchants-under-test'].includes(id)) return resolve(`src/components/${id.startsWith('/tabs') ? 'MerchantAllianceTabs' : 'AllianceMerchantList'}.interaction.js`).replaceAll('\\', '/')
    },
    load(id) {
      if (!id.endsWith('.interaction.js')) return
      const filename = id.replace('.interaction.js', '.vue')
      return compileScript(parse(readFileSync(filename, 'utf8'), { filename }).descriptor, { id: 'alliance-test' }).content
        .replace('__expose();', '').replace('return __returned__', '__expose(__returned__); return __returned__')
    },
  }] })
  let app
  try {
    const { commerceApi } = await vite.ssrLoadModule('/src/services/commerceApi.js')
    const requests = []
    const images = []
    commerceApi.allianceMerchants = params => new Promise(resolve => requests.push({ params, resolve }))
    commerceApi.publicImageUrl = id => { images.push(id); return Promise.resolve({ downloadUrl: `https://cdn.test/${id}.png` }) }
    const { default: Tabs } = await vite.ssrLoadModule('/tabs-under-test')
    const { default: Merchants } = await vite.ssrLoadModule('/merchants-under-test')
    Tabs.render = Merchants.render = () => null
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/merchant-alliance', name: 'merchant-alliance', component: { render: () => null } },
      { path: '/store/:storeId', name: 'public-storefront', component: { render: () => null } },
    ] })
    const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {} })

    await router.push('/merchant-alliance?keyword=茶&page=2')
    app = renderer.createApp(Tabs).use(router)
    const tabs = app.mount({})
    assert.equal(tabs.current, 'products')
    await tabs.select('merchants'); await settle()
    assert.deepEqual(router.currentRoute.value.query, { tab: 'merchants', keyword: '茶' }, 'switching keeps the search and resets the page')
    assert.equal(tabs.current, 'merchants')
    await tabs.select('products'); await settle()
    assert.deepEqual(router.currentRoute.value.query, { keyword: '茶' })
    app.unmount()

    await router.push('/merchant-alliance?tab=merchants&keyword=茶')
    app = renderer.createApp(Merchants).use(router)
    const merchants = app.mount({})
    assert.deepEqual(requests[0].params, { page: 0, size: 10 })
    requests[0].resolve({ total: 2, totalPages: 2, items: [
      { id: 1, merchantName: '甲商家', productTotal: 1, stores: [{ storeId: 5, storeName: '甲茶铺' }], products: [{ id: 9, storeId: 5, name: '龙井茶礼盒', price: 100, coverAssetId: 31 }] },
      { id: 2, merchantName: '乙商家', productTotal: 0, stores: [], products: [] },
    ] })
    await settle()
    assert.equal(merchants.feed.items.length, 2)
    assert.deepEqual(images, [31])
    assert.equal(merchants.covers[31], 'https://cdn.test/31.png')
    assert.deepEqual(merchants.storefront(5), { name: 'public-storefront', params: { storeId: 5 } })
    await merchants.go(1); await settle()
    assert.deepEqual(router.currentRoute.value.query, { tab: 'merchants', keyword: '茶', page: '1' })
    assert.deepEqual(requests[1].params, { page: 1, size: 10 })
  } finally {
    app?.unmount()
    await vite.close()
  }
})
