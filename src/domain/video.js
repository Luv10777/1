export function createVideoBrief(input = {}) {
  const brief = {
    id: input.id || `vbrief_${crypto.randomUUID()}`,
    tenantId: input.tenantId || 'tenant_demo_001',
    campaignId: input.campaignId || null,
    durationSeconds: input.durationSeconds || 15,
    channel: input.channel || '抖音',
    audience: input.audience || '本地年轻消费者',
    productFacts: input.productFacts || [],
    characters: input.characters || [{ id: 'host', description: '年轻女主持，轻松自然', locked: true }],
    scenes: input.scenes || [],
    constraints: input.constraints || [],
  }
  if (!Number.isInteger(brief.durationSeconds) || brief.durationSeconds < 5 || brief.durationSeconds > 180) throw new Error('VIDEO_DURATION_INVALID')
  return brief
}

export function createStoryboard(brief) {
  const sourceScenes = brief.scenes.length ? brief.scenes : [
    { purpose: 'HOOK', seconds: 3, action: '主持人提出到店理由', setting: '门店入口' },
    { purpose: 'PRODUCT', seconds: 6, action: '展示商品与真实卖点', setting: '桌面近景' },
    { purpose: 'PROOF', seconds: 3, action: '呈现真实价格或门店环境', setting: '门店环境' },
    { purpose: 'CTA', seconds: Math.max(2, brief.durationSeconds - 12), action: '邀请周末到店', setting: '门店外景' },
  ]
  return sourceScenes.map((scene, index) => ({
    id: scene.id || `shot_${index + 1}`,
    index,
    purpose: scene.purpose,
    seconds: scene.seconds,
    setting: scene.setting,
    action: scene.action,
    characterIds: brief.characters.map((character) => character.id),
    locked: true,
    prompt: `镜头 ${index + 1}：${scene.action}；场景：${scene.setting}；保持角色、商品和门店事实一致。`,
  }))
}

export function createVideoTaskSpec(brief) {
  const storyboard = createStoryboard(brief)
  return {
    version: 'video-spec.v1',
    briefId: brief.id,
    modelAlias: 'VIDEO_PRIMARY',
    durationSeconds: brief.durationSeconds,
    channel: brief.channel,
    lockedCharacters: brief.characters.filter((character) => character.locked).map((character) => character.id),
    lockedConstraints: brief.constraints,
    storyboard,
    prompt: storyboard.map((shot) => shot.prompt).join('\n'),
    estimatedCredits: storyboard.length * 18,
  }
}

export function runVideoConsistencyQa({ spec, output = {} } = {}) {
  const issues = []
  if (!output.assetId) issues.push({ code: 'VIDEO_ASSET_MISSING', severity: 'BLOCKING' })
  if (output.durationSeconds && output.durationSeconds !== spec.durationSeconds) issues.push({ code: 'DURATION_MISMATCH', severity: 'BLOCKING' })
  if (output.characterIds && spec.lockedCharacters.some((id) => !output.characterIds.includes(id))) issues.push({ code: 'CHARACTER_DRIFT', severity: 'BLOCKING' })
  return { version: 'video-qa.v1', status: issues.length ? 'BLOCKED' : 'PASSED', blocking: issues.length > 0, issues, checkedAt: new Date().toISOString() }
}

export function createMockVideoProvider({ latency = 20 } = {}) {
  const tasks = new Map()
  return {
    name: 'mock-toapis-video',
    capability: 'USER_CONFIRM',
    async create(spec) {
      const taskId = `vtask_${crypto.randomUUID()}`
      tasks.set(taskId, { spec, startedAt: Date.now(), status: 'QUEUED' })
      return { provider: 'mock-toapis-video', modelAlias: spec.modelAlias, taskId, status: 'QUEUED', estimatedCost: spec.estimatedCredits }
    },
    async getStatus(taskId) {
      const task = tasks.get(taskId)
      if (!task) throw new Error('VIDEO_TASK_NOT_FOUND')
      if (Date.now() - task.startedAt >= latency) task.status = 'SUCCEEDED'
      return { taskId, status: task.status, output: task.status === 'SUCCEEDED' ? { assetId: `asset_${taskId}`, durationSeconds: task.spec.durationSeconds, characterIds: task.spec.lockedCharacters } : null }
    },
  }
}
