import test from 'node:test'
import assert from 'node:assert/strict'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('enterprise Alipay shows the persisted amount and original QR only for payable manual orders', async () => {
  const vite = await createServer({ configFile: false, plugins: [vue()],
    optimizeDeps: { noDiscovery: true, entries: [] },
    server: { middlewareMode: true, hmr: false }, appType: 'custom',
  })
  try {
    const { default: Payment } = await vite.ssrLoadModule('/src/components/CommerceAlipayPayment.vue')
    const order = { status: 'PENDING_CONFIRMATION', orderNumber: 'YF-ALIPAY-TEST', totalAmount: '197.50',
      payment: { method: 'ALIPAY_MANUAL', recipient: '嘉兴市梧曜科技有限公司', qrCodeUrl: '/images/payments/company-alipay-qr.png' } }
    const render = values => renderToString(createSSRApp({ render: () => h(Payment, { order: { ...order, ...values } }) }))
    const html = await render({})
    assert.match(html, /197\.50/)
    assert.match(html, /YF-ALIPAY-TEST/)
    assert.match(html, /嘉兴市梧曜科技有限公司/)
    assert.match(html, /src="\/images\/payments\/company-alipay-qr\.png"/)
    assert.match(html, /download="一方志企业支付宝收款码\.png"/)
    assert.match(html, /付款备注填写订单号/)
    assert.match(html, /付款后由商家核实到账/)
    assert.doesNotMatch(html, /支付成功|已付款成功|确认已支付/)

    for (const status of ['CONFIRMED', 'SHIPPED', 'COMPLETED', 'CANCELLED']) {
      assert.doesNotMatch(await render({ status }), /<img|扫码付款/, `${status} must not request a second payment`)
    }
    assert.doesNotMatch(await render({ payment: null }), /<img|扫码付款/, 'other merchants do not inherit the collection code')
    assert.doesNotMatch(await render({ totalAmount: 0 }), /<img|扫码付款/)

    const auto = { paymentMethod: 'ALIPAY', paymentState: 'PENDING', status: 'PENDING_PAYMENT',
      payment: { method: 'ALIPAY', recipient: '嘉兴市梧曜科技有限公司' }, paymentExpiresAt: '2099-01-01T00:00:00Z' }
    const cashier = await render(auto)
    assert.match(cashier, /前往支付宝付款/)
    assert.match(cashier, /197\.50/)
    assert.match(cashier, /查询支付结果/)
    assert.match(cashier, /class="commerce-alipay-logo"/)
    assert.match(cashier, /alt="支付宝"/)
    assert.match(cashier, /role="timer"/)
    assert.match(cashier, /支付剩余时间/)
    assert.doesNotMatch(cashier, /company-alipay-qr|输入上方应付金额|支付成功/)
    const paid = await render({ ...auto, status: 'SHIPPED', paymentState: 'PAID', payment: null })
    assert.match(paid, /支付宝支付成功/)
    assert.doesNotMatch(paid, /前往支付宝付款|查询支付结果|等待商家确认/)
    assert.doesNotMatch(await render({ ...auto, status: 'CANCELLED', paymentState: 'CLOSED' }), /前往支付宝付款|查询支付结果|支付成功/)
    assert.doesNotMatch(await render({ ...auto, paymentExpiresAt: '2000-01-01T00:00:00Z' }), /前往支付宝付款/)
  } finally { await vite.close() }
})
