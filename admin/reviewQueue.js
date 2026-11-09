import { platformManagementApi } from '../src/services/platformManagement.js'
import { allianceAdminApi } from '../src/services/allianceApi.js'
import { reviewKindOrder, settleRateBp, sortReviewItems, toReviewItems } from '../src/domain/adminReview.js'

/** 每类最多读取的待审条数；超出时页面提示去原列表处理。 */
export const QUEUE_PAGE_SIZE = 50

const sources = {
  information: () => platformManagementApi.information({ status: 'PENDING_REVIEW', page: 0, size: QUEUE_PAGE_SIZE }),
  merchant: () => platformManagementApi.merchants({ status: 'PENDING_REVIEW', page: 0, size: QUEUE_PAGE_SIZE }),
  offer: () => allianceAdminApi.pendingOffers(),
  dispute: () => allianceAdminApi.disputes(),
  account: () => allianceAdminApi.accounts('PENDING_VERIFY'),
}

/**
 * 读取五类待处理事项。某一类读不到不影响其他类：失败的类放在 failed，
 * 分页接口还有更多记录时放在 more（类 → 总数）。
 */
export async function loadReviewQueue() {
  const results = await Promise.allSettled(reviewKindOrder.map(kind => sources[kind]()))
  const items = []
  const failed = []
  const more = {}
  results.forEach((result, index) => {
    const kind = reviewKindOrder[index]
    if (result.status === 'rejected') { failed.push(kind); return }
    const value = result.value
    const list = Array.isArray(value) ? value : value?.items || []
    if (!Array.isArray(value) && value?.total > list.length) more[kind] = value.total
    items.push(...toReviewItems[kind](list))
  })
  return { items: sortReviewItems(items), failed, more }
}

/** 提交审核结果。approved 表示通过（申诉里表示核销有效）。 */
export function submitReview(item, approved, { note = '', rate = '' } = {}) {
  const text = note.trim()
  const raw = item.raw
  switch (item.kind) {
    case 'information': return platformManagementApi.informationStatus(raw, { status: approved ? 'PUBLISHED' : 'REJECTED', note: text, updatedAt: raw.updatedAt })
    case 'merchant': return platformManagementApi.merchantReview(raw, { status: approved ? 'APPROVED' : 'REJECTED', note: text })
    case 'offer': return allianceAdminApi.review(raw, approved, text, approved ? settleRateBp(rate) : undefined)
    case 'dispute': return allianceAdminApi.resolveDispute(raw, approved, text)
    case 'account': return allianceAdminApi.reviewAccount(raw, approved, text)
    default: throw new Error('未知的审核类型')
  }
}

/** 按填写的用途查看完整资料，后端留存查阅记录。只有入驻和收款账号有脱敏资料。 */
export function revealDetail(item, purpose) {
  if (item.kind === 'merchant') return platformManagementApi.revealMerchant(item.raw, purpose.trim())
  if (item.kind === 'account') return allianceAdminApi.revealAccount(item.raw, purpose.trim())
  throw new Error('这类事项没有需要查看的完整资料')
}

/** 信息和入驻有平台处理记录。 */
export function reviewTrail(item) {
  if (item.kind === 'information') return platformManagementApi.audit(item.raw, 'INFORMATION')
  if (item.kind === 'merchant') return platformManagementApi.audit(item.raw, 'MERCHANT')
  return Promise.resolve(null)
}
