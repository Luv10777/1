<script setup>
import { billing, number } from '../stores/billing'

defineProps({ disabled: Boolean })
const wallet = defineModel({ type: String, required: true })
</script>

<template>
  <div v-if="billing.summary?.generationTeam" class="wallet-picker" role="group" aria-label="选择创作扣费余额">
    <button type="button" :disabled="disabled" :aria-pressed="wallet === 'PERSONAL'" @click="wallet = 'PERSONAL'">
      <span>个人余额</span><strong>{{ number(billing.summary.available) }} 烟火</strong>
    </button>
    <button type="button" :disabled="disabled" :aria-pressed="wallet === 'TEAM'" :title="billing.summary.generationTeam.name" @click="wallet = 'TEAM'">
      <span>团队余额</span><strong>{{ number(billing.summary.generationTeam.available) }} 烟火</strong>
    </button>
  </div>
</template>

<style scoped>
.wallet-picker { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin-block: 8px; }
.wallet-picker button { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 2px 8px; padding: 7px 9px; border: 1px solid var(--color-border, #d8dfe5); border-radius: 8px; background: var(--color-surface, #fff); color: inherit; font: inherit; font-size: 12px; text-align: left; }
.wallet-picker button[aria-pressed="true"] { border-color: var(--color-accent, #0e766e); box-shadow: inset 0 0 0 1px var(--color-accent, #0e766e); }
.wallet-picker button:focus-visible { outline: 2px solid var(--color-accent, #0e766e); outline-offset: 2px; }
.wallet-picker button:disabled { opacity: .65; }
.wallet-picker strong { font-variant-numeric: tabular-nums; }
</style>
