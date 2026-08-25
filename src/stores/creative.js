import { reactive } from 'vue'
import { createProviderRegistry } from '../domain/providers.js'
import { createWorkflowOrchestrator } from '../domain/orchestrator.js'

const orchestrator = createWorkflowOrchestrator({ providers: createProviderRegistry() })

export const creative = reactive({
  brief: '',
  campaign: null,
  batch: null,
  items: [],
  loading: false,
  phase: 'idle',
  error: '',
  lastProviderResult: null,
  promptArtifacts: [],
  qaReport: null,
  async interpret(brief, options = {}) {
    this.loading = true
    this.error = ''
    this.phase = 'interpreting'
    this.brief = brief
    try {
      this.campaign = orchestrator.createCampaign({
        brief,
        tenantId: 'tenant_demo_001',
        merchantId: 'merchant_demo_001',
        storeId: 'store_demo_001',
        brandId: 'brand_demo_001',
      })
      const result = await orchestrator.interpret(this.campaign.id, {
        ...options,
        contentCount: options.contentCount || 6,
        facts: options.facts || [
          { field: 'merchant', value: '青岚茶事', source: 'merchant_context', confirmed: true },
          { field: 'offer', value: '29.9 元双人下午茶', source: 'merchant_context', confirmed: true },
        ],
      })
      this.campaign = result.campaign
      this.lastProviderResult = result.providerResult
      this.promptArtifacts = result.promptArtifacts || []
      this.qaReport = result.qaReport || null
      this.phase = result.blocked ? 'blocked' : 'planned'
      return result
    } catch (error) {
      this.error = error.message
      this.phase = 'error'
      throw error
    } finally {
      this.loading = false
    }
  },
  confirm(count = this.campaign?.plan?.contentCount || 6) {
    if (!this.campaign) throw new Error('CAMPAIGN_REQUIRED')
    const result = orchestrator.confirmCampaign(this.campaign.id, { count, kind: 'IMAGE', estimatedCredits: count * 8 })
    this.campaign = result.campaign
    this.batch = result.batch
    this.items = result.items
    this.phase = 'confirmed'
    return result
  },
  async start() {
    if (!this.batch) throw new Error('BATCH_REQUIRED')
    this.loading = true
    this.phase = 'running'
    this.error = ''
    try {
      const result = await orchestrator.startBatch(this.batch.id)
      this.batch = result.batch
      this.items = result.items
      this.phase = result.batch.failed > 0 ? 'needs_review' : 'qa'
      return result
    } catch (error) {
      this.error = error.message
      this.phase = 'error'
      throw error
    } finally {
      this.loading = false
    }
  },
  reset() {
    this.brief = ''; this.campaign = null; this.batch = null; this.items = []; this.loading = false; this.phase = 'idle'; this.error = ''; this.lastProviderResult = null; this.promptArtifacts = []; this.qaReport = null
  },
})
