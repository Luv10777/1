/**
 * 服务商后台的纯逻辑：到期筛选、会员状态文字、续期预览、批量分烟火、商户记录、激活码导出。不发请求，方便单测。
 */

export const partnerRoles = { OWNER: '负责人', MEMBER: '成员' }
export const expiryFilters = { SOON7: '7 天内到期', SOON30: '30 天内到期', EXPIRED: '已过期', NEVER: '从未开通' }
export const transferPresets = [500, 1000, 2000, 5000]

/**
 * 成员的几项权限。ownerOnly 的只有负责人能给（钱的口子由负责人把住）；其余有「团队管理」权限的成员也能给，但只能给出自己也有的。
 */
export const permissionItems = [
  { key: 'tenants', label: '商户', hint: '看名下商户、添加商户、直接续期、档案和跟进' },
  { key: 'codes', label: '激活码', hint: '生成、作废、导出激活码' },
  { key: 'distribute', label: '分配烟火', hint: '从团队账户分烟火给名下商户，可设每月上限', ownerOnly: true },
  { key: 'finance', label: '财务查看', hint: '看团队烟火、平台发放和全部分配记录', ownerOnly: true },
  { key: 'team', label: '团队管理', hint: '添加、移除成员，改成员的权限和称呼' },
]
export const defaultPermissions = () => ({ tenants: true, codes: true, distribute: false, finance: false, team: false })

/** 权限列表的简短文字，例如「商户、激活码」；负责人是「全部」。 */
export function permissionText(member) {
  if (member?.role === 'OWNER') return '全部'
  const names = permissionItems.filter(item => member?.permissions?.[item.key]).map(item => item.label)
  return names.length ? names.join('、') : '无'
}

/**
 * 当前账号能不能把成员的权限从 before 改成 after：负责人什么都能改；成员不能动只有负责人能给的几项和每月上限，
 * 也只能给出自己有的权限。@return 不能改的原因，能改时为空字符串
 */
export function grantError(me, before, after, capBefore = null, capAfter = null) {
  if (me?.role === 'OWNER') return ''
  const from = before || {}
  if (permissionItems.some(item => item.ownerOnly && Boolean(from[item.key]) !== Boolean(after[item.key])) || (capBefore ?? null) !== (capAfter ?? null))
    return '分配烟火、财务查看和每月上限只有负责人能设。'
  const missing = permissionItems.filter(item => !item.ownerOnly && after[item.key] && !from[item.key] && !me?.permissions?.[item.key])
  return missing.length ? `你没有「${missing.map(item => item.label).join('、')}」权限，不能给别人。` : ''
}

/** 团队称呼：1 到 20 个字。通过返回空字符串。 */
export function displayNameError(value) {
  const name = String(value ?? '').trim()
  if (!name) return '请填写称呼。'
  return [...name].length > 20 ? '称呼最多 20 个字。' : ''
}

/** 按时段问候：0-6 点夜深了，6-11 早上好，11-13 中午好，13-18 下午好，之后晚上好。 */
export function greeting(hour) {
  return hour < 6 ? '夜深了' : hour < 11 ? '早上好' : hour < 13 ? '中午好' : hour < 18 ? '下午好' : '晚上好'
}

/** 从团队账户分出 credits 时，赠送和充值各扣多少（先扣赠送）。 */
export function accountSplit(account, credits) {
  const gift = Math.min(account?.gift || 0, credits)
  return { gift, purchased: credits - gift }
}

/** 待办分类：标题、说明和对服务商的建议动作。顺序就是首页的展示顺序。 */
export const todoTypes = {
  FOLLOW_UP: { title: '预约跟进', short: '预约跟进', hint: '未来 7 天和已逾期的预约', tone: 'info' },
  EXPIRING: { title: '7 天内到期', short: '即将到期', hint: '提醒续费，或直接用额度续期', tone: 'warn' },
  EXPIRED: { title: '30 天内刚过期', short: '已过期', hint: '会员已停，尽快联系续费', tone: 'alert' },
  LOW_BALANCE: { title: '烟火余额不足', short: '余额不足', hint: '余额不到最近一次充值的 10%', tone: 'warn' },
  NOT_STARTED: { title: '开通了还没用过', short: '未开始使用', hint: '帮商户完成第一次创作', tone: 'info' },
  IDLE: { title: '7 天没有使用', short: '7 天未用', hint: '回访了解原因，防止流失', tone: 'muted' },
}

/**
 * 待办按商户合并：一个商户一行，reasons 按优先级排（todoTypes 的顺序），整体按最紧急的原因、再按到期时间排。
 * 筛选某一类时只留有这一类原因的商户。
 */
