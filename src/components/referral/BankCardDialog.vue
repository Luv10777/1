<script setup>
import { computed, reactive, watch } from 'vue'
import { Dialog, DialogDescription, DialogPanel, DialogTitle } from '@headlessui/vue'
import { CircleAlert, CircleCheck, CreditCard, Landmark, Lock, Smartphone, X } from 'lucide-vue-next'
import BankCardVisual from './BankCardVisual.vue'
import { formatCardNumber } from '../../domain/referral'
import { LEGAL_ENTITY } from '../../domain/legalPolicies'

// 绑定本人银行卡：四要素（姓名、身份证号取自实名；卡号、预留手机号在这里填）一起向银行核验。校验和提交由父组件负责。
const props = defineProps({
  open: { type: Boolean, default: false },
  binding: { type: Boolean, default: false },
  error: { type: String, default: '' },
  mismatch: { type: Boolean, default: false },
  holder: { type: String, default: '' },
})
const emit = defineEmits(['submit', 'close'])

const form = reactive({ bankName: '', cardNumber: '', mobile: '', declarationAccepted: false })

watch(() => props.open, value => {
  if (value) Object.assign(form, { bankName: '', cardNumber: '', mobile: '', declarationAccepted: false })
})

const digits = computed(() => form.cardNumber.replace(/ /g, ''))
const numberShown = computed(() => (digits.value.length > 16 ? form.cardNumber : (digits.value + '•'.repeat(16)).slice(0, 16).replace(/(.{4})(?=.)/g, '$1 ')))
const cardOk = computed(() => /^\d{12,19}$/.test(digits.value))
const mobileOk = computed(() => /^1\d{10}$/.test(form.mobile.trim()))

function onCardNumber(event) {
  form.cardNumber = formatCardNumber(event.target.value)
  event.target.value = form.cardNumber
}
function close() {
  if (!props.binding) emit('close')
}
</script>

<template>
  <Dialog :open="open" class="bank-dialog" @close="close">
    <div class="bank-backdrop" aria-hidden="true" />
    <div class="bank-position">
      <DialogPanel class="bank-panel">
        <form autocomplete="off" @submit.prevent="emit('submit', { ...form })">
          <div class="bank-head">
            <span class="bank-icon" aria-hidden="true"><Landmark :size="24" /></span>
            <div>
              <DialogTitle as="h2">绑定收款银行卡</DialogTitle>
              <DialogDescription as="p">银行会核验这张卡是否属于你本人</DialogDescription>
            </div>
            <button type="button" class="bank-close" aria-label="关闭" :disabled="binding" @click="close"><X :size="20" aria-hidden="true" /></button>
          </div>

          <div v-if="mismatch || error" class="bank-error" role="alert">
            <CircleAlert :size="18" aria-hidden="true" />
            <div>
              <template v-if="mismatch">银行卡与本人实名信息或银行预留手机号核验不一致，请核对后重试，或换一张本人银行卡。<small>如有疑问请拨打人工客服：<a :href="`tel:${LEGAL_ENTITY.phone}`">{{ LEGAL_ENTITY.phone }}</a></small></template>
              <template v-else>{{ error }}</template>
            </div>
          </div>

          <div class="bank-body">
            <div>
              <BankCardVisual compact :bank-name="form.bankName.trim()" :number="numberShown" :holder="holder" />
              <ul class="bank-checks">
                <li><CircleCheck :size="15" aria-hidden="true" />姓名，取自实名</li>
                <li><CircleCheck :size="15" aria-hidden="true" />身份证号，取自实名</li>
                <li :class="{ off: !cardOk }"><CircleCheck :size="15" aria-hidden="true" />银行卡号</li>
                <li :class="{ off: !mobileOk }"><CircleCheck :size="15" aria-hidden="true" />银行预留手机号，仅用于本次核验，不保存</li>
              </ul>
            </div>
            <div class="bank-fields">
              <label class="bank-field">
                <span>持卡人<small>来自实名认证，不可修改</small></span>
                <span class="bank-input readonly"><Lock :size="18" aria-hidden="true" /><input :value="holder || '本人实名'" readonly tabindex="-1" /></span>
              </label>
              <label class="bank-field">
                <span>开户银行</span>
                <span class="bank-input"><Landmark :size="18" aria-hidden="true" /><input v-model="form.bankName" maxlength="40" autocomplete="off" placeholder="例如：中国工商银行" :disabled="binding" /></span>
              </label>
              <label class="bank-field">
                <span>银行卡号</span>
                <span class="bank-input"><CreditCard :size="18" aria-hidden="true" /><input :value="form.cardNumber" inputmode="numeric" autocomplete="off" placeholder="本人名下的储蓄卡" :disabled="binding" @input="onCardNumber" /></span>
              </label>
              <label class="bank-field">
                <span>银行预留手机号</span>
                <span class="bank-input"><Smartphone :size="18" aria-hidden="true" /><input v-model="form.mobile" inputmode="numeric" maxlength="11" autocomplete="off" placeholder="办卡时留给银行的号码" :disabled="binding" /></span>
              </label>
            </div>
          </div>

          <label class="bank-consent">
            <input v-model="form.declarationAccepted" type="checkbox" :disabled="binding" />
            <span>我确认这是本人名下的银行卡，并单独同意平台把我实名的姓名、身份证号码和这张卡的卡号、银行预留手机号，经阿里云实人认证服务（客服电话 95187）提交银行核验是否一致，仅用于返利提现收款。</span>
          </label>
          <div class="bank-actions">
            <button type="button" class="ghost-button" :disabled="binding" @click="close">取消</button>
            <button type="submit" class="primary-button" :disabled="binding">{{ binding ? '正在核验…' : '核验并绑定' }}</button>
          </div>
        </form>
      </DialogPanel>
    </div>
  </Dialog>
