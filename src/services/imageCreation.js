import { get, post, put, request } from '../utils/request'
import { validateImageFile } from '../domain/imageCreation'
const root = '/api/image-creations'
export const imageApi = {
  capabilities: () => request(root + '/capabilities', { method: 'GET', signal: AbortSignal.timeout(10000) }),
  create: data => post(root, data),
  get: id => get(root + '/' + id),
  cancel: id => post(root + '/' + id + '/cancel', {}),
  thread: id => get(root + '/' + id + '/thread'),
  rename: (id, title) => put(root + '/' + id + '/title', { title }),
  history: (workflow, page = 0) => get(root, { workflow, page, size: 20 }),
  works: (page = 0) => get(root + '/works', { page, size: 20 }),
  revision: (id, data) => post(root + '/' + id + '/revisions', data),
  answer: (id, data) => post(root + '/' + id + '/answers', data),
  retry: (id, item) => post(root + '/' + id + (item ? '/items/' + item.id : '') + '/retry', { taskId: item?.taskId }),
  retryPlan: (id, taskId) => post(root + '/' + id + '/retry', { taskId }),
  text: (id, itemId, data) => post(root + '/' + id + '/items/' + itemId + '/text', data),
  regenerate: (id, itemId, data) => post(root + '/' + id + '/items/' + itemId + '/regenerate', data),
}
export const assetApi = {
  images: (page = 0) => get('/api/assets', { page, size: 100 }),
}
export async function uploadImageReference(file) {
  validateImageFile(file)
  const ticket = await post('/api/assets/upload-url', { name: file.name, type: 'IMAGE', mimeType: file.type })
  // Signed object-store upload: never forward the application's bearer token.
  const response = await fetch(ticket.uploadUrl, { method: 'PUT', headers: { 'Content-Type': file.type }, body: file })
  if (!response.ok) throw new Error('图片上传失败，请重试')
  await post('/api/assets/' + ticket.assetId + '/confirm', { sizeBytes: file.size })
  return ticket.assetId
}
export async function downloadImage(url, filename) {
  const response = await fetch(url)
  if (!response.ok) throw new Error('下载地址可能已过期，请刷新记录后重试')
  const objectUrl = URL.createObjectURL(await response.blob())
  const a = document.createElement('a')
  a.href = objectUrl; a.download = filename
  document.body.appendChild(a)
  a.click(); a.remove()
  setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
}
