import { get, post } from '../utils/request.js'

const params = values => Object.fromEntries(Object.entries(values || {}).filter(([, value]) => value != null && value !== ''))
const target = (item, resource) => `/api/platform/tenants/${encodeURIComponent(item.tenantId)}/${resource}/${encodeURIComponent(item.id)}`

export const platformManagementApi = {
  access: () => get('/api/platform/access'),
  summary: () => get('/api/platform/summary'),
  information: values => get('/api/platform/information', params(values)),
  merchants: values => get('/api/platform/merchants', params(values)),
  identities: values => get('/api/platform/identities', params(values)),
  informationStatus: (item, values) => post(`${target(item, 'information')}/status`, values),
  merchantReview: (item, values) => post(`${target(item, 'merchants')}/review`, values),
  identityReview: (item, values) => post(`${target(item, 'identities')}/review`, values),
  revealIdentity: (item, note) => post(`${target(item, 'identities')}/reveal`, { note }),
  revealMerchant: (item, note) => post(`${target(item, 'merchants')}/reveal`, { note }),
  audit: (item, type) => get(`/api/platform/tenants/${encodeURIComponent(item.tenantId)}/audit/${type}/${encodeURIComponent(item.id)}`),
  myIdentity: () => get('/api/identity/me'),
  submitIdentity: async values => {
    const result = await post('/api/identity/applications', values)
    if (!result?.id || result.status !== 'PENDING_REVIEW') throw new Error('未收到有效申请回执，请稍后刷新确认提交结果。')
    return result
  },
}
