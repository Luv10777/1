import test from 'node:test'
import assert from 'node:assert/strict'
import { createProductApi, productPayload } from './productApi.js'

const detail = (id = 8) => ({
  id, storeId: 3, name: '茶饮券', type: 'VOUCHER', category: '餐饮美食', price: 19.9,
  saleUnit: '张', specification: '饮品任选一杯', promotionRule: '周末可用', coreSellingPoints: '现点现做',
  version: 4, images: [{ id: 5, assetId: 11, sortOrder: 0 }],
  faqs: [{ id: 13, question: '需要预约吗？', answer: '无需预约。', status: 'ACTIVE', version: 2 }],
})

test('loads every product page and fetches omitted FAQs before returning UI records', async () => {
  const calls = []
  const api = createProductApi({ request: async endpoint => {
    calls.push(endpoint)
    if (endpoint === '/api/stores/3/products?page=0&size=100') return { items: [{ id: 8, faqs: [] }], totalPages: 2 }
    if (endpoint === '/api/stores/3/products?page=1&size=100') return { items: [{ id: 9, faqs: [] }], totalPages: 2 }
    if (endpoint.startsWith('/api/products/')) return detail(Number(endpoint.split('/').at(-1)))
    if (endpoint === '/api/assets/11/download-url') return { downloadUrl: 'https://assets.test/11' }
    assert.fail(`unexpected endpoint ${endpoint}`)
  } })
  const products = await api.listAll(3)
  assert.deepEqual(products.map(product => product.id), [8, 9])
  assert.equal(products[0].faqs[0].status, 'active')
  assert.equal(products[0].faqs[0].version, 2)
  assert.equal(products[0].unit, '张')
  assert.equal(products[0].type, 'voucher')
  assert.equal(products[0].images[0].assetId, 11)
  assert.equal(products[0].images[0].url, 'https://assets.test/11')
  assert.ok(calls.includes('/api/products/8'))
  assert.deepEqual(await api.listAll(null), [])
})

test('round trips facts, gallery order, FAQ content and optimistic version without sending signed URLs', async () => {
  const api = createProductApi({ request: async endpoint => endpoint.includes('/assets/') ? { downloadUrl: 'signed-url' } : detail() })
  const values = await api.get(8)
  values.images.unshift({ assetId: 12, url: 'other-signed-url' })
  const payload = productPayload(values, true)
  assert.equal(payload.type, 'VOUCHER')
  assert.equal(payload.saleUnit, '张')
  assert.equal(payload.specification, '饮品任选一杯')
  assert.equal(payload.promotionRule, '周末可用')
  assert.equal(payload.coreSellingPoints, '现点现做')
  assert.equal(payload.version, 4)
  assert.deepEqual(payload.images, [{ assetId: 12, sortOrder: 0 }, { assetId: 11, sortOrder: 1 }])
  assert.deepEqual(payload.faqs, [{ question: '需要预约吗？', answer: '无需预约。', sortOrder: 0 }])
  assert.equal(productPayload(values).version, undefined)
  assert.throws(() => productPayload({ ...values, images: ['data:image/png;base64,demo'] }), /尚未上传/)
})

test('uses PATCH for updates and does not mistake a preview failure for a failed product save', async () => {
  const writes = []
  const api = createProductApi({ request: async (endpoint, options) => {
    if (endpoint.includes('/assets/')) throw Error('storage unavailable')
    writes.push({ endpoint, ...options })
    return detail()
  } })
  const product = await api.update(8, { name: '茶饮券', type: 'voucher', price: 19.9, unit: '张', images: [], faqs: [], version: 4 })
  assert.equal(writes[0].endpoint, '/api/products/8')
  assert.equal(writes[0].method, 'PATCH')
  assert.equal(JSON.parse(writes[0].body).version, 4)
  assert.equal(product.id, 8)
  assert.equal(product.images[0].previewUnavailable, true)
  assert.equal(product.images[0].assetId, 11)
})

test('uploads actual image bytes without bearer credentials then confirms before exposing the image', async () => {
  const calls = []
  const file = { name: 'cover.png', type: 'image/png', size: 10 }
  const api = createProductApi({
    request: async (endpoint, options) => {
      calls.push(endpoint)
      if (endpoint === '/api/assets/upload-url') {
        assert.deepEqual(JSON.parse(options.body), { name: 'cover.png', type: 'IMAGE', mimeType: 'image/png' })
        return { assetId: 11, uploadUrl: 'https://storage.test/upload' }
      }
      if (endpoint.endsWith('/confirm')) { assert.deepEqual(JSON.parse(options.body), { sizeBytes: 10 }); return { status: 'READY' } }
      return { downloadUrl: 'https://storage.test/download' }
    },
    fetch: async (url, options) => {
      calls.push(url)
      assert.equal(options.body, file)
      assert.deepEqual(options.headers, { 'Content-Type': 'image/png' })
      assert.equal(options.method, 'PUT')
      return { ok: true }
    },
  })
  assert.deepEqual(await api.uploadImage(file), { assetId: 11, url: 'https://storage.test/download' })
  assert.deepEqual(calls, ['/api/assets/upload-url', 'https://storage.test/upload', '/api/assets/11/confirm', '/api/assets/11/download-url'])
})

test('does not confirm a failed upload or fall back to demo products after a request failure', async () => {
  const calls = []
  const api = createProductApi({
    request: async endpoint => { calls.push(endpoint); return { assetId: 11, uploadUrl: 'https://storage.test/upload' } },
    fetch: async () => ({ ok: false }),
  })
  await assert.rejects(api.uploadImage({ name: 'x.png', type: 'image/png', size: 1 }), /上传失败/)
  assert.deepEqual(calls, ['/api/assets/upload-url'])
  await assert.rejects(api.uploadImage({ name: 'x.gif', type: 'image/gif', size: 1 }), /JPG/)
  const failedApi = createProductApi({ request: async () => { throw Error('server unavailable') } })
  await assert.rejects(failedApi.listAll(3), /server unavailable/)
})