export function groupTodos(todos, type = '') {
  const order = Object.keys(todoTypes)
  const byTenant = new Map()
  for (const todo of todos || []) {
    const row = byTenant.get(todo.tenant.tenantId) || { tenant: todo.tenant, reasons: [] }
    row.reasons.push(todo)
    byTenant.set(todo.tenant.tenantId, row)
  }
  return [...byTenant.values()]
    .map(row => ({ ...row, reasons: row.reasons.sort((a, b) => order.indexOf(a.type) - order.indexOf(b.type)) }))
    .filter(row => !type || row.reasons.some(r => r.type === type))
    .sort((a, b) => order.indexOf(a.reasons[0].type) - order.indexOf(b.reasons[0].type)
      || (a.tenant.memberUntil ? Date.parse(a.tenant.memberUntil) : Infinity) - (b.tenant.memberUntil ? Date.parse(b.tenant.memberUntil) : Infinity))
}

/**
 * 预约跟进（商户档案里约好的下次跟进）变成待办，和工作台的其他待办一起按商户合并。
 * 接口只给商户名和联系人，用 followUpOnly 标出来：合并时优先用工作台那份更全的商户信息。
 */
export function followUpTodos(items) {
  return (items || []).filter(item => item.profile?.nextFollowUpAt).map(item => ({
    type: 'FOLLOW_UP', at: item.profile.nextFollowUpAt,
    tenant: { tenantId: item.tenantId, tenantName: item.tenantName, ownerName: item.profile.contactName || null, followUpOnly: true },
  }))
}

/** 会员状态分布：有效（不含即将到期）、7 天内到期、已过期、从未开通；每段带占比，画横向堆叠条用。 */
export function memberSegments(d) {
  const rows = [
    { key: 'active', label: '有效（7 天后到期）', value: Math.max(0, (d?.activeMembers || 0) - (d?.expiringSoon || 0)) },
    { key: 'expiring', label: '7 天内到期', value: d?.expiringSoon || 0 },
    { key: 'expired', label: '已过期', value: d?.expiredMembers || 0 },
    { key: 'never', label: '从未开通', value: d?.neverActivated || 0 },
  ]
  const total = rows.reduce((sum, r) => sum + r.value, 0)
  return rows.map(r => ({ ...r, ratio: total ? r.value / total : 0 }))
}

/** 百分比文字，分母为 0 时给「—」。 */
export const percent = (part, whole) => whole ? `${Math.round(part / whole * 100)}%` : '—'
export const capabilityNames = { IMAGE: '图片创作', IMAGE_INSPIRATION: '图片灵感', VIDEO: '视频创作', VIDEO_ANALYSIS: '视频反推', VIDEO_PLAN: '视频方案', COPY: '文案创作', LIVE: '实景直播', OTHER: '其他' }
export const dashboardRanges = [{ value: 1, label: '今天' }, { value: 7, label: '7 天' }, { value: 30, label: '30 天' }, { value: 90, label: '90 天' }]

/** 「3 天前」这类相对时间；没有时间返回「从未」。 */
export function sinceText(value, now = Date.now()) {
  if (!value) return '从未'
  const days = Math.floor((now - new Date(value).getTime()) / DAY)
  return days <= 0 ? '今天' : days === 1 ? '昨天' : `${days} 天前`
}

/** 把按功能的消耗变成从多到少的列表，每项带占最大值的比例（画横条用）。 */
export function capabilityBars(byCapability) {
  const rows = Object.entries(byCapability || {}).filter(([, v]) => v > 0).sort((a, b) => b[1] - a[1])
  const max = rows.length ? rows[0][1] : 0
  return rows.map(([key, value]) => ({ key, label: capabilityNames[key] || key, value, ratio: max ? value / max : 0 }))
}

/** 柱状图的纵轴上限：取不小于最大值的「好看」整数（1、2、5 乘 10 的幂），全 0 时为 1。 */
export function niceMax(values) {
  const max = Math.max(0, ...values)
  if (max <= 0) return 1
  const step = 10 ** Math.floor(Math.log10(max))
  return [1, 2, 5, 10].map(m => m * step).find(v => v >= max)
}

