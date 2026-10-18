export const informationCategories = [
  { value: '', label: '全部信息' },
  { value: 'activity', label: '门店活动' },
  { value: 'cooperation', label: '商业合作' },
  { value: 'supply', label: '供求信息' },
  { value: 'notice', label: '商家公告' },
]

export const informationCategoryNames = Object.fromEntries(informationCategories.filter(item => item.value).map(item => [item.value, item.label]))

export function informationFilters(query = {}) {
  const category = informationCategories.some(item => item.value === query.category) ? query.category : ''
  const keyword = typeof query.keyword === 'string' ? query.keyword.trim().slice(0, 80) : ''
  const requestedPage = typeof query.page === 'string' && /^\d+$/.test(query.page) ? Number(query.page) : 0
  const page = Number.isSafeInteger(requestedPage) && requestedPage <= 10000 ? requestedPage : 0
  return { category, keyword, page }
}

export function informationQuery(filters) {
  return {
    ...(filters.category && { category: filters.category }),
    ...(filters.keyword && { keyword: filters.keyword }),
    ...(filters.page > 0 && { page: String(filters.page) }),
  }
}

export function informationDate(value, detailed = false) {
  if (!value || Number.isNaN(new Date(value).getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    ...(detailed && { hour: '2-digit', minute: '2-digit', hour12: false }),
  }).format(new Date(value))
}

export function publicationState(item) {
  if (item.status === 'PENDING_REVIEW') return { label: '待审核', className: 'is-pending', visible: false }
  if (item.status === 'REJECTED') return { label: '已驳回', className: 'is-rejected', visible: false }
  if (item.status !== 'PUBLISHED') return { label: '已下架', className: 'is-offline', visible: false }
  const parts = new Intl.DateTimeFormat('en', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date())
  const date = Object.fromEntries(parts.map(part => [part.type, part.value]))
  const today = `${date.year}-${date.month}-${date.day}`
  if (item.validUntil && item.validUntil < today) return { label: '已过期', className: 'is-offline', visible: false }
  return { label: '公开中', className: 'is-published', visible: true }
}
