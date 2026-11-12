import { del, get, post, put } from '../utils/request.js'

const params = values => Object.fromEntries(Object.entries(values || {}).filter(([, value]) => value != null && value !== ''))

/** 服务商后台（partner.yifangzhi.com）：只有服务商的登录账号能用，接口只返回自己这一家的数据。 */
export const partnerApi = {
  me: () => get('/api/partner/me'),
  tenants: values => get('/api/partner/tenants', params(values)),
  addTenant: uid => post('/api/partner/tenants', { uid: Number(uid) }),
  transfer: (tenantId, values) => post(`/api/partner/tenants/${encodeURIComponent(tenantId)}/credits`, values),
  renew: (tenantId, values) => post(`/api/partner/tenants/${encodeURIComponent(tenantId)}/renewals`, values),
  assignBd: (tenantId, bdUserId) => put(`/api/partner/tenants/${encodeURIComponent(tenantId)}/bd`, { bdUserId: bdUserId || null }),
  team: () => get('/api/partner/team'),
  addBd: uid => post('/api/partner/team', { uid: Number(uid) }),
  removeBd: userId => del(`/api/partner/team/${encodeURIComponent(userId)}`),
  codes: values => get('/api/partner/codes', params(values)),
  createCodes: values => post('/api/partner/codes', values),
  voidCode: id => post(`/api/partner/codes/${encodeURIComponent(id)}/void`, {}),
  ledger: values => get('/api/partner/ledger', params(values)),
}

/** 平台管理服务商（需要计费运营权限）。 */
export const partnerAdminApi = {
  list: () => get('/api/platform/partners'),
  create: values => post('/api/platform/partners', values),
  addMember: (id, uid) => post(`/api/platform/partners/${encodeURIComponent(id)}/members`, { uid: Number(uid) }),
  removeMember: (id, userId) => del(`/api/platform/partners/${encodeURIComponent(id)}/members/${encodeURIComponent(userId)}`),
  setStatus: (id, status) => post(`/api/platform/partners/${encodeURIComponent(id)}/status`, { status }),
  grant: (id, values) => post(`/api/platform/partners/${encodeURIComponent(id)}/credits`, values),
  setQuota: (id, planCode, quota) => put(`/api/platform/partners/${encodeURIComponent(id)}/quotas`, { planCode, quota }),
  tenants: (id, values) => get(`/api/platform/partners/${encodeURIComponent(id)}/tenants`, params(values)),
  ledger: (id, values) => get(`/api/platform/partners/${encodeURIComponent(id)}/ledger`, params(values)),
  lookup: uid => get('/api/platform/partners/lookup', { uid }),
  reassign: (tenantId, partnerId) => put(`/api/platform/partners/assignments/${encodeURIComponent(tenantId)}`, { partnerId: partnerId || null }),
}

export const tenantSources = { UID: '按 UID 添加', CODE: '兑换激活码', PLATFORM: '平台指定' }
export const ledgerTypes = { GRANT: '平台发放', TRANSFER: '分给商户' }

/** UID 是 8 位数字。 */
export const validUid = value => /^[1-9]\d{7}$/.test(String(value || '').trim())
