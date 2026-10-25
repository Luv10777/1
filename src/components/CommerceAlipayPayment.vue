<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { Download } from 'lucide-vue-next'
import { LEGAL_ENTITY } from '../domain/legalPolicies'

const props = defineProps({ order: { type: Object, required: true }, loading: Boolean, error: { type: String, default: '' } })
const emit = defineEmits(['pay', 'refresh'])
const now = ref(Date.now())
let countdownTimer
let stopCountdownWatch
const imageError = ref(false)
const payable = computed(() => props.order.status === 'PENDING_CONFIRMATION'
  && props.order.payment?.method === 'ALIPAY_MANUAL' && Number(props.order.totalAmount) > 0)
const automatic = computed(() => props.order.paymentMethod === 'ALIPAY')
const pending = computed(() => automatic.value && props.order.paymentState === 'PENDING')
const remainingSeconds = computed(() => Math.max(0, Math.ceil((Date.parse(props.order.paymentExpiresAt) - now.value) / 1000)))
const expired = computed(() => props.order.paymentExpiresAt && remainingSeconds.value === 0)
const countdown = computed(() => {
  const seconds = remainingSeconds.value
  const minutes = Math.floor(seconds / 60)
  return `${String(minutes).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
})
const amount = computed(() => Number(props.order.totalAmount).toLocaleString('zh-CN', {
  minimumFractionDigits: 2, maximumFractionDigits: 2,
}))

watch(() => props.order.payment?.qrCodeUrl, () => { imageError.value = false })

onMounted(() => {
  stopCountdownWatch = watch(() => [pending.value, props.order.paymentExpiresAt], ([payable]) => {
    window.clearInterval(countdownTimer)
    now.value = Date.now()
    if (!payable || expired.value) return
    countdownTimer = window.setInterval(() => {
      now.value = Date.now()
      if (expired.value) {
        window.clearInterval(countdownTimer)
        emit('refresh')
      }
    }, 1000)
  }, { immediate: true })
})
onUnmounted(() => {
  stopCountdownWatch?.()
  window.clearInterval(countdownTimer)
})
</script>

<template>
  <section v-if="automatic" class="commerce-alipay" aria-label="支付宝付款">
    <header><img class="commerce-alipay-logo" src="/images/payments/alipay.svg" alt="支付宝" width="24" height="24" /><h3>{{ order.paymentState === 'PAID' ? '支付宝支付成功' : order.paymentState === 'CLOSED' ? '支付已关闭' : '支付宝付款' }}</h3></header>
    <p class="commerce-alipay-label">{{ order.paymentState === 'PAID' ? '实付金额' : '订单金额' }}</p>
    <p class="commerce-alipay-amount"><span>¥</span>{{ amount }}</p>
    <p class="commerce-alipay-order">订单号：<strong>{{ order.orderNumber }}</strong></p>
    <p v-if="order.payment" class="commerce-alipay-recipient">收款方：{{ order.payment.recipient }}</p>
    <template v-if="pending">
      <p class="commerce-alipay-countdown"><span>支付剩余时间</span><strong role="timer" aria-label="支付剩余时间" aria-live="off">{{ countdown }}</strong></p>
      <p class="commerce-alipay-recipient">付款截止：{{ new Date(order.paymentExpiresAt).toLocaleString('zh-CN', { hour12: false }) }}</p>
      <button v-if="!expired" type="button" class="public-button public-button-primary" :disabled="loading" @click="$emit('pay')">{{ loading ? '正在打开收银台…' : '前往支付宝付款' }}</button>
      <p v-else class="commerce-alipay-recipient" role="status">付款时限已结束，正在核实最终支付结果。</p>
      <button type="button" class="public-button" :disabled="loading" @click="$emit('refresh')">查询支付结果</button>
      <p class="commerce-alipay-recipient">支付完成后自动更新订单。已付款请勿重复支付。</p>
    </template>
    <p v-else-if="order.paymentState === 'PAID'" class="commerce-alipay-recipient" role="status">{{ order.status === 'PENDING_CONFIRMATION' ? '付款已核实，请等待商家确认并安排交付。' : '付款已核实，交付进度请查看下方订单信息。' }}</p>
    <p v-else class="commerce-alipay-recipient">订单已取消，请勿继续付款。</p>
    <p v-if="error" class="public-error" role="alert">{{ error }}</p>
    <p class="commerce-alipay-help">付款或售后遇到问题，请提供订单号联系<a :href="`tel:${LEGAL_ENTITY.phone}`">{{ LEGAL_ENTITY.phone }}</a>。</p>
  </section>
  <section v-if="payable" class="commerce-alipay" aria-label="企业支付宝付款">
    <header><img class="commerce-alipay-logo" src="/images/payments/alipay.svg" alt="支付宝" width="24" height="24" /><h3>支付宝扫码付款</h3></header>
    <p class="commerce-alipay-label">本单应付金额</p>
    <p class="commerce-alipay-amount"><span>¥</span>{{ amount }}</p>
    <p class="commerce-alipay-order">订单号：<strong>{{ order.orderNumber }}</strong></p>
    <div class="commerce-alipay-code">
      <p v-if="imageError" role="alert">收款码暂时无法加载，请刷新页面重试，或联系客服。</p>
      <img v-else :src="order.payment.qrCodeUrl" :alt="`${order.payment.recipient}企业支付宝收款二维码`" width="280" height="280" @error="imageError = true" />
    </div>
    <p class="commerce-alipay-recipient">收款方：{{ order.payment.recipient }}</p>
    <a v-if="!imageError" class="commerce-alipay-download" :href="order.payment.qrCodeUrl" download="一方志企业支付宝收款码.png"><Download :size="15" aria-hidden="true" />保存收款码</a>
    <ol class="commerce-alipay-instructions">
      <li>打开支付宝扫一扫；同一部手机可保存收款码后，在扫一扫中从相册选择。</li>
      <li>核对收款方，输入上方应付金额，并在付款备注填写订单号。</li>
      <li>付款后由商家核实到账并确认订单。已付款请勿重复支付。</li>
    </ol>
    <p class="commerce-alipay-help">付款或核实遇到问题，请提供订单号联系<a :href="`tel:${LEGAL_ENTITY.phone}`">{{ LEGAL_ENTITY.phone }}</a>。</p>
  </section>
</template>

<style scoped>
.commerce-alipay { margin: 20px 0; padding: 24px; border: 1px solid var(--color-border-subtle); border-radius: 8px; background: var(--color-bg-surface); color: var(--color-primary); text-align: center; }
.commerce-alipay header { display: flex; align-items: center; justify-content: center; gap: 8px; }
.commerce-alipay-logo { display: block; width: 24px; height: 24px; flex-shrink: 0; }
.commerce-alipay-countdown { display: flex; align-items: baseline; justify-content: center; gap: 10px; margin: 18px 0 0; }
.commerce-alipay-countdown > span { color: var(--color-text-muted); font-size: 15px; }
.commerce-alipay-countdown > strong { color: var(--color-accent-text); font-size: 24px; font-weight: 600; line-height: 1.3; font-variant-numeric: tabular-nums; }
.commerce-alipay .public-button { margin: 4px; }
.commerce-alipay h3 { margin: 0; font-size: 17px; font-weight: 600; }
.commerce-alipay-label { margin: 20px 0 4px; color: var(--color-text-muted); font-size: 12px; }
.commerce-alipay-amount { margin: 0; font-size: 36px; font-weight: 650; line-height: 1.2; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.commerce-alipay-amount > span { margin-right: 4px; font-size: 21px; }
.commerce-alipay-order { margin: 10px 0 16px; color: var(--color-text-muted); font-size: 12px; overflow-wrap: anywhere; }
.commerce-alipay-order strong { color: var(--color-primary); font-weight: 500; user-select: all; }
.commerce-alipay-code { display: flex; justify-content: center; align-items: center; width: min(100%, 280px); min-height: 180px; margin: 0 auto; background: #fff; }
.commerce-alipay-code img { display: block; width: 100%; height: auto; aspect-ratio: 1; }
.commerce-alipay-code > p { padding: 20px; color: #984637; font-size: 13px; line-height: 1.7; }
.commerce-alipay-recipient { margin: 12px 0; font-size: 12px; overflow-wrap: anywhere; }
.commerce-alipay-download { display: inline-flex; min-height: 40px; align-items: center; justify-content: center; gap: 6px; color: var(--color-accent-text); font-size: 12px; text-decoration: none; }
.commerce-alipay-download:hover { text-decoration: underline; }
.commerce-alipay-download:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 3px; }
.commerce-alipay-instructions { display: grid; gap: 8px; margin: 16px 0 0; padding: 16px 0 0 18px; border-top: 1px solid var(--color-border-subtle); color: var(--color-text-muted); font-size: 12px; line-height: 1.8; text-align: left; }
.commerce-alipay-help { margin: 14px 0 0; color: var(--color-text-muted); font-size: 12px; line-height: 1.8; text-align: left; }
.commerce-alipay-help a { margin-left: 4px; color: var(--color-accent-text); white-space: nowrap; }
@media (max-width: 560px) { .commerce-alipay { padding: 20px 14px; } .commerce-alipay-amount { font-size: 32px; } }
</style>
