import test from 'node:test'
import assert from 'node:assert/strict'
import { createKnowledgeApi } from './knowledgeApi.js'

test('knowledge lists retrieve every page and preserve backend status', async () => {
  const calls = []
  const api = createKnowledgeApi(async endpoint => {
    calls.push(endpoint)
    const url = new URL(endpoint, 'http://localhost')
    const page = Number(url.searchParams.get('page'))
    assert.equal(url.searchParams.get('size'), '100')
    return { items: [{ id: page + 1, status: 'ACTIVE' }], totalPages: 3 }
  })
  assert.deepEqual((await api.listSets(12)).map(item => item.id), [1, 2, 3])
  assert.equal(calls.length, 3)
  assert.equal(calls[2], '/api/stores/12/knowledge-sets?page=2&size=100')
  calls.length = 0
  const entries = await api.listEntries(80)
  assert.equal(calls[0], '/api/knowledge-sets/80/entries?page=0&size=100')
  assert.equal(entries[0].status, 'ACTIVE')
})

test('empty knowledge pages terminate and pagination failures do not return partial libraries', async () => {
  let count = 0
  const empty = createKnowledgeApi(async () => { count++; return { items: [], totalPages: 0 } })
  assert.deepEqual(await empty.listSets(12), [])
  assert.equal(count, 1)
  const broken = createKnowledgeApi(async endpoint => {
    if (endpoint.includes('page=1')) throw new Error('Network unavailable')
    return { items: [{ id: 1 }], totalPages: 2 }
  })
  await assert.rejects(broken.listEntries(3), /Network unavailable/)
})

test('knowledge context is per store and takes no product filter', async () => {
  const calls = []
  const api = createKnowledgeApi(async endpoint => { calls.push(endpoint); return { entries: [] } })
  await api.context(12)
  assert.deepEqual(calls, ['/api/stores/12/knowledge-context'])
})

test('knowledge writes use the API contract and never add a tenant or demo identity', async () => {
  const calls = []
  const api = createKnowledgeApi(async (endpoint, options) => { calls.push({ endpoint, ...options }); return { id: 7 } })
  const newSet = { name: '门店服务', kind: 'FAQ', description: '日常问答' }
  const newEntry = { question: '几点营业？', answer: '10:00', source: 'MANUAL', status: 'ACTIVE' }
  await api.createSet(12, newSet)
  await api.updateSet(7, { name: '门店营业' })
  await api.createEntry(7, newEntry)
  await api.updateEntry(8, { answer: '11:00' })
  await api.deleteEntry(8)
  await api.archive(7)
  await api.restore(7)
  assert.deepEqual(calls.map(({ endpoint, method }) => [endpoint, method]), [
    ['/api/stores/12/knowledge-sets', 'POST'],
    ['/api/knowledge-sets/7', 'PATCH'],
    ['/api/knowledge-sets/7/entries', 'POST'],
    ['/api/knowledge-entries/8', 'PATCH'],
    ['/api/knowledge-entries/8', 'DELETE'],
    ['/api/knowledge-sets/7/archive', 'POST'],
    ['/api/knowledge-sets/7/restore', 'POST'],
  ])
  assert.deepEqual(JSON.parse(calls[0].body), newSet)
  assert.deepEqual(JSON.parse(calls[2].body), newEntry)
  assert.equal(calls[4].body, undefined)
})

test('brand-owned sets and documents use the brand routes', async () => {
  const calls = []
  const api = createKnowledgeApi(async (endpoint, options) => { calls.push([endpoint, options?.method || 'GET']); return { items: [], totalPages: 1 } })
  await api.listBrandSets(9)
  await api.createBrandSet(9, { name: '品牌通用', kind: 'FAQ' })
  await api.listDocuments({ brandId: 9 })
  await api.listDocuments({ storeId: 12 })
  await api.acceptSuggestion(5, { knowledgeSetId: 3 })
  await api.dismissSuggestion(5)
  assert.deepEqual(calls, [
    ['/api/brands/9/knowledge-sets?page=0&size=100', 'GET'],
    ['/api/brands/9/knowledge-sets', 'POST'],
    ['/api/brands/9/knowledge-documents', 'GET'],
    ['/api/stores/12/knowledge-documents', 'GET'],
    ['/api/knowledge-suggestions/5/accept', 'POST'],
    ['/api/knowledge-suggestions/5/dismiss', 'POST'],
  ])
})

test('uploading a document checks the file, puts it to storage, then registers it for the owner', async () => {
  const calls = []
  const send = async (endpoint, options) => {
    calls.push([endpoint, options?.method])
    return endpoint === '/api/assets/upload-url' ? { assetId: 44, uploadUrl: 'https://storage.example/put' } : { id: 7 }
  }
  const puts = []
  const api = createKnowledgeApi(send, async (url, options) => { puts.push([url, options.headers['Content-Type']]); return { ok: true } })
  const file = { name: '品牌手册.PDF', type: 'application/pdf', size: 1024 }
  assert.deepEqual(await api.uploadDocument({ brandId: 9 }, file), { id: 7 })
  assert.deepEqual(puts, [['https://storage.example/put', 'application/pdf']])
  assert.deepEqual(calls, [
    ['/api/assets/upload-url', 'POST'],
    ['/api/assets/44/confirm', 'POST'],
    ['/api/brands/9/knowledge-documents', 'POST'],
  ])
  await assert.rejects(api.uploadDocument({ storeId: 1 }, { name: '旧版.doc', type: 'application/msword', size: 10 }), /PDF/)
  await assert.rejects(api.uploadDocument({ storeId: 1 }, { name: '大文件.pdf', type: 'application/pdf', size: 11 * 1024 * 1024 }), /10MB/)
  const failing = createKnowledgeApi(send, async () => ({ ok: false }))
  await assert.rejects(failing.uploadDocument({ storeId: 1 }, file), /上传失败/)
})
