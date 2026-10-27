import { get } from '../utils/request.js'

const params = values => Object.fromEntries(Object.entries(values || {}).filter(([, value]) => value != null && value !== ''))

/** 全平台烟火流水（需要计费运营权限）。只读。 */
export const ledgerAdminApi = {
  entries: values => get('/api/billing/operations/ledger', params(values)),
  summary: days => get('/api/billing/operations/ledger/summary', { days }),
}

export const ledgerTypes = { CAPTURE: '扣除', RESERVE: '预占', RELEASE: '退回', PURCHASE: '充值', GRANT: '赠送' }
export const ledgerCapabilities = { IMAGE: '图片创作', VIDEO: '视频创作', VIDEO_ANALYSIS: '视频反推', VIDEO_PLAN: '视频方案', LIVE: '实景直播' }
export const ledgerRanges = { 1: '今天', 7: '近 7 天', 30: '近 30 天', 90: '近 90 天' }

/** 这一笔对可用烟火的影响：扣除取预占的减少量（可用余额在预占时已扣过），其余取可用烟火的变动。 */
export function ledgerChange(entry) {
  const amount = entry.type === 'CAPTURE' ? -entry.heldDelta : entry.availableDelta
  if (entry.type === 'CAPTURE') return { text: `-${Math.abs(amount)}`, tone: 'out' }
  if (entry.type === 'RESERVE') return { text: `-${Math.abs(amount)}`, tone: 'hold' }
  return { text: `+${Math.abs(amount)}`, tone: 'in' }
}
