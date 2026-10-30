export const informationCategories = { activity: '门店活动', cooperation: '商业合作', supply: '供求信息', notice: '商家公告' }
export const managementStatuses = {
  PUBLISHED: '已发布', OFFLINE: '已下线', EXPIRED: '已过期', PENDING_REVIEW: '待审核',
  APPROVED: '已入驻', REJECTED: '已驳回', SUSPENDED: '已暂停', VERIFIED: '实名已通过',
}
export const userStatuses = { ACTIVE: '正常', DISABLED: '商户已停用', SUSPENDED: '平台停用', REMOVED: '已移除' }
export const userRoles = { OWNER: '商户管理员', STAFF: '店员' }
export const auditActions = { ...managementStatuses, VERIFIED: '人工审核通过', AUTO_VERIFIED: '自动核验一致', AUTO_REJECTED: '自动核验不一致', REVEAL: '查阅完整资料', SUBMITTED: '本人提交实名申请', SUSPEND_USER: '平台停用账号', RESTORE_USER: '解除平台停用', GRANT_MEMBERSHIP: '平台开通会员', REVOKE_MEMBERSHIP: '平台关闭会员', GRANT_CREDITS: '平台赠送烟火' }

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

// 实名提醒弹窗：直播、付款和设置页不打扰；点「稍后再说」后 24 小时内不再弹。
export const IDENTITY_PROMPT_SNOOZE_MS = 24 * 60 * 60 * 1000
const IDENTITY_PROMPT_QUIET_ROUTES = new Set(['digital-human', 'billing', 'settings'])
export function shouldPromptIdentity({ missing, routeName, dismissedAt, now = Date.now() }) {
  if (!missing || IDENTITY_PROMPT_QUIET_ROUTES.has(routeName)) return false
  return !(Number(dismissedAt) > 0 && now - Number(dismissedAt) < IDENTITY_PROMPT_SNOOZE_MS)
}
