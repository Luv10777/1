// 邀请返利的展示规则。金额都是分，比例都是万分点（1000 = 10%）。
export const commissionStates = { FROZEN: '冻结中', AVAILABLE: '可提现', VOIDED: '已作废' }

export const inviteLink = (code, origin) => `${origin}/login?mode=register&invite=${encodeURIComponent(code)}`

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
