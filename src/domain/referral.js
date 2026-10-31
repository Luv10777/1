// 邀请返利的展示规则。金额都是分，比例都是万分点（1000 = 10%）。
export const commissionStates = { FROZEN: '冻结中', AVAILABLE: '可提现', WITHDRAWING: '提现审核中', WITHDRAWN: '已提现', VOIDED: '已作废' }
export const commissionKinds = { TOPUP: '充值烟火', MEMBERSHIP: '开通会员' }
export const withdrawalStatuses = { PENDING: '待审核', REJECTED: '已驳回', PAID: '已打款' }

export const inviteLink = (code, origin) => `${origin}/login?mode=register&invite=${encodeURIComponent(code)}`

/** 这次能提多少：可提现返利减去已打款后又作废、要扣回的欠款。 */
export const withdrawable = overview => Math.max(0, overview.availableMinor - overview.owedMinor)

/** 输入时每 4 位加一个空格，最多 19 位数字。 */
export const formatCardNumber = value => String(value ?? '').replace(/\D/g, '').slice(0, 19).replace(/(\d{4})(?=\d)/g, '$1 ')

export function ratePercent(bp) {
  const value = Number(bp) / 100
  return `${Number.isInteger(value) ? value : value.toFixed(2).replace(/0+$/, '')}%`
}

/** 后台输入的百分比转成万分点；不合法时返回错误提示。 */
export function parseRatePercent(input) {
  const text = String(input ?? '').trim()
  if (!/^\d{1,2}(\.\d{1,2})?$/.test(text)) return { error: '请输入 0–50 之间的数字，最多两位小数。' }
  const bp = Math.round(Number(text) * 100)
  if (bp > 5000) return { error: '返利比例不能超过 50%。' }
  return { bp }
}

/** 财务填写的元金额转成分，不经过浮点；不合法时返回错误提示。 */
export function parseYuan(input) {
  const text = String(input ?? '').trim()
  if (!/^\d{1,9}(\.\d{1,2})?$/.test(text)) return { error: '请输入金额，例如 12 或 12.50，最多两位小数。' }
  const [whole, fraction = ''] = text.split('.')
  return { minor: Number(whole) * 100 + Number(fraction.padEnd(2, '0')) }
}
