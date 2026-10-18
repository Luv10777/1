<script setup>
import { computed, ref, watch } from 'vue'
import { ClipboardList, RefreshCw, Truck, Check, X, CircleCheck } from 'lucide-vue-next'
import { selectedStore, selectedStoreId, storeError, storeLoading } from '../stores/merchantContext'
import { commerceApi, orderStatuses } from '../services/commerceApi'
import '../public-commerce.css'

const orders = ref([])
const filter = ref('ALL')
const loading = ref(false)
const busyId = ref(null)
const error = ref('')
const notice = ref('')

const filteredOrders = computed(() => filter.value === 'ALL' ? orders.value : orders.value.filter(order => order.status === filter.value))
const counts = computed(() => Object.fromEntries(['ALL', 'PENDING_CONFIRMATION', 'CONFIRMED', 'SHIPPED', 'COMPLETED', 'CANCELLED'].map(status => [status,
  status === 'ALL' ? orders.value.length : orders.value.filter(order => order.status === status).length,
])))
const filters = computed(() => [
  { value: 'ALL', label: '全部' }, { value: 'PENDING_CONFIRMATION', label: '待确认' },
  { value: 'CONFIRMED', label: '待发货' }, { value: 'SHIPPED', label: '运输中' },
  { value: 'COMPLETED', label: '已完成' }, { value: 'CANCELLED', label: '已取消' },
].map(item => ({ ...item, count: counts.value[item.value] })))
const money = value => Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const nextActions = order => ({
  PENDING_CONFIRMATION: [{ status: 'CONFIRMED', label: order.payment ? '核实收款并确认订单' : '确认订单', icon: Check }, { status: 'CANCELLED', label: '取消并恢复库存', icon: X }],
  CONFIRMED: [{ status: 'CANCELLED', label: '取消并恢复库存', icon: X }],
  SHIPPED: [{ status: 'COMPLETED', label: '标记完成', icon: CircleCheck }],
}[order.status] || [])

async function loadOrders() {
  const storeId = selectedStoreId.value
  if (!storeId) { orders.value = []; return }
  loading.value = true
  error.value = ''
  notice.value = ''
  try { orders.value = await commerceApi.merchantOrders(storeId) }
  catch (failure) { error.value = failure.message || '订单暂时无法读取。' }
  finally { loading.value = false }
}

async function changeStatus(order, status, shipping = {}) {
  if (busyId.value) return
  if (status === 'CONFIRMED' && order.payment && !window.confirm('请先在企业支付宝中核对实际到账金额及订单号。确认已收到本单款项并可安排交付吗？')) return
  if (status === 'CANCELLED' && !window.confirm('取消后将恢复对应商品库存，但不会自动退款。如顾客已付款，请另行核实并办理退款。确定继续吗？')) return
  busyId.value = order.id
  error.value = ''
  notice.value = ''
  try {
    const updated = await commerceApi.updateOrder(selectedStoreId.value, order.id, status, shipping)
    orders.value = orders.value.map(item => item.id === updated.id ? updated : item)
    notice.value = `订单 ${updated.orderNumber} 已更新为「${orderStatuses[updated.status]}」`
  } catch (failure) { error.value = failure.message || '订单状态更新失败。' }
  finally { busyId.value = null }
}

watch(selectedStoreId, loadOrders, { immediate: true })
</script>

<template>
  <section class="merchant-orders-page">
    <header class="merchant-orders-heading"><div><p>MERCHANT COMMERCE / ORDERS</p><h1>订单管理</h1><span>{{ selectedStore }}</span></div><button type="button" class="public-button" :disabled="loading || !selectedStoreId" @click="loadOrders"><RefreshCw :size="15" />刷新</button></header>
    <p v-if="notice" class="merchant-order-notice" role="status">{{ notice }}</p>
    <p v-if="error" class="public-error" role="alert">{{ error }}</p>
    <p v-if="loading || storeLoading" class="merchant-orders-state" role="status">正在读取订单…</p>
    <p v-else-if="!selectedStoreId" class="merchant-orders-state" role="status">{{ storeError || '请先选择门店。' }}</p>
    <template v-else>
      <nav class="merchant-order-filters" aria-label="订单状态筛选"><button v-for="item in filters" :key="item.value" type="button" :class="{ active: filter === item.value }" :aria-pressed="filter === item.value" @click="filter = item.value">{{ item.label }}<span>{{ item.count }}</span></button></nav>
      <p class="merchant-payment-note">企业支付宝扫码付款需人工核实到账金额及订单号，再确认订单并安排发货。取消订单会恢复有限库存，已收款项需另行办理退款。</p>
      <div v-if="filteredOrders.length" class="merchant-order-list">
        <article v-for="order in filteredOrders" :key="order.id" class="merchant-order-row">
          <header><div><strong>{{ order.orderNumber }}</strong><time>{{ new Date(order.createdAt).toLocaleString('zh-CN', { hour12: false }) }}</time></div><span :class="`status-${order.status.toLowerCase()}`">{{ orderStatuses[order.status] }}</span></header>
          <div class="merchant-order-content"><div class="merchant-order-buyer"><strong>{{ order.buyerName }} · {{ order.buyerPhone }}</strong><span>{{ order.shippingAddress }}</span><small v-if="order.buyerNote">备注：{{ order.buyerNote }}</small></div><ul><li v-for="item in order.items" :key="item.productId || item.productName"><span>{{ item.productName }} × {{ item.quantity }}</span><strong>¥{{ money(item.lineTotal) }}</strong></li></ul></div>
          <form v-if="order.status === 'CONFIRMED'" class="merchant-shipment-form" @submit.prevent="changeStatus(order, 'SHIPPED', { shippingCompany: order.shippingCompany, trackingNumber: order.trackingNumber })"><label><span>承运方 / 配送方式</span><input v-model.trim="order.shippingCompany" maxlength="60" required placeholder="例如：顺丰速运、自提" /></label><label><span>运单号</span><input v-model.trim="order.trackingNumber" maxlength="120" placeholder="无物流时可留空" /></label><button type="submit" class="public-button public-button-primary" :disabled="busyId === order.id"><Truck :size="14" />{{ busyId === order.id ? '正在保存…' : '保存物流并发货' }}</button></form>
          <footer><strong>合计 ¥{{ money(order.totalAmount) }}</strong><div><button v-for="action in nextActions(order)" :key="action.status" type="button" class="public-button" :class="{ 'public-button-primary': action.status !== 'CANCELLED' }" :disabled="busyId === order.id" @click="changeStatus(order, action.status)"><component :is="action.icon" :size="14" />{{ busyId === order.id ? '正在保存…' : action.label }}</button></div></footer>
        </article>
      </div>
      <div v-else class="merchant-orders-empty"><ClipboardList :size="32" /><h2>{{ orders.length ? '这个状态下没有订单' : '还没有顾客订单' }}</h2><p>{{ orders.length ? '切换其他状态查看订单。' : '店铺商品上架并公开后，顾客下单会出现在这里。' }}</p><RouterLink to="/assets/products" class="public-text-link">管理店铺商品</RouterLink></div>
    </template>
  </section>
</template>
