import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'

test('business applications use authenticated requests and require a review receipt', async t => {
  const vite = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  const originalStorage = globalThis.localStorage
  globalThis.localStorage = { getItem: key => key === 'wuyao-ai-token' ? JSON.stringify({ accessToken: 'business-test-token' }) : null }
  const response = (status, data, code = status, message = '') => ({ ok: status === 200, status, statusText: '', json: async () => ({ code, data, message }) })
  try {
    const { businessCommunityService } = await vite.ssrLoadModule('/src/services/businessCommunity.js')
    await t.test('information and enrollment send their payloads with the current token', async () => {
      const calls = []
      globalThis.fetch = async (url, options) => {
        calls.push({ url, options })
        return response(200, { id: 'application-1', status: 'PENDING_REVIEW' })
      }
      const values = { title: '合作信息', declarationAccepted: true }
      assert.deepEqual(await businessCommunityService.submitInformation(values), { id: 'application-1', status: 'PENDING_REVIEW' })
      await businessCommunityService.applyForAlliance({ companyName: '经营主体', declarationAccepted: true })
      assert.ok(calls[0].url.endsWith('/api/information-publications'))
      assert.ok(calls[1].url.endsWith('/api/merchant-alliance/applications'))
      for (const call of calls) {
        assert.equal(call.options.method, 'POST')
        assert.equal(call.options.headers.Authorization, 'Bearer business-test-token')
      }
      assert.deepEqual(JSON.parse(calls[0].options.body), values)
    })
    await t.test('public records and merchant controls use their connected API endpoints', async () => {
      const calls = []
      globalThis.fetch = async (url, options) => {
        calls.push({ url, options })
        return response(200, options.method === 'GET' ? { items: [] } : { id: 8, status: 'PENDING_REVIEW' })
      }
      await businessCommunityService.applications()
      await businessCommunityService.publications()
      await businessCommunityService.updatePublication(8, { title: '更新内容' })
      await businessCommunityService.takePublicationOffline(8)
      await businessCommunityService.publicFeed({ page: 0, size: 12, category: '', keyword: undefined })
      await businessCommunityService.publicInformation(8)
      assert.deepEqual(calls.map(call => [new URL(call.url, 'http://local').pathname, call.options.method]), [
        ['/api/merchant-alliance/applications', 'GET'],
        ['/api/information-publications', 'GET'],
        ['/api/information-publications/8', 'PUT'],
        ['/api/information-publications/8', 'DELETE'],
        ['/api/public/information', 'GET'],
        ['/api/public/information/8', 'GET'],
      ])
      assert.equal(JSON.parse(calls[4].options.body || '{}').keyword, undefined)
    })
    await t.test('missing endpoints never report successful submission', async () => {
      globalThis.fetch = async () => response(404, null, 1404, 'Not found')
      await assert.rejects(businessCommunityService.submitInformation({}), /尚未接通.*未提交/)
      await assert.rejects(businessCommunityService.applyForAlliance({}), /尚未接通.*未提交/)
    })
    await t.test('an empty or unexpected success response is not a review receipt', async () => {
      for (const data of [null, {}, { id: 1 }, { id: 1, status: 'APPROVED' }, { id: 1, status: 'PUBLISHED' }, { status: 'PENDING_REVIEW' }]) {
        globalThis.fetch = async () => response(200, data)
        await assert.rejects(businessCommunityService.submitInformation({}), /未收到有效的业务回执/)
        await assert.rejects(businessCommunityService.updatePublication(8, {}), /未收到有效的业务回执/)
      }
      globalThis.fetch = async () => response(200, { id: 1, status: 'PUBLISHED' })
      await assert.rejects(businessCommunityService.applyForAlliance({}), /未收到有效的业务回执/)
    })
    await t.test('validation errors stay actionable and an interrupted response remains uncertain', async () => {
      globalThis.fetch = async () => response(400, null, 1400, '统一社会信用代码不正确')
      await assert.rejects(businessCommunityService.applyForAlliance({}), /统一社会信用代码不正确/)
      globalThis.fetch = async () => response(502, null)
      await assert.rejects(businessCommunityService.submitInformation({}), /无法确认提交结果/)
      globalThis.fetch = async () => { throw new TypeError('Failed to fetch') }
      await assert.rejects(businessCommunityService.submitInformation({}), /无法确认提交结果/)
    })
  } finally {
    globalThis.fetch = originalFetch
    if (originalStorage === undefined) delete globalThis.localStorage
    else globalThis.localStorage = originalStorage
    await vite.close()
  }
})
