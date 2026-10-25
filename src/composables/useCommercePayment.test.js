import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, h, ref } from 'vue'
import { createServer } from 'vite'

test('commerce opens a fresh cashier on click, reconciles payment and stops polling after payment or unmount', async () => {
  const vite = await createServer({ configFile: false, optimizeDeps: { noDiscovery: true, entries: [] },
    server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const originalWindow = globalThis.window
  const originalDocument = globalThis.document
  let app
  try {
    const { commerceApi } = await vite.ssrLoadModule('/src/services/commerceApi.js')
    const { useCommercePayment } = await vite.ssrLoadModule('/src/composables/useCommercePayment.js')
    const pending = { status: 'PENDING_PAYMENT', paymentMethod: 'ALIPAY', paymentState: 'PENDING' }
    let result = pending
    let queries = 0
    let tick
    let cleared = false
    let opened = false
    const tab = { opener: {}, location: {}, close() { this.closed = true } }
    globalThis.window = {
      open: () => { opened = true; return tab },
      setInterval: callback => { tick = callback; return 1 },
      clearInterval: () => { cleared = true }, addEventListener() {}, removeEventListener() {},
    }
    globalThis.document = { visibilityState: 'visible', addEventListener() {}, removeEventListener() {} }
    commerceApi.publicOrder = async () => pending
    commerceApi.refreshOrder = async () => { queries++; return result }
    commerceApi.alipay = async token => {
      assert.equal(opened, true, 'popup opens in the click before awaiting the URL')
      assert.equal(token, 'lookup')
      return 'https://openapi.alipay.com/gateway.do?fresh=1'
    }
    const renderer = createRenderer({
      createElement: tag => ({ tag, children: [] }), createText: text => ({ text }), createComment: text => ({ text }),
      setText: (node, text) => { node.text = text }, setElementText: (node, text) => { node.text = text },
      patchProp() {}, insert: (node, parent) => { parent.children.push(node) }, remove() {},
      parentNode: () => null, nextSibling: () => null,
    })
    let payment
    app = renderer.createApp({ setup() { payment = useCommercePayment(ref('lookup')); return () => h('div') } })
    app.mount({ children: [] })
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(queries, 1)
    await payment.openAlipay()
    assert.equal(tab.opener, null)
    assert.equal(tab.location.href, 'https://openapi.alipay.com/gateway.do?fresh=1')
    globalThis.window.open = () => null
    await payment.openAlipay()
    assert.match(payment.paymentError.value, /拦截/)
    result = { ...pending, status: 'PENDING_CONFIRMATION', paymentState: 'PAID' }
    tick()
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(payment.order.value.paymentState, 'PAID')
    assert.equal(payment.paymentError.value, '')
    const afterPaid = queries
    tick()
    await payment.openAlipay()
    assert.equal(queries, afterPaid)
    app.unmount()
    app = null
    assert.equal(cleared, true)
  } finally {
    app?.unmount()
    globalThis.window = originalWindow
    globalThis.document = originalDocument
    await vite.close()
  }
})