</template>

<style scoped>
:global(.bank-dialog) { position: fixed; inset: 0; z-index: 1100; }
.bank-backdrop { position: fixed; inset: 0; background: rgb(15 23 42 / 50%); }
.bank-position { position: fixed; inset: 0; display: grid; place-items: center; overflow-y: auto; padding: 24px 16px; }
.bank-panel { width: min(100%, 620px); box-sizing: border-box; padding: 26px 28px; border: 1px solid var(--color-border-subtle); border-radius: 12px; background: var(--color-bg-surface); color: var(--color-primary); font-family: var(--font-sans); }
.bank-head { display: flex; align-items: center; gap: 14px; }
.bank-head > div { flex: 1; min-width: 0; }
.bank-head h2 { margin: 0; font-family: var(--font-serif); font-size: 18px; font-weight: 600; }
.bank-head p { margin: 2px 0 0; color: var(--color-text-muted); font-size: 12px; }
.bank-icon { flex: none; display: grid; place-items: center; width: 44px; height: 44px; border-radius: 10px; color: var(--color-accent-text); background: color-mix(in srgb, var(--color-accent) 10%, var(--color-bg-surface)); }
.bank-close { align-self: flex-start; padding: 4px; border: 0; background: transparent; color: var(--color-text-muted); cursor: pointer; }
.bank-close:hover:not(:disabled) { color: var(--color-primary); }
.bank-error { display: flex; gap: 10px; margin-top: 18px; padding: 12px 14px; border-radius: 6px; color: var(--color-error); background: color-mix(in srgb, var(--color-error) 8%, var(--color-bg-surface)); font-size: 13px; line-height: 1.7; }
.bank-error svg { flex: none; margin-top: 2px; }
.bank-error small { display: block; margin-top: 2px; color: var(--color-text-muted); font-size: 12px; }
.bank-error a { color: var(--color-accent-text); }
.bank-body { display: grid; grid-template-columns: 240px minmax(0, 1fr); gap: 26px; margin-top: 20px; }
.bank-checks { display: grid; gap: 7px; margin: 14px 0 0; padding: 0; list-style: none; color: var(--color-text-muted); font-size: 12px; }
.bank-checks li { display: flex; gap: 7px; align-items: flex-start; }
.bank-checks svg { flex: none; margin-top: 1px; color: var(--color-success); }
.bank-checks li.off svg { color: var(--color-border-strong); }
.bank-fields { display: grid; gap: 16px; align-content: start; }
.bank-field { display: grid; gap: 6px; font-size: 13px; font-weight: 700; }
.bank-field > span:first-child { display: flex; justify-content: space-between; gap: 8px; }
.bank-field small { color: var(--color-text-muted); font-size: 12px; font-weight: 400; }
.bank-input { position: relative; display: block; }
.bank-input svg { position: absolute; top: 50%; left: 12px; transform: translateY(-50%); color: var(--color-text-muted); pointer-events: none; }
.bank-input input { width: 100%; min-height: 44px; box-sizing: border-box; padding: 0 12px 0 40px; border: 1px solid var(--color-border-strong); border-radius: 6px; background: var(--color-bg-surface); color: var(--color-primary); font: 400 15px var(--font-sans); }
.bank-input input:focus { outline: 0; border-color: var(--color-accent); box-shadow: 0 0 0 3px color-mix(in srgb, var(--color-accent) 15%, transparent); }
.bank-input input:disabled, .bank-input.readonly input { border-color: var(--color-border-subtle); background: var(--color-bg-subtle); }
.bank-consent { display: flex; gap: 10px; align-items: flex-start; margin-top: 18px; padding: 12px; border-radius: 6px; background: var(--color-bg-subtle); color: var(--color-text-muted); font-size: 12px; line-height: 1.75; }
.bank-consent input { flex: none; width: 16px; height: 16px; margin-top: 4px; accent-color: var(--color-accent); }
.bank-actions { display: flex; justify-content: flex-end; gap: 12px; margin-top: 22px; }
.bank-actions .ghost-button { padding: 0 18px; border: 1px solid var(--color-border-strong); border-radius: 4px; color: var(--color-primary); font-size: 13px; }
.bank-actions .ghost-button:hover:not(:disabled) { background: var(--color-bg-subtle); }
@media (max-width: 640px) {
  .bank-panel { padding: 20px 16px; }
  .bank-body { grid-template-columns: 1fr; gap: 18px; }
}
</style>
