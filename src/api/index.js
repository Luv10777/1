import { get, post, put, del } from '../utils/request'

/**
 * 认证API
 */
export const authApi = {
  sendVerificationCode: (phone) => post('/api/auth/send-code', { phone }),
  login: (phone, code) => post('/api/auth/login', { phone, code }),
  refreshToken: (refreshToken) => post('/api/auth/refresh', { refreshToken }),
  logout: () => post('/api/auth/logout'),
  getCurrentUser: () => get('/api/auth/me')
}

/**
 * 商家API
 */
export const merchantApi = {
  list: (params) => get('/api/merchants', params),
  get: (id) => get(`/api/merchants/${id}`),
  create: (data) => post('/api/merchants', data),
  update: (id, data) => put(`/api/merchants/${id}`, data),
  delete: (id) => del(`/api/merchants/${id}`),
  toggleStatus: (id, enabled) => put(`/api/merchants/${id}/status`, { enabled })
}

/**
 * 门店API
 */
export const storeApi = {
  list: (merchantId, params) => get(`/api/merchants/${merchantId}/stores`, params),
  get: (id) => get(`/api/stores/${id}`),
  create: (merchantId, data) => post(`/api/merchants/${merchantId}/stores`, data),
  update: (id, data) => put(`/api/stores/${id}`, data),
  delete: (id) => del(`/api/stores/${id}`),
  toggleStatus: (id, enabled) => put(`/api/stores/${id}/status`, { enabled })
}

/**
 * 品牌API
 */
export const brandApi = {
  list: (merchantId, params) => get(`/api/merchants/${merchantId}/brands`, params),
  get: (id) => get(`/api/brands/${id}`),
  create: (merchantId, data) => post(`/api/merchants/${merchantId}/brands`, data),
  update: (id, data) => put(`/api/brands/${id}`, data),
  delete: (id) => del(`/api/brands/${id}`)
}

/**
 * 素材API
 */
export const assetApi = {
  list: (params) => get('/api/assets', params),
  get: (id) => get(`/api/assets/${id}`),
  requestUploadUrl: (data) => post('/api/assets/upload-url', data),
  confirmUpload: (id, data) => post(`/api/assets/${id}/confirm`, data),
  delete: (id) => del(`/api/assets/${id}`)
}

/**
 * 知识库API
 */
export const knowledgeApi = {
  list: (params) => get('/api/knowledge', params),
  get: (id) => get(`/api/knowledge/${id}`),
  create: (data) => post('/api/knowledge', data),
  update: (id, data) => put(`/api/knowledge/${id}`, data),
  delete: (id) => del(`/api/knowledge/${id}`),
  reprocess: (id) => post(`/api/knowledge/${id}/reprocess`)
}

/**
 * 作品库API
 */
export const workApi = {
  list: (params) => get('/api/works', params),
  get: (id) => get(`/api/works/${id}`),
  update: (id, data) => put(`/api/works/${id}`, data),
  delete: (id) => del(`/api/works/${id}`),
  approve: (id, data) => post(`/api/works/${id}/approve`, data),
  reject: (id, data) => post(`/api/works/${id}/reject`, data)
}

/**
 * 任务API
 */
export const taskApi = {
  list: (params) => get('/api/tasks', params),
  get: (id) => get(`/api/tasks/${id}`),
  cancel: (id) => post(`/api/tasks/${id}/cancel`),
  retry: (id) => post(`/api/tasks/${id}/retry`)
}

/**
 * AI工作流API
 */
export const workflowApi = {
  createCampaign: (data) => post('/api/workflows/campaign', data),
  estimateCost: (data) => post('/api/workflows/campaign/estimate', data),
  generateImage: (data) => post('/api/workflows/image', data),
  generateVideo: (data) => post('/api/workflows/video', data)
}
