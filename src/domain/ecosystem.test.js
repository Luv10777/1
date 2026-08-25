import test from 'node:test'
import assert from 'node:assert/strict'
import { API_SCOPES, AUTONOMY_LEVELS, ORG_NODE_TYPES, POLICY_MODES, authorizeScope, canAccessOrganizationNode, createBenchmarkSnapshot, createOAuthApplication, createOrganizationNode, createPolicy, createTemplatePackage, decideAgentToolCall, installTemplate, issueAccessToken, resolveEffectivePolicy, revokeAccessToken } from './ecosystem.js'

const hq = createOrganizationNode({ id: 'org_hq', tenantId: 'tenant_chain', type: ORG_NODE_TYPES.HEADQUARTERS, name: '总部' })
const region = createOrganizationNode({ id: 'org_region', tenantId: 'tenant_chain', parentId: hq.id, type: ORG_NODE_TYPES.REGION, name: '华东区' })
const store = createOrganizationNode({ id: 'org_store', tenantId: 'tenant_chain', parentId: region.id, type: ORG_NODE_TYPES.STORE, name: '城西店' })

test('organization policy resolves inheritance and ABAC keeps store boundaries', () => {
  const policies = [
    createPolicy({ tenantId: 'tenant_chain', nodeId: hq.id, key: 'brand.tone', value: '统一温暖', mode: POLICY_MODES.LOCKED }),
    createPolicy({ tenantId: 'tenant_chain', nodeId: region.id, key: 'campaign.local_fields', value: ['address', 'opening_hours'], mode: POLICY_MODES.OVERRIDABLE }),
  ]
  const result = resolveEffectivePolicy(store.id, [hq, region, store], policies)
  assert.deepEqual(result.chain, [hq.id, region.id, store.id])
  assert.equal(result.values['brand.tone'].value, '统一温暖')
  assert.equal(canAccessOrganizationNode({ tenantId: 'tenant_chain', role: 'STORE_MANAGER', storeId: store.id }, store, 'write'), false)
  assert.equal(canAccessOrganizationNode({ tenantId: 'tenant_chain', role: 'HEADQUARTERS_ADMIN' }, region, 'approve'), true)
  assert.equal(canAccessOrganizationNode({ tenantId: 'other_tenant', role: 'PLATFORM_SUPER_ADMIN' }, store), false)
})

test('sandbox OAuth only issues requested scopes and revocation is immediate', () => {
  const app = createOAuthApplication({ id: 'app_demo', developerId: 'dev_1', name: '沙箱应用', environment: 'SANDBOX', requestedScopes: ['store.read', 'analytics.read'] })
  const token = issueAccessToken(app, { tenantId: 'tenant_chain', scopes: ['store.read'] })
  assert.equal(authorizeScope(token, 'store.read'), true)
  assert.equal(authorizeScope(token, 'campaign.write'), false)
  assert.equal(authorizeScope(revokeAccessToken(token), 'store.read'), false)
  assert.throws(() => issueAccessToken(app, { scopes: ['customer.read_limited'] }), /OAUTH_SCOPE_NOT_ALLOWED/)
  assert.ok(API_SCOPES.includes('analytics.read'))
})

test('template review blocks external URLs and installations snapshot the version', () => {
  const version = createTemplatePackage({ id: 'template_1', name: '门店周报', version: '1.0.0', nodes: [{ type: 'INPUT' }, { type: 'METRIC' }, { type: 'OUTPUT' }], budget: { maxSteps: 5 } })
  const installation = installTemplate(version, { tenantId: 'tenant_chain', config: { region: '华东区' } })
  assert.equal(installation.version, '1.0.0')
  assert.throws(() => createTemplatePackage({ id: 'template_bad', name: '坏模板', version: '1.0.0', nodes: [{ type: 'PROMPT', text: 'https://evil.test' }] }), /TEMPLATE_EXTERNAL_URL_BLOCKED/)
})

test('benchmark enforces minimum cohort and agent levels require approval', () => {
  assert.throws(() => createBenchmarkSnapshot({ metric: 'conversion', participantCount: 4 }), /BENCHMARK_GROUP_TOO_SMALL/)
  assert.equal(createBenchmarkSnapshot({ metric: 'conversion', participantCount: 8, aggregate: { p50: 0.12 } }).estimated, true)
  assert.equal(decideAgentToolCall({ level: AUTONOMY_LEVELS.L1, tool: 'campaign.publish', policy: {} }).decision, 'REQUIRES_APPROVAL')
  assert.equal(decideAgentToolCall({ level: AUTONOMY_LEVELS.L2, tool: 'campaign.write', policy: {}, approved: true }).allowed, true)
  assert.equal(decideAgentToolCall({ level: AUTONOMY_LEVELS.L4, tool: 'campaign.write', policy: {}, approved: true }).reasonCode, 'L4_DISABLED')
})