const DAY = 24 * 60 * 60 * 1000
export const dateText = value => {
  if (value == null) return '—'
  const d = new Date(value)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
export const number = value => Number(value || 0).toLocaleString('zh-CN')

/** 商户会员状态：{ text, short, tone }。tone 为 warn（7 天内到期）、muted（未开通 / 已过期）、ok 或空（读不到）。 */
export function membershipState(item, now = Date.now()) {
  if (item.memberActive == null) return { text: '—', short: '—', tone: '' }
  if (!item.memberUntil) return { text: '从未开通', short: '从未开通', tone: 'muted' }
  const until = new Date(item.memberUntil).getTime()
  if (!item.memberActive) return { text: `已过期 · ${dateText(until)}`, short: '已过期', tone: 'muted' }
  const days = Math.ceil((until - now) / DAY)
  return { text: `${item.planName || '会员'} · ${dateText(until)} 到期（剩 ${days} 天）`, short: `剩 ${days} 天`, tone: days <= 7 ? 'warn' : 'ok' }
}

/** 续期后的到期时间：还没到期的从原到期日往后加，否则从现在算。 */
export function renewedUntil(item, durationDays, now = Date.now()) {
  const until = item.memberUntil ? new Date(item.memberUntil).getTime() : 0
  const start = item.memberActive && until > now ? until : now
  return new Date(start + durationDays * DAY)
}

/** 分配烟火的单笔金额校验；通过返回空字符串。 */
export function transferError(raw, available) {
  const credits = Number(String(raw ?? '').trim())
  if (!String(raw ?? '').trim()) return '请输入烟火数。'
  if (!Number.isInteger(credits) || credits < 1 || credits > 1000000) return '请输入 1 至 1,000,000 的整数。'
  if (credits > Number(available || 0)) return `超过可分配的 ${number(available)} 烟火。`
  return ''
}

/**
 * 批量分烟火前的核对：每行 { tenantId, tenantName, credits }，空着的行跳过。
 * @return { entries, total, error } entries 只含要分的行
 */
export function planBatchTransfer(rows, available) {
  const entries = []
  for (const row of rows) {
    const raw = String(row.credits ?? '').trim()
    if (!raw) continue
    const credits = Number(raw)
    if (!Number.isInteger(credits) || credits < 1 || credits > 1000000)
      return { entries: [], total: 0, error: `「${row.tenantName || '商户'}」请输入 1 至 1,000,000 的整数。` }
    entries.push({ ...row, credits })
  }
  const total = entries.reduce((sum, e) => sum + e.credits, 0)
  if (!entries.length) return { entries, total, error: '请至少给一个商户填写烟火数。' }
  if (total > Number(available || 0))
    return { entries, total, error: `合计 ${number(total)} 烟火，超过可分配的 ${number(available)}。` }
  return { entries, total, error: '' }
}

/**
 * 逐笔分配。每笔带自己的 requestKey（失败后用同一个重试，不会重复扣烟火）；一笔失败不影响其他笔。
 * @return [{ tenantId, ok, error, result }]
 */
export async function runBatchTransfers(entries, keys, transfer) {
  const results = []
  for (const entry of entries) {
    try {
      const result = await transfer(entry.tenantId, { requestKey: keys[entry.tenantId], credits: entry.credits })
      results.push({ tenantId: entry.tenantId, ok: true, error: '', result })
    } catch (failure) {
      results.push({ tenantId: entry.tenantId, ok: false, error: failure?.message || '分配失败', result: null })
    }
  }
  return results
}

/** 商户详情里的记录：服务商分给他的烟火、他兑换的激活码和直接续期，按时间倒序。 */
export function tenantTimeline(detail) {
  const rows = [
    ...(detail?.transfers || []).map(t => ({ key: 'l' + t.id, at: t.createdAt, kind: 'transfer',
      title: `分配 ${number(t.credits)} 烟火${t.actorName ? ` · ${t.actorName}` : ''}${t.status === 'PENDING' ? '（未到账）' : ''}`, note: t.note })),
    ...(detail?.codes || []).map(c => ({ key: 'c' + c.id, at: c.redeemedAt, kind: c.note === '直接续期' ? 'renew' : 'code',
      title: `${c.note === '直接续期' ? '直接续期' : '兑换激活码'} · ${c.planName || '套餐'} ${c.durationDays ?? ''} 天`.trim(), note: c.note === '直接续期' ? null : c.note })),
  ]
  return rows.filter(r => r.at).sort((a, b) => new Date(b.at) - new Date(a.at))
}

/** 按 UID 预查询的结果说明：{ text, can }，can 表示能不能添加。 */
export function lookupVerdict(found) {
  if (!found) return { text: '', can: false }
  return {
    NONE: { text: '还没有归属服务商，可以添加到名下。', can: true },
    MINE: { text: '已经在名下。', can: false },
    OTHER: { text: '已归属其他服务商，不能添加。', can: false },
  }[found.tenantState] || { text: '', can: false }
}

const cell = value => {
  const text = value == null ? '' : String(value)
  return /[",\n\r]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

/** 激活码导出成 CSV（带 BOM，Excel 直接打开不乱码）。 */
export function codesCsv(items, { statuses = {}, creators = {}, format = code => code, date = value => value || '' } = {}) {
  const header = ['激活码', '套餐', '天数', '状态', '生成人', '兑换商户', '兑换时间', '备注', '生成时间']
  const lines = items.map(item => [format(item.code), item.planName, item.durationDays, statuses[item.status] || item.status,
    creators[item.createdBy] || '', item.redeemedTenantName, date(item.redeemedAt), item.note, date(item.createdAt)].map(cell).join(','))
  return '﻿' + [header.join(','), ...lines].join('\r\n') + '\r\n'
}

/** 在浏览器里把 CSV 存成文件，文件名带本地日期。 */
export function downloadCsv(csv, prefix) {
  const today = new Date()
  const link = document.createElement('a')
  link.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
  link.download = `${prefix}-${dateText(today)}.csv`
  link.click()
  setTimeout(() => URL.revokeObjectURL(link.href), 1000)
}
