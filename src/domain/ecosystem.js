/**
 * Phase 5 sandbox governance contracts.
 *
 * These functions are intentionally deterministic and in-memory. They model
 * the boundaries that a future server-side Organization, Open API, Template
 * Registry and Growth OS service must enforce.
 */

export const ORG_NODE_TYPES = Object.freeze({ HEADQUARTERS: 'HEADQUARTERS', BUSINESS_UNIT: 'BUSINESS_UNIT', REGION: 'REGION', FRANCHISEE: 'FRANCHISEE', STORE: 'STORE' })
export const POLICY_MODES = Object.freeze({ LOCKED: 'LOCKED', OVERRIDABLE: 'OVERRIDABLE', LOCAL_ONLY: 'LOCAL_ONLY' })
export const AUTONOMY_LEVELS = Object.freeze({ L0: 'L0', L1: 'L1', L2: 'L2', L3: 'L3', L4: 'L4' })
export const API_SCOPES = Object.freeze(['merchant.read', 'store.read', 'brand.read', 'campaign.read', 'campaign.write', 'workflow.run', 'work.read', 'review.read', 'analytics.read', 'webhook.manage'])
export const HIGH_RISK_TOOLS = Object.freeze(new Set(['payment.create', 'refund.create', 'credential.rotate', 'data.delete', 'campaign.publish', 'message.broadcast']))
const DECLARED_TEMPLATE_NODES = new Set(['INPUT', 'PROMPT', 'MODEL', 'QA', 'APPROVAL', 'OUTPUT', 'METRIC', 'STOP'])

function id(prefix) { return `${prefix}_${crypto.randomUUID()}` }
function now() { return new Date().toISOString() }

export function createOrganizationNode(input = {}) {
  if (!input.tenantId || !input.name || !input.type) throw new Error('ORG_NODE_REQUIRED')
  return { id: input.id || id('org'), tenantId: input.tenantId, parentId: input.parentId || null, type: input.type, name: input.name, path: input.path || '/', status: input.status || 'ACTIVE', createdAt: now() }
}

export function createPolicy(input = {}) {
  if (!input.tenantId || !input.key || !input.value || !input.mode) throw new Error('POLICY_REQUIRED')
  return { id: input.id || id('policy'), tenantId: input.tenantId, nodeId: input.nodeId || null, key: input.key, value: input.value, mode: input.mode, version: input.version || 1, reason: input.reason || null, createdAt: now() }
}

export function resolveEffectivePolicy(nodeId, nodes = [], policies = []) {
  const nodeMap = new Map(nodes.map((node) => [node.id, node]))
  const chain = []
  let current = nodeMap.get(nodeId)
  while (current) { chain.unshift(current); current = nodeMap.get(current.parentId) }
  const result = {}
  const sources = {}
  for (const node of chain) {
    for (const policy of policies.filter((item) => item.nodeId === node.id || item.nodeId === null)) {
      if (sources[policy.key] && result[policy.key]?.mode === POLICY_MODES.LOCKED) continue
      if (policy.mode !== POLICY_MODES.LOCAL_ONLY || policy.nodeId === node.id) {
        result[policy.key] = { value: policy.value, mode: policy.mode, version: policy.version }
        sources[policy.key] = { nodeId: node.id, policyId: policy.id }
      }
    }
  }
  return { nodeId, chain: chain.map((node) => node.id), values: result, sources }
}

export function canAccessOrganizationNode(subject = {}, targetNode, action = 'read') {
  if (!subject.tenantId || !targetNode || subject.tenantId !== targetNode.tenantId) return false
  if (subject.role === 'PLATFORM_SUPER_ADMIN') return true
  if (subject.role === 'HEADQUARTERS_ADMIN') return ['read', 'write', 'approve'].includes(action)
  if (subject.role === 'REGION_MANAGER') return subject.allowedNodeIds?.includes(targetNode.id) || targetNode.type === ORG_NODE_TYPES.STORE && subject.allowedStoreIds?.includes(targetNode.id)
  if (subject.role === 'STORE_MANAGER') return action === 'read' && targetNode.type === ORG_NODE_TYPES.STORE && targetNode.id === subject.storeId
  return action === 'read' && targetNode.id === subject.nodeId
}

export function createOAuthApplication(input = {}) {
  if (!input.developerId || !input.name || !input.environment) throw new Error('OAUTH_APPLICATION_REQUIRED')
  return { id: input.id || id('app'), developerId: input.developerId, tenantId: input.tenantId || null, name: input.name, environment: input.environment, redirectUris: input.redirectUris || [], requestedScopes: input.requestedScopes || [], status: input.status || 'SANDBOX', createdAt: now() }
}

