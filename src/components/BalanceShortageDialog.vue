<script setup>
import { RouterLink } from 'vue-router'
import YanhuoIcon from './YanhuoIcon.vue'
import { number } from '../stores/billing'

defineProps({
  open: Boolean,
  required: { type: Number, default: 0 },
  available: { type: Number, default: 0 },
  walletLabel: { type: String, default: '个人额度' },
})

const emit = defineEmits(['close', 'update:open'])
function close() {
  emit('update:open', false)
  emit('close')
}
</script>

<template>
  <Transition name="balance-shortage">
    <div v-if="open" class="balance-shortage-backdrop" role="presentation" @click.self="close">
      <section class="balance-shortage-dialog" role="dialog" aria-modal="true" aria-labelledby="balance-shortage-title">
        <button type="button" class="balance-shortage-close" aria-label="关闭余额提示" @click="close">×</button>
        <span class="balance-shortage-icon" aria-hidden="true"><YanhuoIcon :size="22" /></span>
        <h2 id="balance-shortage-title">余额不足</h2>
        <p>本次需要 <strong>{{ number(required) }}</strong> 烟火，{{ walletLabel }}还剩 <strong>{{ number(available) }}</strong> 烟火。</p>
        <div class="balance-shortage-actions">
          <button type="button" class="balance-shortage-secondary" @click="close">先不充值</button>
          <RouterLink class="balance-shortage-primary" to="/billing?topup=1" @click="close">立即充值</RouterLink>
        </div>
      </section>
    </div>
  </Transition>
</template>

<style scoped>
.balance-shortage-backdrop { position: fixed; z-index: 100; inset: 0; display: grid; place-items: center; padding: 20px; background: rgb(15 23 42 / 28%); backdrop-filter: blur(3px); }
.balance-shortage-dialog { position: relative; width: min(360px, 100%); box-sizing: border-box; padding: 28px 24px 22px; border: 1px solid var(--line, #d8dfe5); border-radius: 18px; background: var(--color-bg-surface, #fff); color: var(--color-primary, #122033); box-shadow: 0 24px 64px rgb(15 23 42 / 22%); text-align: center; }
.balance-shortage-close { position: absolute; top: 10px; right: 12px; width: 28px; height: 28px; padding: 0; border: 0; border-radius: 50%; background: transparent; color: var(--ink-muted, #718096); font-size: 22px; line-height: 1; cursor: pointer; }
.balance-shortage-close:hover { background: var(--color-bg-subtle, #f1f4f7); }
.balance-shortage-icon { display: grid; place-items: center; width: 44px; height: 44px; margin: 0 auto 12px; border-radius: 50%; background: color-mix(in srgb, var(--color-accent, #0e766e) 12%, transparent); color: var(--color-accent-text, #0e766e); }
.balance-shortage-dialog h2 { margin: 0 0 8px; font: 600 20px/1.35 var(--font-serif, serif); }
.balance-shortage-dialog p { margin: 0; color: var(--ink-muted, #718096); font-size: 14px; line-height: 1.75; }
.balance-shortage-dialog p strong { color: var(--color-primary, #122033); font-variant-numeric: tabular-nums; }
.balance-shortage-actions { display: flex; justify-content: center; gap: 10px; margin-top: 22px; }
.balance-shortage-actions button, .balance-shortage-actions a { display: inline-flex; align-items: center; justify-content: center; min-height: 38px; padding: 0 16px; border-radius: 19px; font: inherit; font-size: 13px; text-decoration: none; cursor: pointer; }
.balance-shortage-secondary { border: 1px solid var(--line, #d8dfe5); background: transparent; color: inherit; }
.balance-shortage-primary { border: 0; background: var(--color-primary, #122033); color: var(--color-bg-surface, #fff); }
.balance-shortage-primary:hover { opacity: .9; }
.balance-shortage-enter-active, .balance-shortage-leave-active { transition: opacity .18s ease; }
.balance-shortage-enter-active .balance-shortage-dialog, .balance-shortage-leave-active .balance-shortage-dialog { transition: transform .18s ease, opacity .18s ease; }
.balance-shortage-enter-from, .balance-shortage-leave-to { opacity: 0; }
.balance-shortage-enter-from .balance-shortage-dialog, .balance-shortage-leave-to .balance-shortage-dialog { opacity: 0; transform: translateY(8px) scale(.98); }
</style>
