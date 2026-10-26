import test from 'node:test'
import assert from 'node:assert/strict'
import { orderStatuses } from './commerceApi.js'

test('commerce API uses the public storefront, order receipt and tenant-scoped merchant routes', async () => {
  const calls = []
  const { createServer } = await import('vite')
  const vite = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  try {
    globalThis.fetch = async (url, options) => {
      calls.push({ url, options })
      return { ok: true, status: 200, statusText: 'OK', json: async () => ({ code: 200, data: { status: 'PENDING_CONFIRMATION' } }) }
    }
    const module = await vite.ssrLoadModule('/src/services/commerceApi.js')
    for (const call of [
      () => module.commerceApi.publicStore(7),
      () => module.commerceApi.publicProducts(7),
      () => module.commerceApi.publicImageUrl(11),
      () => module.commerceApi.placeOrder({ items: [{ productId: 2, quantity: 1 }] }),
      () => module.commerceApi.publicOrder('random-token-123'),
      () => module.commerceApi.merchantOrders(7),
      () => module.commerceApi.updateOrder(7, 41, 'CONFIRMED'),
      () => module.commerceApi.catalog({ keyword: '茶', page: 1, size: 12 }),
      () => module.commerceApi.catalog({ keyword: '', page: 0 }),
      () => module.commerceApi.alipay('token/with space'),
      () => module.commerceApi.refreshOrder('token/with space'),
      () => module.commerceApi.allianceStores({ page: 1, size: 10 }),
    ]) await call()
    assert.deepEqual(calls.map(call => decodeURIComponent(new URL(call.url, 'http://local').pathname)), [
      '/api/public/stores/7', '/api/public/stores/7/products', '/api/public/assets/11/download-url',
      '/api/public/orders', '/api/public/orders/random-token-123', '/api/stores/7/orders',
      '/api/stores/7/orders/41',
      '/api/public/products', '/api/public/products',
      '/api/public/orders/token/with space/alipay', '/api/public/orders/token/with space/refresh',
      '/api/public/alliance-stores',
    ])
    assert.equal(new URL(calls[11].url, 'http://local').search, '?page=1&size=10')
    assert.equal(new URL(calls[7].url, 'http://local').search, '?keyword=%E8%8C%B6&page=1&size=12')
    assert.equal(new URL(calls[8].url, 'http://local').search, '?page=0', 'empty filters are not sent')
    assert.equal(JSON.parse(calls[3].options.body).items[0].productId, 2)
    assert.equal(JSON.parse(calls[6].options.body).status, 'CONFIRMED')
    assert.equal(calls[9].options.method, 'POST')
    assert.equal(calls[10].options.method, 'POST')
    assert.match(calls[9].url, /token%2Fwith%20space\/alipay/)
    assert.equal(module.orderStatuses.PENDING_CONFIRMATION, orderStatuses.PENDING_CONFIRMATION)
  } finally {
    globalThis.fetch = originalFetch
    await vite.close()
  }
})