export function issueAccessToken(app, grant = {}) {
  const scopes = [...new Set(grant.scopes || [])]
  if (!scopes.length || scopes.some((scope) => !API_SCOPES.includes(scope) || !app.requestedScopes.includes(scope))) throw new Error('OAUTH_SCOPE_NOT_ALLOWED')
  return { id: id('token_family'), appId: app.id, tenantId: grant.tenantId || app.tenantId, scopes, token: `sandbox_${crypto.randomUUID()}`, expiresAt: new Date(Date.now() + (grant.ttlMs || 15 * 60 * 1000)).toISOString(), revokedAt: null, createdAt: now() }
}

export function revokeAccessToken(token) {
  if (!token || token.revokedAt) return token
  return { ...token, revokedAt: now() }
}

export function authorizeScope(token, scope) {
  if (!token || token.revokedAt || new Date(token.expiresAt) <= new Date()) return false
  return token.scopes.includes(scope)
}

export function createTemplatePackage(input = {}) {
  if (!input.id || !input.name || !input.version || !Array.isArray(input.nodes)) throw new Error('TEMPLATE_PACKAGE_REQUIRED')
  if (input.nodes.some((node) => !DECLARED_TEMPLATE_NODES.has(node.type))) throw new Error('TEMPLATE_NODE_NOT_ALLOWED')
  if (input.nodes.some((node) => JSON.stringify(node).match(/https?:\/\//i))) throw new Error('TEMPLATE_EXTERNAL_URL_BLOCKED')
  if (input.budget?.maxSteps && input.nodes.length > input.budget.maxSteps) throw new Error('TEMPLATE_BUDGET_EXCEEDED')
  return { id: input.id, name: input.name, version: input.version, visibility: input.visibility || 'PRIVATE', license: input.license || 'INTERNAL', nodes: input.nodes.map((node) => ({ ...node })), requiredScopes: input.requiredScopes || [], budget: input.budget || { maxSteps: 20 }, status: 'REVIEW_REQUIRED', createdAt: now() }
}

export function installTemplate(packageVersion, input = {}) {
  if (!packageVersion || packageVersion.status === 'BLOCKED') throw new Error('TEMPLATE_NOT_INSTALLABLE')
  return { id: id('template_installation'), tenantId: input.tenantId, templateId: packageVersion.id, version: packageVersion.version, configSnapshot: input.config || {}, installedAt: now(), status: 'ACTIVE' }
}

export function createBenchmarkSnapshot(input = {}) {
  if (!input.metric || !Number.isInteger(input.participantCount) || input.participantCount < (input.minimumGroupSize || 5)) throw new Error('BENCHMARK_GROUP_TOO_SMALL')
  return { id: id('benchmark'), metric: input.metric, cohort: input.cohort || 'ALL', participantCount: input.participantCount, aggregate: input.aggregate, kAnonymity: input.minimumGroupSize || 5, estimated: true, createdAt: now() }
}

export function decideAgentToolCall(input = {}) {
  const level = input.level || AUTONOMY_LEVELS.L0
  const tool = input.tool
  if (!tool || !input.policy) throw new Error('AGENT_POLICY_INPUT_REQUIRED')
  if (level === AUTONOMY_LEVELS.L0) return { allowed: false, decision: 'DRAFT_ONLY', reasonCode: 'L0_NO_ACTION' }
  if (level === AUTONOMY_LEVELS.L1 && HIGH_RISK_TOOLS.has(tool)) return { allowed: false, decision: 'REQUIRES_APPROVAL', reasonCode: 'HIGH_RISK_TOOL' }
  if (level === AUTONOMY_LEVELS.L2 && (!input.approved || HIGH_RISK_TOOLS.has(tool))) return { allowed: false, decision: 'REQUIRES_APPROVAL', reasonCode: input.approved ? 'HIGH_RISK_TOOL' : 'APPROVAL_REQUIRED' }
  if (level === AUTONOMY_LEVELS.L3 && HIGH_RISK_TOOLS.has(tool)) return { allowed: false, decision: 'POLICY_BLOCKED', reasonCode: 'L3_HIGH_RISK_DISABLED' }
  if (level === AUTONOMY_LEVELS.L4) return { allowed: false, decision: 'POLICY_BLOCKED', reasonCode: 'L4_DISABLED' }
  return { allowed: true, decision: 'EXECUTE', reasonCode: 'POLICY_ALLOWED' }
}
