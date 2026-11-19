<script setup>
import { billing, number } from '../stores/billing'

defineProps({ disabled: Boolean, compact: Boolean })
const wallet = defineModel({ type: String, required: true })
</script>

<template>
  <div v-if="billing.summary?.generationTeam" class="wallet-picker" :class="{ compact }" role="group" aria-label="选择创作扣费余额">
    <button type="button" :disabled="disabled" :aria-pressed="wallet === 'PERSONAL'" @click="wallet = 'PERSONAL'">
      <span>个人额度<span class="wallet-legacy sr-only">个人余额</span></span><strong>{{ number(billing.summary.available) }} 烟火</strong>
    </button>
    <button type="button" :disabled="disabled" :aria-pressed="wallet === 'TEAM'" :title="billing.summary.generationTeam.name" @click="wallet = 'TEAM'">
      <span>团队额度<span class="wallet-legacy sr-only">团队余额</span></span><strong>{{ number(billing.summary.generationTeam.available) }} 烟火</strong>
    </button>
  </div>
</template>

<style scoped>
.wallet-picker { display: inline-grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0; margin-block: 8px; border: 1px solid var(--color-border, #d8dfe5); border-radius: 10px; overflow: hidden; background: var(--color-bg-subtle, #f5f7f9); }
.wallet-picker button { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-width: 108px; min-height: 34px; padding: 0 10px; border: 0; border-right: 1px solid var(--color-border, #d8dfe5); background: transparent; color: var(--color-text-muted, #718096); font: inherit; font-size: 12px; text-align: left; cursor: pointer; }
.wallet-picker button:last-child { border-right: 0; }
.wallet-picker button[aria-pressed="true"] { background: var(--color-bg-surface, #fff); color: inherit; box-shadow: inset 0 0 0 1.5px var(--color-accent, #0e766e); }
.wallet-picker button:focus-visible { outline: 2px solid var(--color-accent, #0e766e); outline-offset: 2px; }
.wallet-picker button:disabled { opacity: .65; }
.wallet-picker strong { font-variant-numeric: tabular-nums; }
.wallet-picker.compact { margin: 0; border-radius: 20px; }
.wallet-picker.compact button { min-width: 92px; min-height: 32px; padding-inline: 10px; font-size: 11px; }
@media (max-width: 520px) { .wallet-picker.compact button { min-width: 0; padding-inline: 8px; } .wallet-picker.compact button span { max-width: 42px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } }
</style>
