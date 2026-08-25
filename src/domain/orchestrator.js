import { createCreativeCompiler } from './compiler.js'
import { createMockObjectStorage, runVisualQa } from './media.js'
import { createBatch, createBatchItem, createCampaign, createCostLedgerEntry, createFactSnapshot, createIdempotencyKey, createStep, STEP_STATES, WORKFLOW_STATES, transition } from './creative.js'

export function createWorkflowOrchestrator({ providers, now = () => new Date().toISOString() } = {}) {
  if (!providers?.text || !providers?.image) throw new Error('PROVIDER_REGISTRY_REQUIRED')
  const campaigns = new Map()
  const batches = new Map()
  const items = new Map()
  const steps = new Map()
  const costs = []
  const idempotency = new Map()
  const events = []
  const compiler = createCreativeCompiler({ providers })
  const storage = providers.storage || createMockObjectStorage()

  const record = (type, payload) => events.push({ id: `evt_${crypto.randomUUID()}`, type, payload, createdAt: now() })
  const reserve = (campaign, batch) => {
    const key = createIdempotencyKey('credit-reservation', batch.id)
    if (idempotency.has(key)) return idempotency.get(key)
    const entry = createCostLedgerEntry({ tenantId: campaign.tenantId, workflowRunId: batch.id, provider: 'platform', modelAlias: 'WORKFLOW', kind: 'RESERVATION', estimatedCredits: batch.estimatedCredits, status: 'RESERVED' })
    costs.push(entry); idempotency.set(key, entry); batch.reservedCredits = batch.estimatedCredits
    record('CREDITS_RESERVED', { batchId: batch.id, credits: entry.estimatedCredits })
    return entry
  }

  return {
    events,
    costs,
    createCampaign(input) {
      const campaign = createCampaign(input)
      campaigns.set(campaign.id, campaign)
      record('CAMPAIGN_CREATED', { campaignId: campaign.id, tenantId: campaign.tenantId })
      return campaign
    },
    async interpret(campaignId, input = {}) {
      const campaign = campaigns.get(campaignId)
      if (!campaign) throw new Error('CAMPAIGN_NOT_FOUND')
      const result = await compiler.compile({ ...input, brief: campaign.brief, requiredFacts: input.requiredFacts || [] })
      campaign.facts = result.factSnapshot
      campaign.missingFacts = result.missingFacts
      campaign.plan = result.plan || null
      campaign.promptArtifacts = result.promptArtifacts || []
      campaign.qaReport = result.qaReport || null
      campaign.updatedAt = now()
      if (result.blocked) {
        record('CAMPAIGN_BLOCKED', { campaignId, reason: result.reason, missingFacts: result.missingFacts })
        return { campaign, blocked: true, ...result }
      }
      campaign.state = transition(campaign.state, WORKFLOW_STATES.PLANNED)
      record('CAMPAIGN_PLANNED', { campaignId, requestId: result.providerResult.requestId })
      return { campaign, blocked: false, ...result }
    },
    confirmCampaign(campaignId, input = {}) {
      const campaign = campaigns.get(campaignId)
      if (!campaign) throw new Error('CAMPAIGN_NOT_FOUND')
      if (campaign.state !== WORKFLOW_STATES.PLANNED) throw new Error('CAMPAIGN_PLAN_REQUIRED')
      if (campaign.missingFacts?.length) throw new Error('CONFIRMED_FACTS_REQUIRED')
      if (campaign.qaReport?.blockingIssues) throw new Error('QA_BLOCKED')
      campaign.state = transition(campaign.state, WORKFLOW_STATES.CONFIRMED)
      const batch = createBatch(campaign, { count: input.count || campaign.plan?.contentCount || 1, estimatedCredits: input.estimatedCredits || (input.count || campaign.plan?.contentCount || 1) * 8 })
      batch.state = transition(batch.state, WORKFLOW_STATES.CONFIRMED)
      batches.set(batch.id, batch)
      for (let index = 0; index < batch.count; index += 1) {
        const item = createBatchItem(batch, { index, kind: input.kind || 'IMAGE' })
        const step = createStep(item, 'IMAGE_PROMPT_COMPILER', { idempotencyKey: createIdempotencyKey(item.id, 'prompt') })
        items.set(item.id, item); steps.set(step.id, step)
      }
      record('CAMPAIGN_CONFIRMED', { campaignId, batchId: batch.id, count: batch.count })
      return { campaign, batch, items: [...items.values()].filter((item) => item.batchId === batch.id) }
    },
    async startBatch(batchId) {
      const batch = batches.get(batchId)
      if (!batch) throw new Error('BATCH_NOT_FOUND')
      const campaign = campaigns.get(batch.campaignId)
      reserve(campaign, batch)
      if (batch.state === WORKFLOW_STATES.CONFIRMED) batch.state = transition(batch.state, WORKFLOW_STATES.QUEUED)
      batch.state = transition(batch.state, WORKFLOW_STATES.RUNNING)
      for (const item of items.values()) {
        if (item.batchId !== batchId || item.state === STEP_STATES.SUCCEEDED) continue
        item.state = STEP_STATES.RUNNING
        const step = [...steps.values()].find((candidate) => candidate.itemId === item.id)
        step.state = STEP_STATES.RUNNING; step.attempt += 1
        try {
          const generated = await providers.image.create({ brief: campaign.brief, index: item.index, estimatedCost: 8 })
          step.provider = generated.provider; step.providerTaskId = generated.taskId; step.state = STEP_STATES.SUCCEEDED; step.actualCost = generated.estimatedCost
          const assetId = generated.output.taskId || ('asset_' + item.id)
          await storage.put({ tenantId: campaign.tenantId, assetId, contentType: 'image/webp', size: 0 })
          item.qaReport = runVisualQa({ assetId, expectedRatio: item.kind === 'VIDEO' ? '9:16' : '3:4', actualRatio: item.kind === 'VIDEO' ? '9:16' : '3:4', hasProductReference: true })
          item.state = item.qaReport.blocking ? STEP_STATES.FAILED : STEP_STATES.SUCCEEDED; item.outputAssetId = assetId; batch.completed += item.qaReport.blocking ? 0 : 1; batch.failed += item.qaReport.blocking ? 1 : 0
          costs.push(createCostLedgerEntry({ tenantId: campaign.tenantId, workflowRunId: batch.id, itemId: item.id, provider: generated.provider, modelAlias: generated.modelAlias, kind: item.kind, estimatedCredits: generated.estimatedCost, actualCredits: generated.actualCost, status: 'CAPTURED' }))
        } catch (error) {
          step.state = STEP_STATES.FAILED; item.state = STEP_STATES.FAILED; item.errorCode = error.code || 'PROVIDER_ERROR'; batch.failed += 1
        }
        record('BATCH_ITEM_UPDATED', { batchId, itemId: item.id, state: item.state })
      }
      batch.state = batch.failed > 0 && batch.completed === 0 ? WORKFLOW_STATES.FAILED : WORKFLOW_STATES.QA
      record('BATCH_COMPLETED', { batchId, completed: batch.completed, failed: batch.failed })
      return { batch, items: [...items.values()].filter((item) => item.batchId === batch.id), events: events.filter((event) => event.payload.batchId === batch.id) }
    },
    getCampaign(id) { return campaigns.get(id) },
    getBatch(id) { return batches.get(id) },
    listItems(batchId) { return [...items.values()].filter((item) => item.batchId === batchId) },
    listEvents(id) { return events.filter((event) => event.payload.campaignId === id || event.payload.batchId === id || event.payload.itemId === id) },
  }
}
