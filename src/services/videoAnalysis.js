import { get, post, put } from '../utils/request'
import { generationWalletPath } from '../domain/billing.js'
import { analysisMimeType, validateAnalysisFile } from '../domain/videoAnalysis'

export const videoAnalysisApi = {
  limits: () => get('/api/video/analyses/limits'),
  list: () => get('/api/video/analyses', { size: 20 }),
  get: id => get(`/api/video/analyses/${id}`),
  create: (data, wallet) => post(generationWalletPath('/api/video/analyses', wallet), data),
  importUrl: (data, wallet) => post(generationWalletPath('/api/video/analyses/import-url', wallet), data),
  cancel: id => post(`/api/video/analyses/${id}/cancel`, {}),
  rename: (id, name) => put(`/api/video/analyses/${id}/name`, { name }),
}

export async function uploadAnalysisVideo(file, limits) {
  const error = validateAnalysisFile(file, limits)
  if (error) throw new Error(error)
  const mimeType = analysisMimeType(file)
  const ticket = await post('/api/video/analyses/upload-url', { name: file.name, mimeType, sizeBytes: file.size })
  // The session token stays with the API; signed storage uploads carry no Authorization header.
  const response = await fetch(ticket.uploadUrl, { method: 'PUT', headers: { 'Content-Type': mimeType }, body: file })
  if (!response.ok) throw new Error('视频上传失败，请检查网络后重试。')
  return ticket.assetId
}
