import test from 'node:test'
import assert from 'node:assert/strict'
import { emptyProduct, validateProduct, normalizeProduct, filterProducts, productStorageKey, loadProducts, saveProducts, getProductImages } from './products.js'

const makeStorage = () => {
  const values = new Map()
  return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) }
}
const validProduct = () => ({ ...emptyProduct(), id: 'one', name: '茶饮券', type: 'voucher', category: '到店核销', price: 19.9, unit: '张', points: '现点现做', updatedAt: new Date().toISOString() })

test('validates required facts and rejects negative or over-precision prices', () => {
  assert.equal(Object.keys(validateProduct(emptyProduct())).length, 5)
  assert.deepEqual(validateProduct(validProduct()), {})
  for (const price of [-1, '19.999', '', 'Infinity', '1e3']) assert.ok(validateProduct({ ...validProduct(), price }).price)
  assert.deepEqual(validateProduct({ ...validProduct(), price: 0 }), {})
  assert.ok(validateProduct({ ...validProduct(), name: '  ' }).name)
})

test('normalizes form values without leaking sample IDs into new products', () => {
  const value = normalizeProduct({ ...validProduct(), name: ' 茶饮券 ', price: '19.90' })
  assert.equal(value.name, '茶饮券')
  assert.equal(value.price, 19.9)
  assert.equal(value.id, undefined)
})

test('combines type filters and name/category/price search', () => {
  const products = [validProduct(), { ...validProduct(), id: 'two', name: '芝麻丸', type: 'physical', category: '食品', price: 69 }]
  assert.equal(filterProducts(products, 'all', '食品 69').length, 1)
  assert.equal(filterProducts(products, 'voucher', '食品').length, 0)
  assert.equal(filterProducts(products, 'voucher', '茶饮 19.90').length, 1)
  assert.equal(filterProducts(products, 'all', '   ').length, 2)
})

test('persists creation, edits and an empty library across reloads', () => {
  const storage = makeStorage()
  const key = productStorageKey('tenant', 'store')
  const product = validProduct()
  saveProducts(storage, key, [product])
  assert.deepEqual(loadProducts(storage, key), [product])
  saveProducts(storage, key, [{ ...product, price: 29.9 }])
  assert.equal(loadProducts(storage, key)[0].price, 29.9)
  saveProducts(storage, key, [])
  assert.deepEqual(loadProducts(storage, key), [])
})

test('isolates tenants and stores and only seeds a new library', () => {
  const storage = makeStorage()
  const key = productStorageKey('tenant', 'store')
  saveProducts(storage, key, [])
  for (const other of [productStorageKey('tenant-2', 'store'), productStorageKey('tenant', 'store-2')]) {
    assert.notEqual(key, other)
    assert.equal(loadProducts(storage, other).length, 3)
  }
})

test('reports storage failures and corrupt records rather than silently replacing data', () => {
  assert.throws(() => loadProducts({ getItem: () => '{' }, 'key'))
  assert.throws(() => loadProducts({ getItem: () => '[null]' }, 'key'))
  assert.throws(() => saveProducts({ setItem: () => { throw Error('quota') } }, 'key', []), /quota/)
})

test('preserves gallery order and cover through save and reload', () => {
  const storage = makeStorage()
  const images = ['data:image/jpeg;base64,cover', 'data:image/jpeg;base64,detail']
  const product = { ...validProduct(), ...normalizeProduct({ ...validProduct(), images }) }
  saveProducts(storage, 'gallery', [product])
  assert.deepEqual(getProductImages(loadProducts(storage, 'gallery')[0]), images)
  const draft = getProductImages(product)
  draft.reverse()
  assert.deepEqual(product.images, images, 'editing a draft must not mutate the saved gallery')
  assert.deepEqual(normalizeProduct({ ...product, images: draft }).images, [...images].reverse())
})

test('migrates legacy single images and does not resurrect a removed cover', () => {
  const legacy = { ...validProduct(), image: 'data:image/jpeg;base64,legacy' }
  delete legacy.images
  assert.deepEqual(normalizeProduct(legacy).images, [legacy.image])
  assert.deepEqual(normalizeProduct({ ...legacy, images: [] }).images, [])
  assert.equal(normalizeProduct(legacy).image, undefined)
  assert.deepEqual(getProductImages({ name: '无图商品' }), [])
})

test('keeps persisted asset identities when editing or reordering image previews', () => {
  const product = { ...validProduct(), images: [{ assetId: 12, url: 'signed-preview', sortOrder: 0 }, { assetId: 13, url: '' }] }
  const draft = getProductImages(product)
  draft[0].url = 'refreshed-preview'
  draft.reverse()
  assert.equal(product.images[0].url, 'signed-preview')
  assert.deepEqual(normalizeProduct({ ...product, images: draft }).images.map(image => image.assetId), [13, 12])
})
