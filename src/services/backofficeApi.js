import { get, post, put } from '../utils/request.js'
const params = values => Object.fromEntries(Object.entries(values || {}).filter(([, value]) => value != null && value !== ''))
export const backofficeApi = {
  orders: values => get('/api/billing/operations/orders', params(values)),
  confirmOrder: (tenantId, id, values) => post(`/api/billing/operations/tenants/${encodeURIComponent(tenantId)}/orders/${encodeURIComponent(id)}/confirm`, values),
  roles: () => get('/api/platform/security/roles'),
  assignRoles: values => put('/api/platform/security/roles', values),
  operations: values => get('/api/platform/security/operations', params(values)),
}
