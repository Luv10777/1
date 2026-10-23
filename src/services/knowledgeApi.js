import { request } from '../utils/request.js'

// Return backend DTOs unchanged so live configuration and the library share
// status and IDs. Lists must include every page, not just the first 20.
export const DOCUMENT_TYPES = '.pdf,.docx,.txt,.md'
const MAX_DOCUMENT_BYTES = 10 * 1024 * 1024

// The presigned upload goes straight to object storage, not to our backend, so it is a plain fetch.
export function createKnowledgeApi(send = request, uploadFetch = (...args) => globalThis.fetch(...args)) {
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
    listBrandSets: (brandId) => listAll(`/api/brands/${brandId}/knowledge-sets`),
    listEntries: (setId) => listAll(`/api/knowledge-sets/${setId}/entries`),
    // What a live session started now would know: switched-on entries of the store's active sets.
    context: (storeId) => send(`/api/stores/${storeId}/knowledge-context`),
    createSet: (storeId, body) => write(`/api/stores/${storeId}/knowledge-sets`, 'POST', body),
    createBrandSet: (brandId, body) => write(`/api/brands/${brandId}/knowledge-sets`, 'POST', body),
    updateSet: (setId, body) => write(`/api/knowledge-sets/${setId}`, 'PATCH', body),
    createEntry: (setId, body) => write(`/api/knowledge-sets/${setId}/entries`, 'POST', body),
    updateEntry: (entryId, body) => write(`/api/knowledge-entries/${entryId}`, 'PATCH', body),
    deleteEntry: (entryId) => write(`/api/knowledge-entries/${entryId}`, 'DELETE'),
    archive: (setId) => write(`/api/knowledge-sets/${setId}/archive`, 'POST', {}),
    restore: (setId) => write(`/api/knowledge-sets/${setId}/restore`, 'POST', {}),

    // 文档：上传后由文本模型整理成待确认的建议，owner 是 { storeId } 或 { brandId }。
    listDocuments: ({ storeId, brandId }) => send(storeId ? `/api/stores/${storeId}/knowledge-documents` : `/api/brands/${brandId}/knowledge-documents`),
    getDocument: (id) => send(`/api/knowledge-documents/${id}`),
    retryDocument: (id) => write(`/api/knowledge-documents/${id}/retry`, 'POST', {}),
    deleteDocument: (id) => write(`/api/knowledge-documents/${id}`, 'DELETE'),
    acceptSuggestion: (id, body = {}) => write(`/api/knowledge-suggestions/${id}/accept`, 'POST', body),
    dismissSuggestion: (id) => write(`/api/knowledge-suggestions/${id}/dismiss`, 'POST', {}),
    async uploadDocument({ storeId, brandId }, file) {
      const name = file.name.toLowerCase()
      if (!DOCUMENT_TYPES.split(',').some(type => name.endsWith(type))) throw new Error('只支持 PDF、Word（.docx）、TXT 和 Markdown 文件。')
      if (file.size > MAX_DOCUMENT_BYTES) throw new Error('文档不能超过 10MB。')
      const mimeType = file.type || 'application/octet-stream'
      const ticket = await write('/api/assets/upload-url', 'POST', { name: file.name, type: 'DOCUMENT', mimeType })
      let response
      try {
        response = await uploadFetch(ticket.uploadUrl, { method: 'PUT', headers: { 'Content-Type': mimeType }, body: file })
      } catch {
        throw new Error('文档上传失败，请检查网络后重试。')
      }
      if (!response.ok) throw new Error('文档上传失败，请重新选择文件。')
      await write(`/api/assets/${ticket.assetId}/confirm`, 'POST', { sizeBytes: file.size })
      const owner = storeId ? `/api/stores/${storeId}` : `/api/brands/${brandId}`
      return write(`${owner}/knowledge-documents`, 'POST', { assetId: ticket.assetId })
    },
  }
}

export const knowledgeApi = createKnowledgeApi()
export const {
  listSets, listEntries, context, createSet, updateSet, createEntry,
  updateEntry, deleteEntry, archive, restore,
} = knowledgeApi
