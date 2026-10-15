export const informationCategories = { activity: '门店活动', cooperation: '商业合作', supply: '供求信息', notice: '商家公告' }
export const managementStatuses = {
  PUBLISHED: '已发布', OFFLINE: '已下线', EXPIRED: '已过期', PENDING_REVIEW: '待审核',
  APPROVED: '已入驻', REJECTED: '已驳回', SUSPENDED: '已暂停', VERIFIED: '人工审核通过',
}
export const auditActions = { ...managementStatuses, REVEAL: '查阅完整资料', SUBMITTED: '本人提交实名申请' }

export function managementDate(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }).format(date)
}

export function identityValidation(form) {
  if (!/^[\p{Script=Han}·]{2,40}$/u.test(String(form.legalName || '').trim())) return '请输入2至40字的真实姓名。'
  const value = String(form.idNumber || '').trim().toUpperCase()
  if (!/^[1-9]\d{16}[0-9X]$/.test(value)) return '请输入18位居民身份证号码。'
  const y = Number(value.slice(6, 10)), m = Number(value.slice(10, 12)), d = Number(value.slice(12, 14))
  const birth = new Date(y, m - 1, d)
  if (y < 1800 || birth.getFullYear() !== y || birth.getMonth() !== m - 1 || birth.getDate() !== d || birth > new Date()) return '身份证出生日期无效。'
  const weights = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
  if ('10X98765432'[[...value.slice(0, 17)].reduce((sum, digit, i) => sum + Number(digit) * weights[i], 0) % 11] !== value[17]) return '身份证号码校验未通过，请检查输入。'
  if (!form.declarationAccepted) return '请确认身份信息真实，并同意用于平台实名审核。'
  return ''
}
