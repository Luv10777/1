import { get, post } from '../utils/request'
export const getVideoQuote = data => post('/api/video/quote', data)
export const submitVideoQuote = (data, quoteId) => post('/api/video/workflows', { ...data, quoteId })

export const videoApi = {
  create: data => post('/api/video/workflows', data),
  get: id => get(`/api/video/workflows/${id}`),
  works: (page = 0) => get('/api/video/works', { page, size: 20 }),
}

export async function uploadVideoReference(entry, type) {
  if (entry.assetId) return entry.assetId
  let file = entry.file
  if (!file && type === 'IMAGE' && entry.url) {
    const source = await fetch(entry.url)
    if (!source.ok) throw new Error('参考图片读取失败，请重新上传。')
    const blob = await source.blob()
    file = new File([blob], entry.name || '参考图.png', { type: blob.type })
  }
  if (!file) throw new Error('参考素材已不可用，请移除后重新上传。')
  const ticket = await post('/api/assets/upload-url', { name: file.name, type, mimeType: file.type })
  // Upload directly to signed object storage without forwarding the session token.
  const response = await fetch(ticket.uploadUrl, { method: 'PUT', headers: { 'Content-Type': file.type }, body: file })
  if (!response.ok) throw new Error('参考素材上传失败')
  const confirmed = await post(`/api/assets/${ticket.assetId}/confirm`, { sizeBytes: file.size })
  entry.assetId = confirmed.id
  return entry.assetId
}
