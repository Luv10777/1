<script setup>
import { computed } from 'vue'
import { number } from '../../src/domain/partnerOps.js'
import { partner } from '../partnerStore.js'
import OptionCards from './OptionCards.vue'

/**
 * 分烟火时选从哪里扣：服务商的可分配烟火（平台发放）或负责人自己账号里的烟火（充值和赠送都能分）。
 * 只给负责人用。某个来源余额为 0 时卡片置灰。
 */
const props = defineProps({ modelValue: { type: String, default: 'POOL' }, disabled: { type: Boolean, default: false }, allowPool: { type: Boolean, default: true } })
const emit = defineEmits(['update:modelValue'])
const pool = computed(() => partner.overview?.partner.creditsAvailable || 0)
const account = computed(() => partner.overview?.account || { available: 0, gift: 0, purchased: 0 })
const options = computed(() => [
  ...(props.allowPool ? [{ value: 'POOL', title: `服务商可分配烟火 · ${number(pool.value)}`, detail: '平台发放给服务商的烟火，对方收到的是充值烟火', disabled: pool.value < 1 }] : []),
  { value: 'ACCOUNT', title: `我的账号 · ${number(account.value.available)}`, detail: `你自己一方志账号里的烟火（赠送 ${number(account.value.gift)}、充值 ${number(account.value.purchased)}），先分赠送的，对方收到的性质不变`, disabled: account.value.available < 1 },
])
</script>

<template>
  <p class="partner-field-label">从哪里扣</p>
  <OptionCards :model-value="modelValue" :options="options" label="烟火来源" :disabled="disabled" @update:model-value="value => emit('update:modelValue', value)" />
</template>
