import { reactive } from 'vue'
import { createCommerceEngine, createConsumerContext, createOffering, OFFERING_KINDS } from '../domain/commerce.js'

const context = createConsumerContext({
  tenantId: 'tenant_demo_001',
  merchantId: 'merchant_demo_001',
  storeId: 'store_demo_001',
  entryContext: { campaignId: 'cmp_summer_001', contentId: 'content_summer_tea_01', source: '小红书' },
})

const offerings = [
  createOffering({ id: 'off_afternoon_tea', ...context, name: '双人下午茶', summary: '两杯招牌茶饮 + 一份季节甜点，周末到店更松弛。', category: '人气套餐', priceMinor: 2990, inventory: 12, cover: 'tea' }),
  createOffering({ id: 'off_peach_oolong', ...context, name: '蜜桃乌龙冷萃', summary: '清甜果香与乌龙回甘，适合下班后的第一口。', category: '当季饮品', priceMinor: 1800, inventory: 24, cover: 'peach' }),
  createOffering({ id: 'off_care', ...context, kind: OFFERING_KINDS.SERVICE, name: '午后舒缓护理', summary: '60 分钟肩颈与头疗服务，留一个慢下来的时段。', category: '预约服务', priceMinor: 16800, durationMinutes: 60, slots: ['明日 14:00', '明日 16:30', '周六 11:00'], cover: 'care' }),
]

const engine = createCommerceEngine({ offerings })
const cart = engine.createCart(context, 'customer_preview_001')

export const commerce = reactive({
  context,
  offerings,
  cart,
  selectedOffering: null,
  selectedSlot: null,
  order: null,
  handoff: null,
  message: '',
  get cartCount() { return this.cart.items.reduce((sum, item) => sum + item.quantity, 0) },
  get cartTotalMinor() { return this.cart.items.reduce((sum, item) => sum + item.quantity * item.unitPriceMinor, 0) },
  selectOffering(offering) {
    this.selectedOffering = offering
    this.selectedSlot = offering.kind === OFFERING_KINDS.SERVICE ? offering.slots[0] : null
    this.message = ''
  },
  addSelected() {
    if (!this.selectedOffering) return
    try {
      engine.addCartItem(this.context, this.cart.id, { offeringId: this.selectedOffering.id, quantity: 1, slot: this.selectedSlot })
      this.message = `${this.selectedOffering.name} 已加入接力单`
    } catch (error) {
      this.message = error.message
    }
  },
  createOrder() {
    try {
      this.order = engine.createDraftOrder(this.context, { cartId: this.cart.id, customerId: 'customer_preview_001', idempotencyKey: `preview:${this.cart.id}` })
      this.handoff = engine.createHandoff(this.context, { targetPath: '/consumer/checkout', payload: { orderId: this.order.id }, card: '确定性结算页' })
      this.message = '订单草稿已生成，等待进入确定性结算页'
    } catch (error) {
      this.message = error.message
    }
  },
  resetOrder() {
    this.order = null
    this.handoff = null
    this.message = ''
  },
})

