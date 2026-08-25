import { PROVIDER_CAPABILITIES } from './creative.js'

export function providerError(code, message, retryable = false) {
  const error = new Error(message)
  error.code = code
  error.retryable = retryable
  return error
}

function result(base, output = {}) {
  return {
    provider: base.provider,
    modelAlias: base.modelAlias,
    providerModel: base.providerModel,
    requestId: base.requestId,
    taskId: base.taskId || null,
    status: base.status || 'SUCCEEDED',
    usage: base.usage || { inputTokens: 0, outputTokens: 0 },
    estimatedCost: base.estimatedCost || 0,
    actualCost: base.actualCost || 0,
    errorCode: null,
    retryable: false,
    output,
    rawAuditRef: base.rawAuditRef || `audit_${base.requestId}`,
  }
}

export function createMockTextProvider({ provider = 'mock-flu', latency = 45 } = {}) {
  return {
    name: provider,
    capability: PROVIDER_CAPABILITIES.API_AUTO,
    async generate(request) {
      await new Promise((resolve) => setTimeout(resolve, latency))
      const brief = request.brief || '商家增长内容'
      return result({ provider, modelAlias: request.modelAlias || 'TEXT_PLANNER', providerModel: 'mock-text-v1', requestId: `req_${crypto.randomUUID()}`, estimatedCost: 2, actualCost: 2 }, {
        text: `已理解你的目标：${brief}。建议围绕真实商品事实、到店理由与平台语气规划内容。`,
        json: {
          intent: 'LOCAL_GROWTH_CAMPAIGN',
          audience: '年轻情侣与周末到店人群',
          channels: request.channels || ['小红书', '抖音'],
          contentCount: request.contentCount || 6,
          tone: request.tone || '温暖高级',
          missingFacts: request.missingFacts || [],
        },
      })
    },
  }
}

export function createMockImageProvider({ provider = 'mock-flu-image', latency = 70 } = {}) {
  const tasks = new Map()
  return {
    name: provider,
    capability: PROVIDER_CAPABILITIES.USER_CONFIRM,
    async create(request) {
      const taskId = `pt_${crypto.randomUUID()}`
      tasks.set(taskId, { status: 'QUEUED', createdAt: Date.now(), request })
      return result({ provider, modelAlias: 'IMAGE_PRIMARY', providerModel: 'mock-image-2', requestId: `req_${crypto.randomUUID()}`, taskId, status: 'QUEUED', estimatedCost: request.estimatedCost || 8 }, { taskId })
    },
    async getStatus(providerTaskId) {
      const task = tasks.get(providerTaskId)
      if (!task) throw providerError('PROVIDER_TASK_NOT_FOUND', 'Provider task was not found')
      const done = Date.now() - task.createdAt > latency
      task.status = done ? 'SUCCEEDED' : 'RUNNING'
      return { provider, providerTaskId, status: task.status, output: done ? { assetId: `asset_${providerTaskId}`, url: '/mock-assets/creative-placeholder.webp' } : null }
    },
    async cancel(providerTaskId) {
      const task = tasks.get(providerTaskId)
      if (!task) throw providerError('PROVIDER_TASK_NOT_FOUND', 'Provider task was not found')
      task.status = 'CANCELLED'
      return { provider, providerTaskId, status: 'CANCELLED' }
    },
  }
}

export function createProviderRegistry(overrides = {}) {
  return {
    text: overrides.text || createMockTextProvider(),
    image: overrides.image || createMockImageProvider(),
  }
}
