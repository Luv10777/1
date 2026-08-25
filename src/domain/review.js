export const REVIEW_RISK = Object.freeze({ LOW: 'LOW', MEDIUM: 'MEDIUM', HIGH: 'HIGH', CRITICAL: 'CRITICAL' })
export const REVIEW_STATUS = Object.freeze({ PENDING: 'PENDING', APPROVED: 'APPROVED', REJECTED: 'REJECTED', NEEDS_FIX: 'NEEDS_FIX' })

export function classifyReviewRisk(input = {}) {
  const text = `${input.text || ''} ${input.caption || ''}`
  const highRiskTerms = ['疗效保证', '绝对安全', '稳赚', '零风险', '官方认证']
  const criticalTerms = ['隐私泄露', '未成年人', '食品安全事故', '人身伤害', '违法']
  if (criticalTerms.some((term) => text.includes(term))) return REVIEW_RISK.CRITICAL
  if (highRiskTerms.some((term) => text.includes(term))) return REVIEW_RISK.HIGH
  if (input.hasUnconfirmedFacts) return REVIEW_RISK.MEDIUM
  return REVIEW_RISK.LOW
}

export function createReviewCase(input = {}) {
  const risk = classifyReviewRisk(input)
  return {
    id: input.id || `review_${crypto.randomUUID()}`,
    tenantId: input.tenantId || 'tenant_demo_001',
    itemId: input.itemId || null,
    risk,
    status: risk === REVIEW_RISK.CRITICAL || risk === REVIEW_RISK.HIGH ? REVIEW_STATUS.NEEDS_FIX : REVIEW_STATUS.PENDING,
    issueTypes: input.issueTypes || (risk === REVIEW_RISK.LOW ? [] : ['CONTENT_RISK']),
    publicReply: null,
    internalRecommendation: risk === REVIEW_RISK.LOW ? '可进入人工抽样审核' : '必须人工审核，不得自动发布',
    createdAt: new Date().toISOString(),
  }
}

export function approveReview(review, actorId) {
  if (!actorId) throw new Error('REVIEW_ACTOR_REQUIRED')
  if (review.risk === REVIEW_RISK.CRITICAL) throw new Error('CRITICAL_REVIEW_CANNOT_AUTO_APPROVE')
  return { ...review, status: REVIEW_STATUS.APPROVED, approvedBy: actorId, approvedAt: new Date().toISOString() }
}

export function createPublicReplyDraft(input = {}) {
  const review = createReviewCase(input)
  if (review.risk === REVIEW_RISK.HIGH || review.risk === REVIEW_RISK.CRITICAL) return { blocked: true, review, text: null }
  return { blocked: false, review, text: input.suggestedText || '感谢你的反馈，我们已经记录并会持续改进门店体验。' }
}
