import test from 'node:test'
import assert from 'node:assert/strict'
import { createKnowledgeApi } from './knowledgeApi.js'

test('knowledge lists retrieve every page and preserve backend scope/status', async () => {
  const calls = []
  const api = createKnowledgeApi(async endpoint => {
    calls.push(endpoint)
    const url = new URL(endpoint, 'http://localhost')
    const page = Number(url.searchParams.get('page'))
    assert.equal(url.searchParams.get('size'), '100')
    return { items: [{ id: page + 1, status: 'PUBLISHED', scope: 'STORE' }], totalPages: 3 }
  })
  assert.deepEqual((await api.listSets(12)).map(item => item.id), [1, 2, 3])
  assert.equal(calls.length, 3)
  assert.equal(calls[2], '/api/stores/12/knowledge-sets?page=2&size=100')
  calls.length = 0
  const entries = await api.listEntries(80)
  assert.equal(calls[0], '/api/knowledge-sets/80/entries?page=0&size=100')
  assert.equal(entries[0].scope, 'STORE')
  assert.equal(entries[0].status, 'PUBLISHED')
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

test('knowledge context uses repeated productId parameters with no empty query', async () => {
  const calls = []
  const api = createKnowledgeApi(async endpoint => { calls.push(endpoint); return { entries: [] } })
  await api.context(12)
  await api.context(12, [11, 25, 11])
  assert.deepEqual(calls, [
    '/api/stores/12/knowledge-context',
    '/api/stores/12/knowledge-context?productId=11&productId=25',
  ])
})

test('knowledge writes use the API contract and never add a tenant or demo identity', async () => {
  const calls = []
  const api = createKnowledgeApi(async (endpoint, options) => { calls.push({ endpoint, ...options }); return { id: 7 } })
  const newSet = { name: '门店服务', kind: 'FAQ', description: '日常问答' }
  const newEntry = { question: '几点营业？', answer: '10:00', scope: 'STORE', source: 'MANUAL', status: 'DRAFT' }
  await api.createSet(12, newSet)
  await api.updateSet(7, { name: '门店营业' })
  await api.createEntry(7, newEntry)
  await api.updateEntry(8, { answer: '11:00' })
  await api.deleteEntry(8)
  await api.publish(7)
  await api.disable(7)
  await api.archive(7)
  assert.deepEqual(calls.map(({ endpoint, method }) => [endpoint, method]), [
    ['/api/stores/12/knowledge-sets', 'POST'],
    ['/api/knowledge-sets/7', 'PATCH'],
    ['/api/knowledge-sets/7/entries', 'POST'],
    ['/api/knowledge-entries/8', 'PATCH'],
    ['/api/knowledge-entries/8', 'DELETE'],
    ['/api/knowledge-sets/7/publish', 'POST'],
    ['/api/knowledge-sets/7/disable', 'POST'],
    ['/api/knowledge-sets/7/archive', 'POST'],
  ])
  assert.deepEqual(JSON.parse(calls[0].body), newSet)
  assert.deepEqual(JSON.parse(calls[2].body), newEntry)
  assert.equal(calls[4].body, undefined)
})
