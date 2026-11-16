export const merchantStages = { ACTIVE: '正常合作', AT_RISK: '需重点维护', DORMANT: '暂停合作' }
export const followChannels = { PHONE: '电话', WECHAT: '微信', VISIT: '到店拜访', OTHER: '其他' }
export const adminRoles = { ADMIN: '系统管理员', REVIEWER: '审核专员', FINANCE: '财务专员', SUPPORT: '客服专员' }
export const orderStates = { PAID: '已支付 · 已入账', PENDING_PAYMENT: '待支付', EXPIRED: '已过期' }
export const orderKinds = { TOPUP: '烟火充值', MEMBERSHIP: '会员订购', ACTIVATION_CODE: '激活码购买' }
export const paymentMethods = { MANUAL: '线下收款', WECHAT: '微信支付', ALIPAY: '支付宝' }
export const money = value => `¥${(Number(value || 0) / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
/** Amounts are stored in integer cents, never rounded from a floating-point currency input. */
export function moneyMinor(value) {
  const text = String(value ?? '').trim()
  if (!/^\d{1,7}(\.\d{1,2})?$/.test(text)) return null
  const [whole, fraction = ''] = text.split('.')
  const minor = Number(whole) * 100 + Number(fraction.padEnd(2, '0'))
  return minor >= 1 && minor <= 100000000 ? minor : null
}
export function localDateTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return ''
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
}
export const instantFromLocal = value => value ? new Date(value).toISOString() : null
