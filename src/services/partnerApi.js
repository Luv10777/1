import { del, get, post, put } from '../utils/request.js'

const params = values => Object.fromEntries(Object.entries(values || {}).filter(([, value]) => value != null && value !== ''))

/** 服务商后台（wy.yifangzhi.com/partner/）：只有服务商的成员能用，接口只返回自己这一家的数据，各项操作按成员权限。 */
export const partnerApi = {
  crm: (tenantId, values) => get(`/api/partner/tenants/${encodeURIComponent(tenantId)}/crm`, params(values)),
  saveProfile: (tenantId, values) => put(`/api/partner/tenants/${encodeURIComponent(tenantId)}/profile`, values),
  followUp: (tenantId, values) => post(`/api/partner/tenants/${encodeURIComponent(tenantId)}/follow-ups`, values),
  dueFollowUps: () => get('/api/partner/follow-ups/due'),
  me: () => get('/api/partner/me'),
  /** 一方志站内判断要不要显示「服务商后台」入口；不是成员也正常返回。 */
  membership: () => get('/api/partner/membership'),
  setDisplayName: displayName => put('/api/partner/me/name', { displayName }),
  workbench: () => get('/api/partner/workbench'),
  dashboard: values => get('/api/partner/dashboard', params(values)),
  tenants: values => get('/api/partner/tenants', params(values)),
  tenant: tenantId => get(`/api/partner/tenants/${encodeURIComponent(tenantId)}`),
  lookup: uid => get('/api/partner/lookup', { uid: Number(uid) }),
  addTenant: uid => post('/api/partner/tenants', { uid: Number(uid) }),
  transfer: (tenantId, values) => post(`/api/partner/tenants/${encodeURIComponent(tenantId)}/credits`, values),
  /** 商户备注名，整个团队共用；空字符串表示删掉。 */
  setRemark: (tenantId, remark) => put(`/api/partner/tenants/${encodeURIComponent(tenantId)}/remark`, { remark }),
  renew: (tenantId, values) => post(`/api/partner/tenants/${encodeURIComponent(tenantId)}/renewals`, values),
  team: () => get('/api/partner/team'),
  /** { phone, name, permissions, monthlyCap }：按手机号把人加进团队账户。 */
  addMember: values => post('/api/partner/team', values),
  /** { permissions, monthlyCap, displayName }。 */
  updateMember: (userId, values) => put(`/api/partner/team/${encodeURIComponent(userId)}`, values),
  removeMember: userId => del(`/api/partner/team/${encodeURIComponent(userId)}`),
  /** 有财务权限的可按 tenantId、actorUserId 筛；只有分配权限的只返回自己分出去的。 */
  distributions: values => get('/api/partner/distributions', params(values)),
  retryDistribution: id => post(`/api/partner/distributions/${encodeURIComponent(id)}/retry`, {}),
  taskLogs: values => get('/api/partner/task-logs', params(values)),
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
  /** { uid } 或 { phone, name }，外加 { permissions, monthlyCap }：把人加成服务商成员。 */
  addTeamMember: (id, values) => post(`/api/platform/partners/${encodeURIComponent(id)}/team`, values),
  removeMember: (id, userId) => del(`/api/platform/partners/${encodeURIComponent(id)}/members/${encodeURIComponent(userId)}`),
  /** 软删除：作废没兑换的激活码、移出全部账号、名下商户解除归属；记录保留。 */
  remove: id => del(`/api/platform/partners/${encodeURIComponent(id)}`),
  rename: (id, values) => put(`/api/platform/partners/${encodeURIComponent(id)}`, values),
  setStatus: (id, status) => post(`/api/platform/partners/${encodeURIComponent(id)}/status`, { status }),
  grant: (id, values) => post(`/api/platform/partners/${encodeURIComponent(id)}/credits`, values),
  tenants: (id, values) => get(`/api/platform/partners/${encodeURIComponent(id)}/tenants`, params(values)),
  ledger: (id, values) => get(`/api/platform/partners/${encodeURIComponent(id)}/ledger`, params(values)),
  lookup: uid => get('/api/platform/partners/lookup', { uid }),
  reassign: (tenantId, partnerId) => put(`/api/platform/partners/assignments/${encodeURIComponent(tenantId)}`, { partnerId: partnerId || null }),
}

export const tenantSources = { UID: '手动添加', CODE: '兑换激活码', PLATFORM: '平台指定' }
/** TRANSFER 是 V84 以前从「可分配烟火」分给商户的，以及升级时把剩余的可分配烟火转进团队账户的那一笔。 */
export const ledgerTypes = { GRANT: '平台发放', TRANSFER: '旧可分配烟火转出' }

/** UID 是 8 位数字。 */
export const validUid = value => /^[1-9]\d{7}$/.test(String(value || '').trim())
