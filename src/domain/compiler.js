import { createFactSnapshot } from './creative.js'

export const INTENT_SCHEMA = Object.freeze({
  type: 'object',
  required: ['intent', 'audience', 'channels', 'contentCount', 'tone'],
  properties: {
    intent: { type: 'string', minLength: 3 },
    audience: { type: 'string', minLength: 3 },
    channels: { type: 'array', minItems: 1, items: { type: 'string' } },
    contentCount: { type: 'integer', minimum: 1, maximum: 50 },
    tone: { type: 'string', minLength: 2 },
    constraints: { type: 'array', items: { type: 'string' } },
    missingFacts: { type: 'array', items: { type: 'string' } },
  },
})

const allowedIntentKeys = new Set(Object.keys(INTENT_SCHEMA.properties))

export function validateIntent(value) {
  const errors = []
  if (!value || typeof value !== 'object' || Array.isArray(value)) return { valid: false, errors: ['intent must be an object'] }
  for (const key of INTENT_SCHEMA.required) {
    if (value[key] === undefined || value[key] === null || value[key] === '') errors.push(`missing:${key}`)
  }
  for (const key of Object.keys(value)) {
    if (!allowedIntentKeys.has(key)) errors.push(`unknown:${key}`)
  }
  if (typeof value.intent !== 'string' || value.intent.length < 3) errors.push('invalid:intent')
  if (typeof value.audience !== 'string' || value.audience.length < 3) errors.push('invalid:audience')
  if (!Array.isArray(value.channels) || value.channels.length < 1 || value.channels.some((channel) => typeof channel !== 'string' || !channel.trim())) errors.push('invalid:channels')
  if (!Number.isInteger(value.contentCount) || value.contentCount < 1 || value.contentCount > 50) errors.push('invalid:contentCount')
  if (typeof value.tone !== 'string' || value.tone.length < 2) errors.push('invalid:tone')
  return { valid: errors.length === 0, errors }
}

export function assertValidIntent(value) {
  const result = validateIntent(value)
  if (!result.valid) {
    const error = new Error(`INTENT_SCHEMA_INVALID:${result.errors.join(',')}`)
    error.code = 'INTENT_SCHEMA_INVALID'
    error.details = result.errors
    throw error
  }
  return value
}

const angles = ['真实卖点', '到店场景', '人群共鸣', '产品细节', '周末仪式感', '轻量行动理由']

export function createCreativePlan(intent, facts = []) {
  assertValidIntent(intent)
  const factIds = createFactSnapshot(facts).map((fact) => fact.id)
  const matrix = Array.from({ length: intent.contentCount }, (_, index) => ({
    id: `creative_unit_${index + 1}`,
    index,
    channel: intent.channels[index % intent.channels.length],
    angle: angles[index % angles.length],
    hook: `${intent.audience} × ${angles[index % angles.length]}`,
    factualSourceIds: factIds,
    platformRatio: intent.channels[index % intent.channels.length] === '抖音' ? '9:16' : '3:4',
    lockedConstraints: intent.constraints || [],
  }))
  return {
    version: 'creative-plan.v1',
    intent: intent.intent,
    audience: intent.audience,
    channels: intent.channels,
    contentCount: intent.contentCount,
    tone: intent.tone,
    constraints: intent.constraints || [],
    matrix,
    estimatedCredits: matrix.length * 8,
    estimatedSeconds: matrix.length * 12,
    factualSourceIds: factIds,
  }
}

export function compilePromptArtifacts(plan, brief) {
  return plan.matrix.map((unit) => ({
    id: `prompt_${unit.id}`,
    planUnitId: unit.id,
    templateVersion: 'image-prompt.v1',
    modelAlias: 'IMAGE_PRIMARY',
    prompt: [
      `目标：${plan.intent}`,
      `受众：${plan.audience}`,
      `场景：${unit.angle}`,
      `渠道：${unit.channel}，比例 ${unit.platformRatio}`,
      `语气：${plan.tone}`,
      `原始需求：${brief}`,
      plan.constraints.length ? `锁定约束：${plan.constraints.join('、')}` : '',
      '只使用已确认的商品事实，不改变产品外观，不编造价格与地址。',
    ].filter(Boolean).join('\n'),
    factualSourceIds: unit.factualSourceIds,
    reviewed: false,
  }))
}

export function reviewPromptArtifacts(artifacts, facts = []) {
  const factValues = new Set(createFactSnapshot(facts).map((fact) => String(fact.value)))
  const reports = artifacts.map((artifact) => {
    const issues = []
    if (artifact.prompt.length > 1800) issues.push({ code: 'PROMPT_TOO_LONG', severity: 'WARNING', message: 'Prompt 超过建议长度' })
    if (artifact.factualSourceIds.some((id) => !id)) issues.push({ code: 'FACT_SOURCE_MISSING', severity: 'BLOCKING', message: '存在没有来源的事实' })
    return {
      artifactId: artifact.id,
      score: issues.length ? 0.78 : 0.96,
      issues,
      blocking: issues.some((issue) => issue.severity === 'BLOCKING'),
      checkedAt: new Date().toISOString(),
      factValueCount: factValues.size,
    }
  })
  return {
    version: 'qa-report.v1',
    checkedCount: reports.length,
    blockingIssues: reports.filter((report) => report.blocking).length,
    warningCount: reports.reduce((count, report) => count + report.issues.filter((issue) => issue.severity === 'WARNING').length, 0),
    reports,
  }
}

export function createCreativeCompiler({ providers } = {}) {
  if (!providers?.text) throw new Error('TEXT_PROVIDER_REQUIRED')
  return {
    async compile({ brief, facts = [], requiredFacts = [], ...options }) {
      const providerResult = await providers.text.generate({ brief, ...options, modelAlias: 'TEXT_PLANNER' })
      const intent = assertValidIntent(providerResult.output.json)
      const snapshot = createFactSnapshot(facts)
      const presentFields = new Set(snapshot.filter((fact) => fact.confirmed).map((fact) => fact.field))
      const missingFacts = [...new Set([...(intent.missingFacts || []), ...requiredFacts.filter((field) => !presentFields.has(field))])]
      if (missingFacts.length) {
        return { blocked: true, reason: 'MISSING_CONFIRMED_FACTS', missingFacts, intent, providerResult, factSnapshot: snapshot }
      }
      const plan = createCreativePlan({ ...intent, missingFacts: [] }, snapshot)
      const promptArtifacts = compilePromptArtifacts(plan, brief)
      const qaReport = reviewPromptArtifacts(promptArtifacts, snapshot)
      return { blocked: qaReport.blockingIssues > 0, reason: qaReport.blockingIssues ? 'QA_BLOCKED' : null, missingFacts: [], intent, plan, promptArtifacts, qaReport, providerResult, factSnapshot: snapshot }
    },
  }
}
