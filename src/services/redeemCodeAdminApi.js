import { get, post } from '../utils/request.js'

const params = values => Object.fromEntries(Object.entries(values || {}).filter(([, value]) => value != null && value !== ''))

/** 兑换码和激活码（需要计费运营权限）：批量生成、查看、作废。一码一用。 */
export const redeemCodeAdminApi = {
  list: values => get('/api/billing/operations/redeem-codes', params(values)),
  create: values => post('/api/billing/operations/redeem-codes', values),
  void: id => post(`/api/billing/operations/redeem-codes/${encodeURIComponent(id)}/void`, {}),
}

export const redeemCodeKinds = { MEMBERSHIP: '激活码', TOPUP: '充值', GIFT: '赠送' }
export const redeemCodeStatuses = { UNUSED: '未使用', REDEEMED: '已兑换', VOIDED: '已作废', EXPIRED: '已过期' }

/** 16 位码每 4 位加一道横线，方便抄写；兑换时横线和大小写都不影响。 */
export function formatRedeemCode(code) {
  return String(code || '').replace(/(.{4})(?=.)/g, '$1-')
}

/** 本地日期（yyyy-mm-dd）当天结束时失效；不填表示长期有效。 */
export function redeemCodeExpiry(date) {
  if (!date) return null
  const [year, month, day] = date.split('-').map(Number)
  return new Date(year, month - 1, day, 23, 59, 59).toISOString()
}
