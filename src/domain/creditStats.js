/** 工作台烟火统计：把按天合计切成「本期」和「上一个同长度时段」，并算涨跌。 */

export const creditRanges = [
  { key: '1', days: 1, label: '今天', compare: '比昨天' },
  { key: '7', days: 7, label: '近 7 天', compare: '比上个 7 天' },
  { key: '30', days: 30, label: '近 30 天', compare: '比上个 30 天' },
  { key: '90', days: 90, label: '近 90 天', compare: '比上个 90 天' },
]

export const creditMetrics = [
  { key: 'consumed', label: '消耗' },
  { key: 'purchased', label: '购买' },
  { key: 'granted', label: '赠送' },
  { key: 'refunded', label: '退回' },
]

/** 对比需要两倍时段的数据，后端按天合计最多给 180 天。 */
export const DAILY_DAYS = 180

const empty = () => ({ consumed: 0, purchased: 0, granted: 0, refunded: 0, byCapability: {} })

function sum(rows) {
  return rows.reduce((total, row) => {
    for (const { key } of creditMetrics) total[key] += Number(row[key] || 0)
    for (const [capability, amount] of Object.entries(row.consumedByCapability || {})) {
      total.byCapability[capability] = (total.byCapability[capability] || 0) + Number(amount)
    }
    return total
  }, empty())
}

/**
 * daily 是按日期从早到晚、最后一天为今天的合计。返回本期、上期合计；
 * 数据不够覆盖上期时 previous 为 null，界面不显示涨跌。
 */
export function periodStats(daily, days) {
  const rows = Array.isArray(daily) ? daily : []
  const current = rows.slice(-days)
  const previous = rows.length >= days * 2 ? rows.slice(-days * 2, -days) : null
  return { current: sum(current), previous: previous ? sum(previous) : null }
}

/** 图表和明细表显示的天数：「今天」只有一天，放最近 14 天方便对照，其余时段显示整段。 */
export function windowRows(daily, days) {
  const rows = Array.isArray(daily) ? daily : []
  return rows.slice(-(days === 1 ? 14 : days))
}

/**
 * 涨跌：tone 为 up / down / flat / none。上期为 0 时没有百分比可算，本期有数就显示「新增」。
 * 上期未知（null / undefined）时显示「—」。
 */
export function change(current, previous) {
  if (previous == null || current == null) return { tone: 'none', text: '—' }
  const now = Number(current)
  const before = Number(previous)
  if (before === 0) return now === 0 ? { tone: 'flat', text: '持平' } : { tone: 'up', text: '新增' }
  const percent = (now - before) / before * 100
  if (Math.abs(percent) < 0.05) return { tone: 'flat', text: '持平' }
  return { tone: percent > 0 ? 'up' : 'down', text: `${percent > 0 ? '+' : '-'}${Math.abs(percent).toFixed(1)}%` }
}

/** 各功能的消耗占比，按本期从多到少；本期和上期都为 0 的功能不列出。 */
export function capabilityShares(current, previous) {
  const total = Object.values(current.byCapability).reduce((value, amount) => value + amount, 0)
  const keys = new Set([...Object.keys(current.byCapability), ...Object.keys(previous?.byCapability || {})])
  return [...keys]
    .map(key => {
      const amount = current.byCapability[key] || 0
      return { key, amount, share: total ? amount / total * 100 : 0, previous: previous ? previous.byCapability[key] || 0 : null }
    })
    .filter(item => item.amount > 0 || item.previous > 0)
    .sort((a, b) => b.amount - a.amount || a.key.localeCompare(b.key))
}
