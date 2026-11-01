import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createRenderer, nextTick } from 'vue'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

const id = '11111111-2222-3333-4444-555555555555'
const pendingOrder = { id, state: 'PENDING_PAYMENT', kind: 'TOPUP', amountMinor: 100,
  credits: 10, paymentMethod: 'ALIPAY', expiresAt: '2099-01-01T00:00:00Z' }
const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }

async function fixture(path) {
  const saved = Object.fromEntries(['window', 'document', 'navigator', 'fetch'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  const navigation = [], listeners = new Map(), reads = []
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { userAgent: 'iPhone Mobile Safari' } })
  Object.defineProperty(globalThis, 'window', { configurable: true, value: {
    open() { assert.fail('mobile must not open a blank tab') },
    location: { assign: url => navigation.push(url) }, addEventListener() {}, removeEventListener() {},
  } })
  Object.defineProperty(globalThis, 'document', { configurable: true, value: {
    visibilityState: 'visible', addEventListener: (event, callback) => listeners.set(event, callback),
    removeEventListener: event => listeners.delete(event),
  } })
  globalThis.fetch = async url => ({ ok: true, status: 200, json: async () => ({ code: 200,
    data: url.includes('/plans') ? [] : { available: 100, creditsPerYuan: 10, canManage: true, payment: { available: true } },
  }) })
  const filename = resolve('src/views/BillingView.vue')
  const moduleId = filename.replaceAll('\\', '/').replace('.vue', '.payment-test.js')
  const vite = await createServer({ configFile: false, optimizeDeps: { noDiscovery: true, entries: [] },
    server: { middlewareMode: true, hmr: false }, appType: 'custom',
    plugins: [vue(), { name: 'billing-payment-test',
      resolveId(id) { if (id === '/billing-under-test') return moduleId },
      load(id) {
        if (id === moduleId) return compileScript(parse(readFileSync(filename, 'utf8'), { filename }).descriptor,
          { id: 'billing-payment-test' }).content.replace('__expose();', '')
          .replace('return __returned__', '__expose(__returned__); return __returned__')
      },
    }],
  })
  let app
  try {
    const { default: View } = await vite.ssrLoadModule('/billing-under-test')
    const { auth } = await vite.ssrLoadModule('/src/stores/auth.js')
    auth.state.user = { id: 1, tenantId: 7 }
    auth.state.token = { accessToken: 'test-only' }
    const { billingApi } = await vite.ssrLoadModule('/src/stores/billing.js')
    billingApi.order = async value => { reads.push(['order', value]); return { ...pendingOrder } }
    billingApi.refresh = async value => { reads.push(['refresh', value]); return { ...pendingOrder } }
    View.render = () => null
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/billing', component: View }] })
    await router.push(path)
    const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {} })
    app = renderer.createApp(View).use(router)
    const view = app.mount({})
    for (let attempt = 0; attempt < 20 && !listeners.has('visibilitychange'); attempt++) await settle()
    assert.ok(listeners.has('visibilitychange'), 'checkout finishes mounting')
    return { view, router, billingApi, navigation, listeners, reads,
      async close() {
        app.unmount(); await vite.close()
        for (const [key, descriptor] of Object.entries(saved)) {
          if (descriptor) Object.defineProperty(globalThis, key, descriptor)
          else delete globalThis[key]
        }
      },
    }
  } catch (error) {
    app?.unmount(); await vite.close()
    for (const [key, descriptor] of Object.entries(saved)) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor)
      else delete globalThis[key]
    }
    throw error
  }
}

test('mobile keeps the pending checkout on gateway failure and preserves its order before navigating', async () => {
  const context = await fixture('/billing')
  try {
    await context.view.openPayment({ ...pendingOrder })
    context.billingApi.alipay = async (value, mobile) => {
      assert.equal(value, id); assert.equal(mobile, true)
      throw new Error('手机支付宝支付暂未开放')
    }
    await context.view.openAlipay()
    assert.match(context.view.paymentError, /暂未开放/)
    assert.equal(context.view.payment.state, 'PENDING_PAYMENT')
    assert.deepEqual(context.navigation, [])
    context.billingApi.alipay = async () => 'https://openapi.alipay.com/gateway.do?wap=1'
    await context.view.openAlipay()
    assert.equal(context.router.currentRoute.value.query.paymentOrder, id)
    assert.deepEqual(context.navigation, ['https://openapi.alipay.com/gateway.do?wap=1'])
    assert.equal(context.view.paymentError, '')
  } finally { await context.close() }
})

test('cashier return restores and verifies the order, ignores success query claims and reconciles on visibility', async () => {
  const context = await fixture('/billing?paymentOrder=' + id + '&trade_status=TRADE_SUCCESS')
  try {
    assert.deepEqual(context.reads, [['order', id], ['refresh', id]])
    assert.equal(context.view.payment.state, 'PENDING_PAYMENT', 'return parameters never fulfil a payment')
    assert.equal(context.view.topupResult, null)
    context.billingApi.refresh = async () => ({ ...pendingOrder, state: 'PAID', paidAt: '2026-11-01T08:00:00Z' })
    context.listeners.get('visibilitychange')()
    for (let attempt = 0; attempt < 10; attempt++) await settle()
    assert.equal(context.view.topupResult.credits, 10)
    assert.equal(context.view.payment, null)
    assert.equal(context.router.currentRoute.value.query.paymentOrder, undefined)
  } finally { await context.close() }
  assert.equal(context.listeners.size, 0, 'unmount removes the payment listener')
})
