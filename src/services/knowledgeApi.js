import { request } from '../utils/request.js'

// Return backend DTOs unchanged so live configuration and the library share
// status, scope and IDs. Lists must include every page, not just the first 20.
export function createKnowledgeApi(send = request) {
  async function listAll(endpoint) {
    const items = []
    let page = 0
    let totalPages = 1
    do {
      const result = await send(`${endpoint}?${new URLSearchParams({ page, size: 100 })}`)
      items.push(...result.items)
      totalPages = result.totalPages
      page += 1
    } while (page < totalPages)
    return items
  }

  const write = (endpoint, method, body) => send(endpoint, {
    method,
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  })

  return {
    listSets: (storeId) => listAll(`/api/stores/${storeId}/knowledge-sets`),
    listEntries: (setId) => listAll(`/api/knowledge-sets/${setId}/entries`),
    context(storeId, productIds = []) {
      const params = new URLSearchParams()
      for (const id of new Set(productIds)) params.append('productId', id)
      const query = params.toString()
      return send(`/api/stores/${storeId}/knowledge-context${query ? `?${query}` : ''}`)
    },
    createSet: (storeId, body) => write(`/api/stores/${storeId}/knowledge-sets`, 'POST', body),
    updateSet: (setId, body) => write(`/api/knowledge-sets/${setId}`, 'PATCH', body),
    createEntry: (setId, body) => write(`/api/knowledge-sets/${setId}/entries`, 'POST', body),
    updateEntry: (entryId, body) => write(`/api/knowledge-entries/${entryId}`, 'PATCH', body),
    deleteEntry: (entryId) => write(`/api/knowledge-entries/${entryId}`, 'DELETE'),
    publish: (setId) => write(`/api/knowledge-sets/${setId}/publish`, 'POST', {}),
    disable: (setId) => write(`/api/knowledge-sets/${setId}/disable`, 'POST', {}),
    archive: (setId) => write(`/api/knowledge-sets/${setId}/archive`, 'POST', {}),
  }
}

export const knowledgeApi = createKnowledgeApi()
export const {
  listSets, listEntries, context, createSet, updateSet, createEntry,
  updateEntry, deleteEntry, publish, disable, archive,
} = knowledgeApi
