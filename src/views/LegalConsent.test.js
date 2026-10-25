import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createRenderer, nextTick } from 'vue'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }

async function fixture(viewName, initialPath, routes) {
  const filename = resolve(`src/views/${viewName}.vue`)
  const moduleId = filename.replaceAll('\\', '/').replace('.vue', '.consent-test.js')
  const vite = await createServer({ configFile: false, optimizeDeps: { noDiscovery: true, entries: [] },
    server: { middlewareMode: true, hmr: false }, appType: 'custom',
    plugins: [vue(), { name: 'legal-consent-interaction-test',
      resolveId(id) { if (id === '/view-under-test') return moduleId },
      load(id) {
        if (id !== moduleId) return
        return compileScript(parse(readFileSync(filename, 'utf8'), { filename }).descriptor, { id: 'legal-consent-test' }).content
          .replace('__expose();', '').replace('return __returned__', '__expose(__returned__); return __returned__')
      },
    }],
  })
  let app
  try {
    const { default: View } = await vite.ssrLoadModule('/view-under-test')
    View.render = () => null
    const router = createRouter({ history: createMemoryHistory(), routes: routes.map(route => ({ ...route, component: View })) })
    await router.push(initialPath)
    return { vite, router,
      mount() {
        const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {} })
        app = renderer.createApp(View).use(router)
        return app.mount({})
      },
      async close() { app?.unmount(); await vite.close() },
    }
  } catch (error) { await vite.close(); throw error }
}

test('registration requires explicit consent before SMS, password login and WeChat authorization', async () => {
  const context = await fixture('LoginView', '/login?mode=register&redirect=/works', [
    { path: '/login', name: 'login' }, { path: '/works', name: 'works' },
  ])
  try {
    const { auth } = await context.vite.ssrLoadModule('/src/stores/auth.js')
    const calls = []
    auth.sendCode = async phone => { calls.push(['sms', phone]); return {} }
    auth.login = async (...values) => { calls.push(['login', ...values]) }
    auth.loginWithPassword = async (...values) => { calls.push(['password', ...values]) }
    const view = context.mount()
    let focusCount = 0
    view.agreedInput = { focus() { focusCount++ } }
    Object.assign(view.form, { phone: '13800138000', code: '123456', account: '13800138000', password: 'UnitTestPass9' })
    assert.equal(view.form.agreed, false)
    await view.sendCode()
    await view.submit()
    view.loginWithWechat()
    view.selectTab('password')
    await view.submit()
    assert.deepEqual(calls, [], 'no account request can precede active consent')
    assert.match(view.errors.agreed, /用户注册协议与隐私协议/)
    assert.ok(focusCount > 0, 'the agreement error directs keyboard focus to the checkbox')
    view.form.agreed = true
    await view.sendCode()
    await view.submit()
    assert.deepEqual(calls, [['sms', '13800138000'], ['password', '13800138000', 'UnitTestPass9']])
    assert.equal(context.router.currentRoute.value.path, '/works', 'consent preserves the original post-login destination')
  } finally { await context.close() }
})

test('store checkout keeps recipient details and the cart when consent is missing or an order fails', async () => {
  const context = await fixture('PublicStorefrontView', '/store/7', [
    { path: '/store/:storeId', name: 'public-storefront' }, { path: '/order/:token', name: 'public-order' },
  ])
  try {
    const { commerceApi } = await context.vite.ssrLoadModule('/src/services/commerceApi.js')
    commerceApi.publicStore = async id => ({ id, name: '测试店铺' })
    commerceApi.publicProducts = async () => [{ id: 13, name: '测试商品', price: 25, inventory: 5, images: [] }]
    const requests = []
    commerceApi.placeOrder = async values => { requests.push(values); throw new Error('库存暂不足') }
    const view = context.mount()
    await settle()
    view.setQuantity(view.products[0], 2)
    Object.assign(view.buyer, { name: ' 测试收货人 ', phone: '13800138000', address: ' 测试地址 ', note: ' 测试备注 ' })
    assert.equal(view.buyer.legalAccepted, false)
    await view.submitOrder()
    assert.equal(requests.length, 0)
    assert.match(view.orderError, /交易与售后规则/)
    assert.equal(view.error, '', 'consent validation does not replace checkout with a store loading error')
    assert.equal(view.cartItems[0].quantity, 2)
    view.buyer.legalAccepted = true
    view.buyer.phone = '1'
    await view.submitOrder()
    assert.equal(requests.length, 0, 'an invalid phone is checked before creating an order')
    assert.match(view.phoneError, /联系电话/)
    assert.doesNotMatch(view.phoneError, /buyerPhone|正则表达式/)
    assert.equal(view.buyer.address, ' 测试地址 ')
    assert.equal(view.cartItems[0].quantity, 2)
    view.buyer.phone = '13800138000'
    await view.submitOrder()
    assert.equal(view.orderError, '库存暂不足')
    assert.equal(view.error, '')
    assert.equal(view.store.id, 7)
    assert.equal(view.buyer.address, ' 测试地址 ')
    assert.deepEqual(requests[0], { items: [{ productId: 13, quantity: 2 }], buyerName: '测试收货人',
      buyerPhone: '13800138000', shippingAddress: '测试地址', buyerNote: '测试备注' })
    const phoneFailure = new Error('buyerPhone 需要匹配正则表达式')
    phoneFailure.code = 1400
    commerceApi.placeOrder = async () => { throw phoneFailure }
    await view.submitOrder()
    assert.match(view.phoneError, /联系电话/)
    assert.equal(view.orderError, '')
    await context.router.push('/store/8')
    await settle()
    assert.equal(view.buyer.legalAccepted, false, 'consent must be obtained again when the receiving store changes')
  } finally { await context.close() }
})

test('top-ups and subscriptions require consent', async () => {
  const context = await fixture('BillingView', '/billing', [{ path: '/billing', name: 'billing' }])
  const originalFetch = globalThis.fetch
  globalThis.fetch = async () => ({ ok: true, status: 200, json: async () => ({ code: 200, data: [] }) })
  try {
    const { auth } = await context.vite.ssrLoadModule('/src/stores/auth.js')
    const { billing, billingApi } = await context.vite.ssrLoadModule('/src/stores/billing.js')
    billing.summary = { canManage: true, payment: { available: true } }
    const calls = []
    billingApi.topUp = async values => { calls.push(['topup', values]); return { state: 'CANCELLED' } }
    billingApi.subscribe = async values => { calls.push(['subscribe', values]); return { state: 'CANCELLED' } }
    const view = context.mount()
    await settle()
    let focusCount = 0
    view.topupTerms = { focus() { focusCount++ } }
    view.planTerms = { focus() { focusCount++ } }
    assert.equal(view.purchaseConsent, false)
    await view.topUp()
    await view.subscribe({ id: 3 })
    assert.deepEqual(calls, [])
    assert.ok(focusCount >= 2)
    view.purchaseConsent = true
    await view.topUp()
    await view.subscribe({ id: 3 })
    assert.deepEqual(calls.map(([kind]) => kind), ['topup', 'subscribe'])
    assert.equal(calls[0][1].amountMinor, 5000)
    assert.equal(calls[1][1].planId, 3)
    auth.state.user = { id: 2, tenantId: 9 }
    assert.equal(view.purchaseConsent, false, 'another account cannot inherit purchase consent')
  } finally { await context.close(); globalThis.fetch = originalFetch }
})
