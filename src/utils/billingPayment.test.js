import test from 'node:test'
import assert from 'node:assert/strict'
import { isMobilePayment, openBillingCashier } from './billingPayment.js'

test('phone and iPad browsers use mobile checkout, desktop stays on page pay', () => {
  const original = Object.getOwnPropertyDescriptor(globalThis, 'navigator')
  try {
    for (const [browser, expected] of [
      [{ userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_7) Mobile Safari' }, true],
      [{ userAgent: 'Mozilla/5.0 (Linux; Android 15) Chrome Mobile' }, true],
      [{ userAgent: 'Mozilla/5.0 (Macintosh) Safari', platform: 'MacIntel', maxTouchPoints: 5 }, true],
      [{ userAgent: 'Mozilla/5.0 (Windows NT 10.0) Chrome' }, false],
    ]) {
      Object.defineProperty(globalThis, 'navigator', { configurable: true, value: browser })
      assert.equal(isMobilePayment(), expected)
    }
  } finally {
    if (original) Object.defineProperty(globalThis, 'navigator', original)
    else delete globalThis.navigator
  }
})

test('mobile fetches before navigating, restores the order URL and leaves checkout intact on failure', async () => {
  const original = globalThis.window
  const events = []
  globalThis.window = {
    open() { throw new Error('a mobile checkout must not open a blank tab') },
    location: { assign: url => events.push(['navigate', url]) },
  }
  try {
    await openBillingCashier(async mobile => {
      assert.equal(mobile, true)
      events.push(['fetch'])
      return 'https://openapi.alipay.com/gateway.do?mobile=1'
    }, { mobile: true, beforeNavigate: async () => { events.push(['remember order']) } })
    assert.deepEqual(events, [['fetch'], ['remember order'], ['navigate', 'https://openapi.alipay.com/gateway.do?mobile=1']])
    events.length = 0
    await assert.rejects(openBillingCashier(async () => { throw new Error('手机支付暂未开放') }, { mobile: true }), /暂未开放/)
    assert.deepEqual(events, [])
    await assert.rejects(openBillingCashier(async () => 'https://pay.test', {
      mobile: true, beforeNavigate: async () => { throw new Error('订单已关闭') },
    }), /订单已关闭/)
    assert.deepEqual(events, [], 'closed orders must not navigate to the cashier')
  } finally { globalThis.window = original }
})

test('desktop opens inside the click and cleans up on failure, blocked windows never request a cashier', async () => {
  const original = globalThis.window
  const tab = { opener: {}, location: {}, close() { this.closed = true } }
  let opened = false
  globalThis.window = { open() { opened = true; return tab } }
  try {
    await openBillingCashier(async mobile => {
      assert.equal(mobile, false)
      assert.equal(opened, true)
      assert.equal(tab.opener, null)
      return 'https://pay.test'
    }, { mobile: false })
    assert.equal(tab.location.href, 'https://pay.test')
    await assert.rejects(openBillingCashier(async () => { throw new Error('network failed') }, { mobile: false }), /network failed/)
    assert.equal(tab.closed, true)
    window.open = () => null
    await assert.rejects(openBillingCashier(async () => assert.fail('must not request when blocked'), { mobile: false }), /拦截/)
  } finally { globalThis.window = original }
})
