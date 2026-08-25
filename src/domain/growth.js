/** Evidence-bound Growth Copilot contracts. The model never writes directly. */

function id(prefix) { return `${prefix}_${crypto.randomUUID()}` }

export function createInsight(input = {}) {
  if (!input.tenantId || !input.title || !input.evidence?.length) throw new Error('INSIGHT_EVIDENCE_REQUIRED')
  return { id: input.id || id('insight'), tenantId: input.tenantId, merchantId: input.merchantId || null, title: input.title, kind: input.kind || 'OPPORTUNITY', summary: input.summary || '', evidence: input.evidence, period: input.period || null, sampleSize: input.sampleSize ?? null, confidence: input.confidence || 'LOW', status: 'DRAFT', createdAt: new Date().toISOString() }
}

export function createGrowthPlan(insights = [], input = {}) {
  if (!input.tenantId || !insights.length) throw new Error('GROWTH_PLAN_INPUT_REQUIRED')
  return { id: input.id || id('growth_plan'), tenantId: input.tenantId, merchantId: input.merchantId || null, title: input.title || '下一轮经营行动', insightIds: insights.map((insight) => insight.id), actions: input.actions || [], status: 'DRAFT', requiresApproval: true, approvedBy: null, inputSnapshot: input.inputSnapshot || null, createdAt: new Date().toISOString() }
}

export function approveGrowthPlan(plan, approverId) {
  if (!plan?.requiresApproval || !approverId) throw new Error('GROWTH_APPROVAL_REQUIRED')
  return { ...plan, status: 'APPROVED', approvedBy: approverId, approvedAt: new Date().toISOString() }
}

