<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft, Check, Clock3, PackageCheck, RefreshCw, Store, Truck } from 'lucide-vue-next'
import { commerceApi, orderStatuses } from '../services/commerceApi'
import '../public-commerce.css'

const route = useRoute()
const order = ref(null)
const loading = ref(false)
const error = ref('')
const currentStatus = computed(() => order.value?.status || '')
const steps = [
  { status: 'PENDING_CONFIRMATION', label: '等待商家确认', icon: Clock3 },
  { status: 'CONFIRMED', label: '订单已确认', icon: Check },
  { status: 'SHIPPED', label: '商品已发出', icon: Truck },
  { status: 'COMPLETED', label: '订单完成', icon: PackageCheck },
]
const activeStep = computed(() => Math.max(0, steps.findIndex(step => step.status === currentStatus.value)))
const formatMoney = value => Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

async function loadOrder() {
  loading.value = true
  error.value = ''
  try {
    order.value = await commerceApi.publicOrder(route.params.token)
  } catch (failure) {
    error.value = failure.message || '无法读取订单状态。'
  } finally {
    loading.value = false
  }
}

watch(() => route.params.token, loadOrder, { immediate: true })
</script>

<template>
  <main class="public-commerce public-order-page">
    <header class="public-commerce-topbar"><RouterLink to="/information" class="public-brand"><span class="public-brand-seal"><img src="/images/brand/yifangzhi-mark.png" alt="" /></span><span><strong>一方志</strong><small>订单查询</small></span></RouterLink><RouterLink to="/information" class="public-text-link">商家信息<ArrowLeft :size="14" /></RouterLink></header>
    <div class="public-order-content">
      <p v-if="loading" class="public-state" role="status">正在读取订单…</p>
      <section v-else-if="error" class="public-empty" role="alert"><PackageCheck :size="30" /><h1>订单暂时无法读取</h1><p>{{ error }}</p><button type="button" class="public-button" @click="loadOrder"><RefreshCw :size="15" />重新查询</button></section>
      <template v-else-if="order">
        <div class="public-order-success"><span class="public-order-success-mark"><Check :size="21" /></span><div><p>订单已保存</p><h1>{{ orderStatuses[order.status] || order.status }}</h1></div><button class="public-icon-button" type="button" aria-label="刷新订单状态" :disabled="loading" @click="loadOrder"><RefreshCw :size="17" /></button></div>
        <section class="public-order-tracking" aria-label="订单进度">
          <div v-for="(step, index) in steps" :key="step.status" :class="{ active: order.status !== 'CANCELLED' && index <= activeStep, cancelled: order.status === 'CANCELLED' && index === 0 }"><span><component :is="step.icon" :size="16" /></span><strong>{{ order.status === 'CANCELLED' && index === 0 ? '订单已取消' : step.label }}</strong></div>
        </section>
        <section class="public-order-detail">
          <header><div><p>ORDER RECEIPT</p><h2>订单内容</h2></div><span>{{ order.orderNumber }}</span></header>
          <div class="public-order-lines"><div v-for="item in order.items" :key="item.productId || item.productName"><span>{{ item.productName }} × {{ item.quantity }}</span><strong>¥{{ formatMoney(item.lineTotal) }}</strong></div></div>
          <div class="public-order-total"><span>订单金额</span><strong>¥{{ formatMoney(order.totalAmount) }}</strong></div>
          <dl><div><dt>提交时间</dt><dd>{{ new Date(order.createdAt).toLocaleString('zh-CN', { hour12: false }) }}</dd></div><div><dt>订单状态</dt><dd>{{ orderStatuses[order.status] || order.status }}</dd></div></dl>
          <dl v-if="order.shippingCompany || order.trackingNumber"><div v-if="order.shippingCompany"><dt>承运方 / 配送方式</dt><dd>{{ order.shippingCompany }}</dd></div><div v-if="order.trackingNumber"><dt>运单号</dt><dd>{{ order.trackingNumber }}</dd></div></dl>
          <p class="public-payment-note">当前未接入线上收款，订单提交不会产生付款。请等待商家确认后按商家指引完成交易。</p>
        </section>
        <RouterLink to="/information" class="public-store-footer-link"><Store :size="15" />回到一方志商家信息<ArrowLeft :size="14" /></RouterLink>
      </template>
    </div>
  </main>
</template>
