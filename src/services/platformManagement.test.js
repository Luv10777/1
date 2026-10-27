import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'

test('platform controls send authenticated requests and identity values are only sent in POST bodies', async () => {
  const vite = await createServer({ server: { middlewareMode: true, hmr: false }, optimizeDeps: { noDiscovery: true, include: [] }, appType: 'custom' })
  const originalFetch = globalThis.fetch, originalStorage = globalThis.localStorage
  globalThis.localStorage = { getItem: key => key === 'wuyao-ai-token' ? JSON.stringify({ accessToken: 'test-platform-token' }) : null }
  const calls = []
  let data = { id: 2, status: 'PENDING_REVIEW' }
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    return { ok: true, status: 200, json: async () => ({ code: 200, data }) }
  }
  try {
    const { platformManagementApi: api } = await vite.ssrLoadModule('/src/services/platformManagement.js')
    const target = { tenantId: 3, id: 8, updatedAt: '2026-10-18T08:00:00Z' }
    await api.information({ page: 1, size: 20, keyword: '合作', status: '', category: undefined })
    assert.ok(!calls[0].url.includes('status='))
    assert.ok(!calls[0].url.includes('undefined'))
    await api.informationStatus(target, { status: 'PUBLISHED', note: '已核对提交内容', updatedAt: target.updatedAt })
    assert.deepEqual(JSON.parse(calls[1].options.body), { status: 'PUBLISHED', note: '已核对提交内容', updatedAt: target.updatedAt })
    await api.merchantReview(target, { status: 'APPROVED', note: '已核对经营主体' })
    await api.revealIdentity(target, '核对本人实名申请')
    await api.identityReview(target, { status: 'REJECTED', note: '需要补充核验资料' })
    await api.audit(target, 'IDENTITY')
    const values = { legalName: '测试用户', idNumber: '11010519491231002X', declarationAccepted: true }
    await api.submitIdentity(values)
    assert.deepEqual(JSON.parse(calls.at(-1).options.body), values)
    await api.revealMerchant(target, '核对经营主体及联系手机')
    assert.equal(calls.at(-1).url, '/api/platform/tenants/3/merchants/8/reveal')
    assert.equal(calls.at(-1).options.method, 'POST')
    assert.deepEqual(calls.slice(1, 6).map(call => [call.url, call.options.method]), [
      ['/api/platform/tenants/3/information/8/status', 'POST'],
      ['/api/platform/tenants/3/merchants/8/review', 'POST'],
      ['/api/platform/tenants/3/identities/8/reveal', 'POST'],
      ['/api/platform/tenants/3/identities/8/review', 'POST'],
      ['/api/platform/tenants/3/audit/IDENTITY/8', 'GET'],
    ])
    await api.users({ page: 0, size: 20, keyword: '林', status: '', role: 'STAFF' })
    assert.equal(calls.at(-1).url, '/api/platform/users?page=0&size=20&keyword=%E6%9E%97&role=STAFF')
    await api.userSummary()
    assert.equal(calls.at(-1).url, '/api/platform/users/summary')
    await api.userSuspension({ id: 9, tenantId: 3 }, { suspended: true, note: '多次发布违规信息' })
    assert.equal(calls.at(-1).url, '/api/platform/users/9/suspension')
    assert.equal(calls.at(-1).options.method, 'POST')
    assert.deepEqual(JSON.parse(calls.at(-1).options.body), { suspended: true, note: '多次发布违规信息' })
    const grant = { granted: true, planId: 4, requestKey: 'platform-key-0001', note: '线下签约赠送会员' }
    await api.userMembership({ id: 9, tenantId: 3 }, grant)
    assert.deepEqual([calls.at(-1).url, calls.at(-1).options.method], ['/api/platform/users/9/membership', 'POST'])
    assert.deepEqual(JSON.parse(calls.at(-1).options.body), grant)
    await api.userCredits({ id: 9, tenantId: 3 }, { credits: 500, requestKey: 'platform-key-0002', note: '活动奖励赠送' })
    assert.deepEqual([calls.at(-1).url, calls.at(-1).options.method], ['/api/platform/users/9/credits', 'POST'])
    assert.equal(JSON.parse(calls.at(-1).options.body).credits, 500)
    for (const call of calls) {
      assert.equal(call.options.headers.Authorization, 'Bearer test-platform-token')
      assert.ok(!call.url.includes(values.idNumber))
    }
    for (data of [null, {}, { id: 2, status: 'VERIFIED' }]) await assert.rejects(api.submitIdentity(values), /回执/)
    globalThis.fetch = async () => ({ ok: false, status: 403, json: async () => ({ code: 1403, message: '没有平台运营权限' }) })
    await assert.rejects(api.information({}), /没有平台运营权限/)
  } finally {
    globalThis.fetch = originalFetch
    if (originalStorage === undefined) delete globalThis.localStorage
    else globalThis.localStorage = originalStorage
    await vite.close()
  }
})
