import { informationCategories } from './platformManagement.js'
import { accountTypes, yuan } from '../services/allianceApi.js'

/** 等待超过这个时长的事项在后台标红，提醒优先处理。 */
export const OVERDUE_HOURS = 24

/**
 * 审核中心里每一类事项的按钮文案和处理依据规则。
 * 信息和入驻沿用原规则：通过、驳回都要写至少 5 字依据；联盟券三类只在驳回或判定无效时必须写原因。
 */
export const reviewKinds = {
  information: {
    label: '信息', title: '待审核信息', approve: '通过并发布', reject: '驳回信息', noteMax: 500, noteMin: 5, approveNeedsNote: true,
    reasons: ['活动有效期没写清楚', '价格或优惠与门店实际不符', '含夸大或绝对化用语', '联系方式不应写在正文', '内容与所选分类不符'],
  },
  merchant: {
    label: '入驻', title: '待审核入驻', approve: '通过入驻', reject: '驳回申请', noteMax: 500, noteMin: 5, approveNeedsNote: true,
    reasons: ['资料齐全，主体信息一致', '营业执照与填写主体不一致', '经营地址不清晰，请补充', '经营范围不在平台服务内'],
  },
  offer: {
    label: '供券', title: '待审核供券', approve: '通过并上架', reject: '驳回供券', noteMax: 300, noteMin: 1, approveNeedsNote: false, needsRate: true,
    reasons: ['面值与商品售价不匹配', '供券数量过多，请分批提交', '可用门店地址不完整'],
  },
  dispute: {
    label: '申诉', title: '待处理申诉', approve: '判定核销有效', reject: '判定核销无效', noteMax: 300, noteMin: 1, approveNeedsNote: false,
    reasons: ['核销记录与门店店员账号一致', '已联系顾客确认未到店', '核销时间与门店营业时间不符'],
  },
  account: {
    label: '收款账号', title: '待核对收款账号', approve: '核对通过', reject: '驳回账号', noteMax: 300, noteMin: 1, approveNeedsNote: false,
    reasons: ['户名与入驻主体不一致', '开户行或支行信息不完整'],
  },
}

export const reviewKindOrder = Object.keys(reviewKinds)

const time = value => {
  const ms = value ? new Date(value).getTime() : NaN
  return Number.isNaN(ms) ? null : ms
}

/** 各接口返回的记录整理成统一条目：kind、key、标题、来源方、补充说明、开始等待的时间，原始记录放在 raw。 */
export const toReviewItems = {
  information: list => list.map(item => ({
    kind: 'information', key: `information-${item.tenantId}-${item.id}`,
    title: item.title, party: item.merchantName || item.tenantName, detail: [informationCategories[item.category] || item.category, item.region].filter(Boolean).join(' · '),
    since: time(item.updatedAt || item.createdAt), raw: item,
  })),
  merchant: list => list.map(item => ({
    kind: 'merchant', key: `merchant-${item.tenantId}-${item.id}`,
    title: item.merchantName, party: item.companyName, detail: [item.industry, item.region].filter(Boolean).join(' · '),
    since: time(item.updatedAt || item.createdAt), raw: item,
  })),
  offer: list => list.map(item => ({
    kind: 'offer', key: `offer-${item.tenantId}-${item.offer.id}`,
    title: item.offer.productName, party: item.supplierName, detail: `面值 ¥${yuan(item.offer.faceValueMinor)} · ${item.offer.totalQty} ${item.offer.saleUnit || '张'}`,
    since: time(item.offer.createdAt), raw: item,
  })),
  dispute: list => list.map(item => ({
    kind: 'dispute', key: `dispute-${item.ref}`,
    title: item.productName, party: `领券商户：${item.holderName}`, detail: `${item.storeName} · ¥${yuan(item.faceValueMinor)}`,
    since: time(item.disputedAt), raw: item,
  })),
  account: list => list.map(item => ({
    kind: 'account', key: `account-${item.tenantId}`,
    title: `${item.supplierName} 的收款账户`, party: item.account.accountName, detail: accountTypes[item.account.accountType] || '',
    since: time(item.account.updatedAt), raw: item,
  })),
}

/** 等得最久的排最前；没有时间的放最后。 */
export function sortReviewItems(items) {
  return [...items].sort((a, b) => (a.since ?? Infinity) - (b.since ?? Infinity))
}

export function waitedHours(since, now = Date.now()) {
  return since == null ? null : Math.max(0, (now - since) / 3_600_000)
}

export function isOverdue(item, now = Date.now()) {
  const hours = waitedHours(item.since, now)
  return hours != null && hours >= OVERDUE_HOURS
}

export function waitedLabel(since, now = Date.now()) {
  const hours = waitedHours(since, now)
  if (hours == null) return '—'
  if (hours < 1) return `${Math.max(1, Math.round(hours * 60))} 分钟`
  if (hours < 24) return `${Math.floor(hours)} 小时`
  const days = Math.floor(hours / 24)
  const rest = Math.floor(hours % 24)
  return rest ? `${days} 天 ${rest} 小时` : `${days} 天`
}

/**
 * 提交前的校验，返回错误文案；通过返回空字符串。
 * rate 是结算折扣（填 5 表示按面值 5 折），只有通过供券时需要。
 */
export function reviewInputError(kind, approved, { note = '', rate = '' } = {}) {
  const rule = reviewKinds[kind]
  const text = note.trim()
  if (text.length > rule.noteMax) return `处理依据最多 ${rule.noteMax} 字。`
  if ((approved ? rule.approveNeedsNote : true) && text.length < rule.noteMin) {
    return rule.noteMin > 1 ? `请先写至少 ${rule.noteMin} 字的处理依据，会留存在记录里。` : '请写明原因，会展示给商户。'
  }
  if (approved && rule.needsRate) {
    const bp = settleRateBp(rate)
    if (bp == null) return '请填写约定的结算折扣，0.1 到 10 之间，例如 5 表示按面值 5 折结算。'
  }
  return ''
}

/** 结算折扣转成后端的基点：5 折 → 5000。不合法时返回 null。 */
export function settleRateBp(rate) {
  if (String(rate).trim() === '') return null
  const bp = Math.round(Number(rate) * 1000)
  return Number.isFinite(bp) && bp >= 1 && bp <= 10000 ? bp : null
}
