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
  revealIdentity: (item, note) => post(`${target(item, 'identities')}/reveal`, { note }),
  revealMerchant: (item, note) => post(`${target(item, 'merchants')}/reveal`, { note }),
  users: values => get('/api/platform/users', params(values)),
  userSummary: () => get('/api/platform/users/summary'),
  userSuspension: (item, values) => post(`/api/platform/users/${encodeURIComponent(item.id)}/suspension`, values),
  // 会员和烟火按商户记账：对某个账号操作，作用于他所属的整个商户。
  userMembership: (item, values) => post(`/api/platform/users/${encodeURIComponent(item.id)}/membership`, values),
  userCredits: (item, values) => post(`/api/platform/users/${encodeURIComponent(item.id)}/credits`, values),
  audit: (item, type) => get(`/api/platform/tenants/${encodeURIComponent(item.tenantId)}/audit/${type}/${encodeURIComponent(item.id)}`),
  myIdentity: () => get('/api/identity/me'),
  submitIdentity: async values => {
    const result = await post('/api/identity/applications', values)
    // 服务端当场核验，只会返回通过或不一致。
    if (!result?.id || !['VERIFIED', 'REJECTED'].includes(result.status)) throw new Error('未收到有效申请回执，请稍后刷新确认提交结果。')
    return result
  },
}
