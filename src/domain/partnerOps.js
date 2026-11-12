/**
 * 服务商后台的纯逻辑：到期筛选、会员状态文字、批量分烟火、激活码导出。不发请求，方便单测。
 */

export const partnerRoles = { OWNER: '负责人', BD: 'BD' }
export const expiryFilters = { SOON7: '7 天内到期', SOON30: '30 天内到期', EXPIRED: '已过期', NEVER: '从未开通' }

const DAY = 24 * 60 * 60 * 1000
const dateText = value => {
  const d = new Date(value)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** 商户会员状态：{ text, tone }。tone 为 warn（7 天内到期）、muted（未开通 / 已过期）或空。 */
export function membershipState(item, now = Date.now()) {
  if (item.memberActive == null) return { text: '—', tone: '' }
  if (!item.memberUntil) return { text: '从未开通', tone: 'muted' }
  const until = new Date(item.memberUntil).getTime()
  if (!item.memberActive) return { text: `已过期 · ${dateText(until)}`, tone: 'muted' }
  const days = Math.ceil((until - now) / DAY)
  return { text: `${item.planName || '会员'} · ${dateText(until)} 到期（剩 ${days} 天）`, tone: days <= 7 ? 'warn' : '' }
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
    return { entries, total, error: `合计 ${total.toLocaleString('zh-CN')} 烟火，超过可分配的 ${Number(available || 0).toLocaleString('zh-CN')}。` }
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
