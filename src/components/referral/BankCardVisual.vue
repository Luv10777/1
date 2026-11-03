<script setup>
import { Landmark } from 'lucide-vue-next'

// 收款银行卡的卡面：已绑定时显示尾号，绑定弹窗里跟着输入实时变化。
defineProps({
  bankName: { type: String, default: '' },
  number: { type: String, required: true },
  holder: { type: String, default: '' },
  compact: { type: Boolean, default: false },
})
</script>

<template>
  <div class="bank-visual" :class="{ compact }">
    <svg class="bank-visual-deco" viewBox="0 0 170 170" aria-hidden="true"><circle cx="100" cy="70" r="70" /><circle cx="140" cy="110" r="46" /></svg>
    <div class="bank-visual-top">
      <span><Landmark :size="16" aria-hidden="true" />{{ bankName || '开户银行' }}</span>
      <em>储蓄卡</em>
    </div>
    <i class="bank-visual-chip" aria-hidden="true" />
    <div class="bank-visual-number">{{ number }}</div>
    <div class="bank-visual-bottom">
      <div>持卡人<b>{{ holder || '本人实名' }}</b></div>
      <span>本人实名</span>
    </div>
  </div>
</template>

<style scoped>
.bank-visual { position: relative; overflow: hidden; box-sizing: border-box; display: flex; flex-direction: column; justify-content: space-between; width: 100%; max-width: 300px; aspect-ratio: 1.586; padding: 18px 20px; border-radius: 12px; color: #fff; background: #0f172a; }
.bank-visual.compact { max-width: 100%; padding: 14px 16px; }
.bank-visual-deco { position: absolute; top: -30px; right: -30px; width: 170px; height: 170px; fill: #0e766e; opacity: .35; pointer-events: none; }
.bank-visual-top { position: relative; display: flex; justify-content: space-between; align-items: center; font-size: 14px; }
.bank-visual-top span { display: flex; align-items: center; gap: 6px; min-width: 0; }
.bank-visual-top em { flex: none; padding: 2px 8px; border-radius: 99px; background: rgb(255 255 255 / 14%); font-size: 11px; font-style: normal; }
.bank-visual-chip { position: relative; width: 36px; height: 27px; border-radius: 5px; background: #c9a85c; }
.bank-visual-number { position: relative; font-family: var(--font-mono, ui-monospace, monospace); font-size: 18px; letter-spacing: .1em; white-space: nowrap; }
.compact .bank-visual-number { font-size: 15px; }
.bank-visual-bottom { position: relative; display: flex; justify-content: space-between; align-items: flex-end; color: #cbd5e1; font-size: 12px; }
.bank-visual-bottom b { display: block; color: #fff; font-size: 14px; font-weight: 500; }
</style>
