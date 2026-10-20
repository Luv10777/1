<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  method: { type: String, default: '' },
  available: { type: Boolean, default: false },
  canManage: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  error: { type: String, default: '' },
})
const consent = defineModel({ type: Boolean, default: false })
const input = ref(null)
const methods = { ALIPAY: { name: '支付宝', logo: '/images/payments/alipay.svg' }, WECHAT: { name: '微信支付' }, MANUAL: { name: '对公转账' } }
const current = computed(() => methods[props.method] || { name: props.method })
defineExpose({ focus: () => input.value?.focus() })
</script>

<template>
  <div class="yanhuo-checkout">
    <fieldset v-if="canManage && available" class="yanhuo-pay-methods">
      <legend>支付方式</legend>
      <label class="selected"><input type="radio" checked :value="method"><img v-if="current.logo" :src="current.logo" alt="" width="22" height="22"><span>{{ current.name }}</span></label>
    </fieldset>
    <p v-if="!canManage" class="yanhuo-note">充值与订阅由账户管理员办理。</p>
    <p v-else-if="!available" class="yanhuo-note">在线收款暂未开放。</p>
    <div class="yanhuo-checkout-row">
      <label class="yanhuo-legal-consent"><input ref="input" v-model="consent" type="checkbox" :disabled="disabled" :aria-invalid="Boolean(error && !consent)" /><span>我已阅读并同意<a href="/policies/terms" target="_blank" rel="noopener noreferrer">《用户注册协议》</a>、<a href="/policies/privacy" target="_blank" rel="noopener noreferrer">《隐私协议》</a>和<a href="/policies/aftersales" target="_blank" rel="noopener noreferrer">《售后与退款规则》</a>，确认按所选金额、套餐期限、权益和订单信息购买。</span></label>
      <slot />
    </div>
    <p v-if="error" class="yanhuo-alert" role="alert">{{ error }}</p>
  </div>
</template>
