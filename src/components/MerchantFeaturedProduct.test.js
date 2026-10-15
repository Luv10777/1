import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createRenderer, nextTick } from 'vue'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createServer } from 'vite'

test('featured physical goods use persisted product facts and preserve checkout details on failure', async () => {
  const filename = resolve('src/components/MerchantFeaturedProduct.vue')
  const moduleId = filename.replaceAll('\\', '/').replace('.vue', '.interaction.js')
  const vite = await createServer({ configFile: false, optimizeDeps: { noDiscovery: true, entries: [] },
    server: { middlewareMode: true, hmr: false }, appType: 'custom',
    plugins: [{ name: 'featured-checkout-test',
      resolveId(id) { if (id === '/featured-under-test') return moduleId },
      load(id) {
        if (id !== moduleId) return
        return compileScript(parse(readFileSync(filename, 'utf8'), { filename }).descriptor, { id: 'featured-test' }).content
          .replace('__expose();', '').replace('return __returned__', '__expose(__returned__); return __returned__')
      },
    }],
  })
  let app
  try {
    const { commerceApi } = await vite.ssrLoadModule('/src/services/commerceApi.js')
    let records = [{ id: 13, storeId: 7, name: '直播收音接口', type: 'PHYSICAL', onlineStatus: 'PUBLISHED',
      price: 100, inventory: 2, images: [{ assetId: 21 }, { assetId: 22 }] }]
    commerceApi.featuredListing = async () => ({ storeId: 7, productId: 13 })
    commerceApi.publicStore = async () => ({ id: 7, name: '测试店铺' })
    commerceApi.publicProducts = async () => records
    commerceApi.publicImageUrl = async id => ({ downloadUrl: `/test-assets/${id}.jpg` })
    const { default: Featured } = await vite.ssrLoadModule('/featured-under-test')
    Featured.render = () => null
    const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {} })
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/', component: Featured }, { path: '/order/:token', name: 'public-order', component: { render: () => null } },
    ] })
    await router.push('/')
    app = renderer.createApp(Featured).use(router)
    const view = app.mount({})
    await new Promise(resolve => setImmediate(resolve)); await nextTick()
    assert.equal(view.product.id, 13)
    assert.equal(view.product.price, 100)
    assert.deepEqual(view.images, ['/test-assets/21.jpg', '/test-assets/22.jpg'])
    view.quantity = 10
    view.openCheckout()
    assert.equal(view.quantity, 2, 'purchase quantity is limited by the current inventory')

    Object.assign(view.buyer, { name: ' 测试收货人 ', phone: '13800138000', address: ' 测试地址 ', note: ' 测试备注 ' })
    let valid = false
    const field = { value: '   ', validityMessage: '', setCustomValidity(message) { this.validityMessage = message } }
    view.orderEditor = { querySelectorAll: () => [field], reportValidity: () => valid }
    const requests = []
    let completeOrder
    commerceApi.placeOrder = values => { requests.push(values); return new Promise(resolve => { completeOrder = resolve }) }
    await view.submitOrder()
    assert.equal(field.validityMessage, '请填写此项。')
    assert.equal(requests.length, 0, 'blank recipient details cannot create an order')
    valid = true; field.value = '测试收货人'
    const pending = view.submitOrder()
    await view.submitOrder()
    assert.equal(requests.length, 1, 'repeated submits do not create concurrent orders')
    assert.deepEqual(requests[0], { items: [{ productId: 13, quantity: 2 }], buyerName: '测试收货人',
      buyerPhone: '13800138000', shippingAddress: '测试地址', buyerNote: '测试备注' })
    assert.equal('price' in requests[0], false, 'the server determines the payable product amount')
    completeOrder({})
    await pending
    assert.match(view.orderError, /未收到订单查询凭证/)
    assert.equal(view.checkoutOpen, true)
    assert.equal(view.buyer.address, ' 测试地址 ')
    assert.equal(router.currentRoute.value.path, '/')

    commerceApi.placeOrder = async () => { throw new Error('库存不足') }
    await view.submitOrder()
    assert.equal(view.orderError, '库存不足')
    assert.equal(view.buyer.phone, '13800138000')
    let blocked = true
    let committedOrders = 0
    router.beforeEach(() => !blocked)
    commerceApi.placeOrder = async () => { committedOrders++; return { orderNumber: 'TEST-ORDER', lookupToken: 'test-receipt-token' } }
    await view.submitOrder()
    assert.equal(view.orderReceipt.lookupToken, 'test-receipt-token')
    assert.equal(router.currentRoute.value.path, '/')
    assert.equal(view.checkoutOpen, true, 'an enrollment draft may prevent leaving but must not hide the saved order')
    await view.submitOrder()
    assert.equal(committedOrders, 1, 'a saved receipt prevents resubmitting an already committed order')
    blocked = false
    await view.goToOrder()
    assert.equal(router.currentRoute.value.path, '/order/test-receipt-token')
    assert.equal(view.checkoutOpen, false)

    records = [{ ...records[0], inventory: 0 }]
    await view.loadProduct()
    assert.equal(view.available, false)
    records = [{ ...records[0], onlineStatus: 'DRAFT' }]
    await view.loadProduct()
    assert.equal(view.product, null, 'unpublished products are never offered for purchase')
    assert.equal(view.available, false)
  } finally {
    app?.unmount()
    await vite.close()
  }
})
