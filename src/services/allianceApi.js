import { get, post } from '../utils/request.js'

const params = values => Object.fromEntries(Object.entries(values || {}).filter(([, value]) => value != null && value !== ''))
const id = value => encodeURIComponent(value)

/** 联盟券：领券方的额度、券库和我的联盟券；供券方的供券单、结算和门店核销。 */
export const allianceApi = {
  rules: () => get('/api/public/alliance/rules'),
  quota: () => get('/api/alliance/quota'),
  pool: values => get('/api/alliance/pool', params(values)),
  claim: offerId => post(`/api/alliance/offers/${id(offerId)}/claims`),
  vouchers: values => get('/api/alliance/vouchers', params(values)),
  voucher: ref => get(`/api/alliance/vouchers/${id(ref)}`),
  dispute: (ref, reason) => post(`/api/alliance/vouchers/${id(ref)}/dispute`, { reason }),

  offers: () => get('/api/alliance/offers'),
  createOffer: values => post('/api/alliance/offers', values),
  withdrawOffer: offerId => post(`/api/alliance/offers/${id(offerId)}/withdraw`),
  settlements: values => get('/api/alliance/settlements', params(values)),
  storeProducts: storeId => get(`/api/stores/${id(storeId)}/products`, { size: 100 }),

  previewRedemption: (storeId, code) => post(`/api/stores/${id(storeId)}/alliance-redemptions/preview`, { code }),
  redeem: (storeId, code) => post(`/api/stores/${id(storeId)}/alliance-redemptions`, { code }),
  redemptions: storeId => get(`/api/stores/${id(storeId)}/alliance-redemptions`),
}

/** 平台运营。 */
export const allianceAdminApi = {
  summary: () => get('/api/platform/alliance/summary'),
  pendingOffers: () => get('/api/platform/alliance/offers'),
  review: (item, approved, note, settleRateBp) => post(`/api/platform/tenants/${id(item.tenantId)}/alliance/offers/${id(item.offer.id)}/review`, { approved, note, settleRateBp }),
  disputes: () => get('/api/platform/alliance/disputes'),
  resolveDispute: (item, valid, note) => post(`/api/platform/tenants/${id(item.holderTenantId)}/alliance/vouchers/${id(item.ref)}/dispute-resolution`, { valid, note }),
  settlements: status => get('/api/platform/alliance/settlements', params({ status })),
  markPaid: (item, reference) => post(`/api/platform/tenants/${id(item.tenantId)}/alliance/settlements/${id(item.settlement.id)}/paid`, { reference }),
}

export const voucherStatuses = {
  CLAIMING: '领取中',
  ACTIVE: '可使用',
  CLAIM_FAILED: '领取失败',
  REDEEMED: '已核销',
  DISPUTED: '申诉中',
  EXPIRED: '已过期',
}

export const offerStatuses = {
  PENDING_REVIEW: '待平台审核',
  ACTIVE: '已上架',
  SOLD_OUT: '已领完',
  REJECTED: '已驳回',
  WITHDRAWN: '已撤回',
}

export const settlementStates = {
  IN_DISPUTE_WINDOW: '申诉期',
  FROZEN: '申诉处理中',
  PAYABLE: '待结算',
  PAID: '已结算',
  VOID: '已作废',
}

/** 约定的结算比例（万分比）显示成「x 折」：5000 → 5 折，8500 → 8.5 折。 */
export const discountLabel = bp => bp ? `${(bp / 1000).toFixed(1).replace(/\.0$/, '')} 折` : ''

export const yuan = minor => (Number(minor || 0) / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
