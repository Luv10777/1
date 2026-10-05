import { get, post } from '../utils/request'

export const videoApi = {
  create: data => post('/api/video/workflows', data),
  get: id => get(`/api/video/workflows/${id}`),
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
