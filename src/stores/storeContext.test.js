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

test('editing another store keeps it in place and does not pull the user away from the store they are working in', async () => {
  const listed = [{ id: 1, name: '一店', version: 0 }, { id: 2, name: '二店', version: 3 }, { id: 3, name: '三店', version: 0 }]
  const context = createStoreContext({ list: async () => listed, update: async (id, values) => ({ id, ...values, version: values.version + 1 }) }, memoryStorage())
  await context.setAccount('tenant:user')
  assert.equal(context.selectedStoreId.value, 1)
  await context.saveStore({ name: '二店（城东）' }, listed[1])
  assert.deepEqual(context.stores.value.map(store => store.name), ['一店', '二店（城东）', '三店'])
  assert.equal(context.stores.value[1].version, 4)
  assert.equal(context.selectedStoreId.value, 1)
})

test('closing a store removes it, and closing the current one moves on to the first store left', async () => {
  const closed = []
  const context = createStoreContext({
    list: async () => [{ id: 1, name: '一店' }, { id: 2, name: '二店' }, { id: 3, name: '三店' }],
    archive: async id => { if (id === 3) throw new Error('这家店有正在进行的直播'); closed.push(id) },
  }, memoryStorage())
  await context.setAccount('tenant:user')
  await context.archiveStore({ id: 2 })
  assert.deepEqual(context.stores.value.map(store => store.id), [1, 3])
  assert.equal(context.selectedStoreId.value, 1)
  await context.archiveStore({ id: 1 })
  assert.equal(context.selectedStoreId.value, 3)
  // 服务端拒绝时什么都不变，原因原样交给页面显示。
  await assert.rejects(context.archiveStore({ id: 3 }), /正在进行的直播/)
  assert.deepEqual(context.stores.value.map(store => store.id), [3])
  assert.equal(context.selectedStoreId.value, 3)
  assert.deepEqual(closed, [2, 1])
})
