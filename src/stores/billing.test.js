import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'

test('billing uses real account responses and isolates late responses after account changes', async () => {
  const vite = await createServer({ configFile: false, cacheDir: 'node_modules/.vite-billing-tests',
    server: { middlewareMode: true, hmr: false }, appType: 'custom', optimizeDeps: { noDiscovery: true } })
  const originalFetch = globalThis.fetch
  const ok = data => ({ ok: true, status: 200, json: async () => ({ code: 200, data }) })
  const pending = []
  globalThis.fetch = (url, options) => new Promise(resolve => pending.push({ url, options, resolve }))
  const { billing, refreshBilling, loadBillingPlans } = await vite.ssrLoadModule('/src/stores/billing.js')
  const { auth } = await vite.ssrLoadModule('/src/stores/auth.js')
  try {
    auth.state.user = { id: 1, tenantId: 7 }
    auth.state.token = { accessToken: 'test-only' }
    const old = refreshBilling()
    refreshBilling()
    assert.equal(pending.length, 1)
    assert.equal(pending[0].url, '/api/billing/summary')
    const oldPlans = loadBillingPlans()
    auth.state.user = { id: 2, tenantId: 8 }
    assert.equal(billing.summary, null)
    const current = refreshBilling()
    pending[2].resolve(ok({ available: 23, giftAvailable: 3, purchasedAvailable: 20, held: 6 }))
    await current
    assert.equal(billing.summary.available, 23)
    pending[0].resolve(ok({ available: 999 }))
    pending[1].resolve(ok([{ name: 'late old catalogue' }]))
    assert.equal(await old, null)
    await oldPlans
    assert.equal(billing.summary.available, 23)
    assert.deepEqual(billing.plans, [])
    globalThis.fetch = async () => { throw new Error('connection lost') }
    await refreshBilling()
    assert.match(billing.error, /connection lost/)
    assert.equal(billing.summary.available, 23)
    auth.state.user = null
    assert.equal(billing.summary, null)
    assert.equal(await refreshBilling(), null)
  } finally {
    auth.state.user = null
    auth.state.token = null
    globalThis.fetch = originalFetch
    await vite.close()
  }
})

test('creation rechecks membership, prompts nonmembers and rejects a stale active account after network failure', async () => {
  const vite = await createServer({ configFile: false, cacheDir: 'node_modules/.vite-billing-tests',
    server: { middlewareMode: true, hmr: false }, appType: 'custom', optimizeDeps: { noDiscovery: true } })
  const savedFetch = globalThis.fetch
  const savedWindow = globalThis.window
  const events = []
  globalThis.window = { dispatchEvent: event => events.push(event.type) }
  const { auth } = await vite.ssrLoadModule('/src/stores/auth.js')
  const { billing, refreshBilling, requireMembership } = await vite.ssrLoadModule('/src/stores/billing.js')
  try {
    let guestRequests = 0
    globalThis.fetch = async () => { guestRequests++; throw new Error('guests must not request account data') }
    assert.equal(await refreshBilling(), null)
    assert.deepEqual(events, [], 'guest browsing must not prompt for login')
    await assert.rejects(requireMembership(), /请先登录/)
    assert.deepEqual(events, ['login-required'], 'creation asks a guest to log in')
    assert.equal(guestRequests, 0, 'a guest action must not request billing or submit a generation')
    events.length = 0
    auth.state.user = { id: 1, tenantId: 7 }
    auth.state.token = { accessToken: 'test-only' }
    let account = { memberActive: false }
    globalThis.fetch = async () => ({ ok: true, status: 200, json: async () => ({ code: 200, data: account }) })
    await refreshBilling()
    assert.deepEqual(events, [], 'loading the account must not prompt')
    await assert.rejects(requireMembership(), e => e.code === 9001 && e.message === '您还未开通会员')
    assert.deepEqual(events, ['membership-required'])
    account = { memberActive: true }
    assert.equal((await requireMembership()).memberActive, true)
    assert.equal(events.length, 1)
    globalThis.fetch = async () => { throw new Error('connection lost') }
    await assert.rejects(requireMembership(), /connection lost/)
    assert.equal(billing.summary.memberActive, true)
    assert.equal(events.length, 1, 'network failure must not be described as missing membership')
  } finally {
    auth.state.user = null; auth.state.token = null
    globalThis.fetch = savedFetch
    if (savedWindow === undefined) delete globalThis.window
    else globalThis.window = savedWindow
    await vite.close()
  }
})
