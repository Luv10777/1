<script setup>
import { computed, ref, watch } from 'vue'
import { Download, ScanLine } from 'lucide-vue-next'
import { LEGAL_ENTITY } from '../domain/legalPolicies'

const props = defineProps({ order: { type: Object, required: true } })
const imageError = ref(false)
const payable = computed(() => props.order.status === 'PENDING_CONFIRMATION'
  && props.order.payment?.method === 'ALIPAY_MANUAL' && Number(props.order.totalAmount) > 0)
const amount = computed(() => Number(props.order.totalAmount).toLocaleString('zh-CN', {
  minimumFractionDigits: 2, maximumFractionDigits: 2,
}))

watch(() => props.order.payment?.qrCodeUrl, () => { imageError.value = false })
</script>

<template>
  <section v-if="payable" class="commerce-alipay" aria-label="企业支付宝付款">
    <header><ScanLine :size="20" aria-hidden="true" /><h3>支付宝扫码付款</h3></header>
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
.commerce-alipay header svg { color: #1677ff; }
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
