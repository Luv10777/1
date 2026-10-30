import { get, post, put } from '../utils/request'

// 空的筛选条件不放进查询串，避免变成字面的 undefined。
const query = values => Object.fromEntries(Object.entries(values).filter(([, value]) => value !== undefined && value !== null && value !== ''))

export const referralApi = {
  me: () => get('/api/referral/me'),
  check: code => get(`/api/public/referral-codes/${encodeURIComponent(code)}`),
  takeCode: () => post('/api/referral/code', { agreementAccepted: true }),
  rate: () => get('/api/platform/referral/rate'),
  updateRate: values => put('/api/platform/referral/rate', values),
  relations: params => get('/api/platform/referral/relations', query(params)),
  commissions: params => get('/api/platform/referral/commissions', query(params)),
  promoters: () => get('/api/platform/referral/promoters'),
}
