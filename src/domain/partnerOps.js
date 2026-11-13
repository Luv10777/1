/**
 * 服务商后台的纯逻辑：到期筛选、会员状态文字、续期预览、批量分烟火、商户记录、激活码导出。不发请求，方便单测。
 */

export const partnerRoles = { OWNER: '负责人', BD: 'BD' }
export const expiryFilters = { SOON7: '7 天内到期', SOON30: '30 天内到期', EXPIRED: '已过期', NEVER: '从未开通' }
export const transferPresets = [500, 1000, 2000, 5000]

/** 待办分类：标题、说明和对服务商的建议动作。顺序就是首页的展示顺序。 */
export const todoTypes = {
  EXPIRING: { title: '7 天内到期', hint: '提醒续费，或直接用额度续期' },
  EXPIRED: { title: '30 天内刚过期', hint: '会员已停，尽快联系续费' },
  LOW_BALANCE: { title: '烟火余额不足', hint: '余额不到最近一次充值的 10%' },
  NOT_STARTED: { title: '开通了还没用过', hint: '帮商户完成第一次创作' },
  IDLE: { title: '7 天没有使用', hint: '回访了解原因，防止流失' },
}
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
    ...(detail?.transfers || []).map(t => ({ key: 'l' + t.id, at: t.createdAt, kind: 'transfer', title: `分配 ${number(-t.delta)} 烟火`, note: t.note })),
    ...(detail?.codes || []).map(c => ({ key: 'c' + c.id, at: c.redeemedAt, kind: c.note === '直接续期' ? 'renew' : 'code',
      title: `${c.note === '直接续期' ? '直接续期' : '兑换激活码'} · ${c.planName || '套餐'} ${c.durationDays ?? ''} 天`.trim(), note: c.note === '直接续期' ? null : c.note })),
  ]
  return rows.filter(r => r.at).sort((a, b) => new Date(b.at) - new Date(a.at))
}

/** 按 UID 预查询的结果说明：{ text, can }，can 表示能不能添加。 */
export function lookupVerdict(found, owner) {
  if (!found) return { text: '', can: false }
  return {
    NONE: { text: owner ? '还没有归属服务商，可以添加到你名下。' : '还没有归属服务商，可以添加到你名下（归你负责）。', can: true },
    MINE: { text: '已经在你名下。', can: false },
    TEAM: { text: `已在本服务商名下${found.bdName ? `，由 BD「${found.bdName}」负责` : '，由负责人直接管'}。需要转给你请联系负责人。`, can: false },
    OTHER: { text: '已归属其他服务商，不能添加。', can: false },
  }[found.tenantState] || { text: '', can: false }
}

/** 添加 BD 前的说明：{ text, can }。 */
export function memberVerdict(found) {
  if (!found) return { text: '', can: false }
  return {
    NONE: { text: '可以添加为你的 BD。添加后对方用自己的账号登录服务商后台。', can: true },
    BD: { text: '已经是你的 BD。', can: false },
    OWNER: { text: '是本服务商的负责人账号。', can: false },
    OTHER: { text: '已是其他服务商的登录账号，不能添加。', can: false },
  }[found.memberState] || { text: '', can: false }
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
