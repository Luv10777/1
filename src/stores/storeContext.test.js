import test from 'node:test'
import assert from 'node:assert/strict'
import { createStoreContext } from './storeContext.js'

const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const memoryStorage = () => { const values = new Map(); return { getItem: key => values.get(key), setItem: (key, value) => values.set(key, value) } }

test('selection is restored only if the account can still access that store', async () => {
  const storage = memoryStorage()
  storage.setItem('wuyao-selected-store:tenant:user', '2')
  let accessible = [{ id: 1, name: '一店' }, { id: 2, name: '二店' }]
  const context = createStoreContext({ list: async () => accessible }, storage)
  await context.setAccount('tenant:user')
  assert.equal(context.selectedStore.value, '二店')
  accessible = [{ id: 1, name: '一店' }]
  await context.loadStores()
  assert.equal(context.selectedStoreId.value, 1)
  assert.equal(storage.getItem('wuyao-selected-store:tenant:user'), '1')
})

test('late responses from a previous account cannot expose its stores', async () => {
  const first = deferred(), second = deferred()
  let calls = 0
  const context = createStoreContext({ list: () => (++calls === 1 ? first.promise : second.promise) }, memoryStorage())
  const a = context.setAccount('a:user')
  const b = context.setAccount('b:user')
  second.resolve([{ id: 20, name: '乙门店' }]); await b
  first.resolve([{ id: 10, name: '甲门店' }]); await a
  assert.deepEqual(context.stores.value, [{ id: 20, name: '乙门店' }])
  assert.equal(context.selectedStoreId.value, 20)
  await context.setAccount('')
  assert.deepEqual(context.stores.value, [])
  assert.equal(context.selectedStoreId.value, null)
})

test('load failure clears accessible data and retry recovers', async () => {
  let fail = false
  const context = createStoreContext({ list: async () => { if (fail) throw new Error('网络不可用'); return [{ id: 1, name: '门店' }] } }, memoryStorage())
  await context.setAccount('tenant:user')
  fail = true
  await context.loadStores()
  assert.equal(context.storeError.value, '网络不可用')
  assert.equal(context.selectedStoreId.value, null)
  assert.equal(context.storeLoading.value, false)
  fail = false; await context.loadStores()
  assert.equal(context.selectedStoreId.value, 1)
  assert.equal(context.storeError.value, '')
})

test('successful create survives an older in-flight list and selects saved store', async () => {
  const oldList = deferred()
  const context = createStoreContext({ list: () => oldList.promise, create: async values => ({ id: 8, ...values, version: 0 }) }, memoryStorage())
  const loading = context.setAccount('tenant:user')
  await context.createStore({ name: '新店' })
  oldList.resolve([]); await loading
  assert.equal(context.selectedStore.value, '新店')
  assert.equal(context.storeLoading.value, false)
})

test('update sends previous version and ignores mutations finished after logout', async () => {
  const mutation = deferred()
  let submitted
  const context = createStoreContext({ list: async () => [{ id: 3, name: '门店', version: 4 }], update: (id, values) => { submitted = { id, ...values }; return mutation.promise } }, memoryStorage())
  await context.setAccount('tenant:user')
  const saving = context.saveStore({ name: '更名' }, context.selectedStoreRecord.value)
  assert.deepEqual(submitted, { id: 3, name: '更名', version: 4 })
  await context.setAccount('')
  mutation.resolve({ id: 3, name: '更名', version: 5 }); await saving
  assert.equal(context.selectedStoreId.value, null)
  assert.deepEqual(context.stores.value, [])
})
