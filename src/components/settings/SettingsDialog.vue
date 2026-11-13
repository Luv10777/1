<script setup>
import { Dialog, DialogDescription, DialogPanel, DialogTitle, TransitionChild, TransitionRoot } from '@headlessui/vue'
import { X } from 'lucide-vue-next'

// 设置页里改密码、换手机号、绑微信共用的弹窗外壳。忙的时候点遮罩、按 Esc 都不关，免得请求做到一半被打断。
const props = defineProps({
  open: { type: Boolean, required: true },
  title: { type: String, required: true },
  description: { type: String, default: '' },
  busy: { type: Boolean, default: false },
})
const emit = defineEmits(['close', 'after-leave'])
function close() { if (!props.busy) emit('close') }
</script>

<template>
  <TransitionRoot appear :show="open" as="template" @after-leave="emit('after-leave')">
    <Dialog as="div" class="sd-root" @close="close">
      <TransitionChild as="template" enter="sd-fade-active" enter-from="sd-fade-from" enter-to="sd-fade-to" leave="sd-fade-active" leave-from="sd-fade-to" leave-to="sd-fade-from">
        <div class="sd-backdrop" aria-hidden="true" />
      </TransitionChild>
      <div class="sd-position">
        <TransitionChild as="template" enter="sd-pop-active" enter-from="sd-pop-from" enter-to="sd-pop-to" leave="sd-pop-leave" leave-from="sd-pop-to" leave-to="sd-pop-from">
          <DialogPanel class="sd-panel">
            <header class="sd-header">
              <div>
                <DialogTitle as="h2">{{ title }}</DialogTitle>
                <DialogDescription v-if="description" as="p">{{ description }}</DialogDescription>
              </div>
              <button type="button" class="sd-close" :disabled="busy" aria-label="关闭" @click="close"><X :size="18" /></button>
            </header>
            <slot />
          </DialogPanel>
        </TransitionChild>
      </div>
    </Dialog>
  </TransitionRoot>
</template>

<style>
/* 弹窗挂在 body 下，样式不能用 scoped；统一用 sd- 前缀。 */
.sd-root { position: relative; z-index: 90; font-family: var(--font-sans); color: var(--color-primary); }
.sd-backdrop { position: fixed; inset: 0; background: color-mix(in srgb, #14181c 42%, transparent); backdrop-filter: blur(3px); }
.sd-position { position: fixed; inset: 0; display: flex; align-items: center; justify-content: center; padding: 20px; overflow-y: auto; }
.sd-panel { width: min(460px, 100%); max-height: calc(100dvh - 40px); overflow-x: hidden; overflow-y: auto; border: 1px solid var(--color-border-subtle); border-radius: 12px; background: var(--color-bg-surface); box-shadow: var(--shadow-paper-elevated); }
.sd-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 22px 24px 4px; }
.sd-header h2 { margin: 0; font: 750 20px/1.4 var(--font-serif); color: var(--color-primary); }
.sd-header p { margin: 6px 0 0; color: var(--color-text-muted); font-size: 13px; line-height: 1.7; }
.sd-close { flex: none; display: grid; place-items: center; width: 32px; height: 32px; margin: -4px -8px 0 0; border: 0; border-radius: 8px; color: var(--color-text-muted); background: transparent; cursor: pointer; transition: background-color .15s, color .15s; }
.sd-close:hover:not(:disabled) { color: var(--color-primary); background: var(--color-bg-subtle); }
.sd-body { display: grid; gap: 18px; padding: 18px 24px 4px; }
.sd-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 18px 24px 22px; }

.sd-fade-active { transition: opacity .2s ease; }
.sd-fade-from { opacity: 0; }
.sd-fade-to { opacity: 1; }
.sd-pop-active { transition: opacity .22s ease, transform .26s cubic-bezier(.2, .9, .3, 1.1); }
.sd-pop-leave { transition: opacity .15s ease, transform .15s ease; }
.sd-pop-from { opacity: 0; transform: translateY(10px) scale(.97); }
.sd-pop-to { opacity: 1; transform: none; }

/* 表单 */
.sd-field { display: grid; gap: 8px; }
.sd-field > label, .sd-field > .sd-label { color: var(--color-primary); font-size: 13px; font-weight: 700; }
.sd-input { position: relative; display: flex; align-items: center; }
.sd-input input { width: 100%; height: 44px; box-sizing: border-box; padding: 0 14px; border: 1px solid var(--color-border-subtle); border-radius: 8px; outline: 0; color: var(--color-primary); background: var(--color-bg-canvas); font: 500 14px/1.5 var(--font-sans); transition: border-color .15s, box-shadow .15s, background-color .15s; }
.sd-input input::placeholder { color: var(--color-text-muted); opacity: .75; }
.sd-input input:hover:not(:disabled) { border-color: var(--color-border-strong, var(--color-border-subtle)); }
.sd-input input:focus { border-color: var(--color-accent); background: var(--color-bg-surface); box-shadow: 0 0 0 3px color-mix(in srgb, var(--color-accent) 16%, transparent); }
.sd-input input:disabled { color: var(--color-text-muted); }
.sd-input input.has-toggle { padding-right: 44px; }
.sd-input .sd-eye { position: absolute; right: 6px; display: grid; place-items: center; width: 32px; height: 32px; border: 0; border-radius: 6px; color: var(--color-text-muted); background: transparent; cursor: pointer; }
.sd-input .sd-eye:hover { color: var(--color-primary); }
.sd-input .sd-prefix { position: absolute; left: 14px; color: var(--color-text-muted); font-size: 14px; pointer-events: none; }
.sd-input .sd-prefix + input { padding-left: 52px; }
.sd-code { display: flex; gap: 10px; }
.sd-code .sd-input { flex: 1; }
.sd-code .sd-input input { letter-spacing: .18em; font-family: var(--font-mono); }
.sd-code .sd-input input::placeholder { letter-spacing: 0; font-family: var(--font-sans); }
.sd-hint { margin: 0; color: var(--color-text-muted); font-size: 12px; line-height: 1.6; }
.sd-hint.ok { color: var(--color-success); }
.sd-hint.error { color: var(--color-error); }

.sd-segment { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: 4px; padding: 4px; border-radius: 10px; background: var(--color-bg-subtle); }
.sd-segment button { height: 34px; border: 0; border-radius: 7px; color: var(--color-text-muted); background: transparent; font: 700 13px var(--font-sans); cursor: pointer; transition: background-color .18s, color .18s, box-shadow .18s; }
.sd-segment button:hover:not([aria-pressed="true"]):not(:disabled) { color: var(--color-primary); }
.sd-segment button[aria-pressed="true"] { color: var(--color-primary); background: var(--color-bg-surface); box-shadow: 0 1px 3px color-mix(in srgb, var(--color-primary) 14%, transparent); }

.sd-note { display: flex; gap: 10px; margin: 0; padding: 12px 14px; border-radius: 8px; color: var(--color-text-muted); background: var(--color-bg-subtle); font-size: 12px; line-height: 1.7; }
.sd-note svg { flex: none; margin-top: 2px; color: var(--color-warning); }
.sd-error { margin: 0; padding: 10px 12px; border-radius: 8px; color: var(--color-error); background: color-mix(in srgb, var(--color-error) 8%, var(--color-bg-surface)); font-size: 13px; line-height: 1.6; }

.sd-btn { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-width: 88px; height: 40px; padding: 0 18px; border: 1px solid var(--color-border-subtle); border-radius: 8px; color: var(--color-primary); background: transparent; font: 700 13px var(--font-sans); white-space: nowrap; cursor: pointer; transition: background-color .15s, border-color .15s, transform .1s; }
.sd-btn:hover:not(:disabled) { background: var(--color-bg-subtle); }
.sd-btn:active:not(:disabled) { transform: translateY(1px); }
.sd-btn.primary { border-color: var(--color-accent); color: var(--color-on-accent); background: var(--color-accent); }
.sd-btn.primary:hover:not(:disabled) { background: var(--color-accent-hover); }
.sd-btn:disabled { opacity: .55; cursor: default; }
.sd-code .sd-btn { min-width: 112px; height: 44px; }

.sd-steps { display: flex; align-items: center; gap: 10px; margin: 0; padding: 0; list-style: none; font-size: 12px; color: var(--color-text-muted); }
.sd-steps li { display: flex; align-items: center; gap: 8px; }
.sd-steps li span { display: grid; place-items: center; width: 22px; height: 22px; border-radius: 50%; background: var(--color-bg-subtle); font-weight: 700; transition: background-color .2s, color .2s; }
.sd-steps li.on { color: var(--color-primary); font-weight: 700; }
.sd-steps li.on span, .sd-steps li.done span { color: var(--color-on-accent); background: var(--color-accent); }
.sd-steps i { flex: 1; height: 1px; background: var(--color-border-subtle); }

.sd-done { display: grid; justify-items: center; gap: 10px; padding: 10px 0 6px; text-align: center; }
.sd-done-icon { display: grid; place-items: center; width: 52px; height: 52px; border-radius: 50%; color: var(--color-success); background: color-mix(in srgb, var(--color-success) 12%, var(--color-bg-surface)); animation: sd-pop-in .35s cubic-bezier(.2, .9, .3, 1.3); }
.sd-done strong { font-size: 16px; }
.sd-done p { margin: 0; color: var(--color-text-muted); font-size: 13px; line-height: 1.7; }
@keyframes sd-pop-in { from { transform: scale(.6); opacity: 0; } to { transform: none; opacity: 1; } }

.sd-step-enter-active, .sd-step-leave-active { transition: opacity .18s ease, transform .2s ease; }
.sd-step-enter-from { opacity: 0; transform: translateX(14px); }
.sd-step-leave-to { opacity: 0; transform: translateX(-14px); }

@media (max-width: 520px) {
  .sd-position { align-items: flex-end; padding: 0; }
  .sd-panel { width: 100%; max-height: 92dvh; border-radius: 14px 14px 0 0; }
  .sd-pop-from { transform: translateY(32px); }
  .sd-footer .sd-btn { flex: 1; }
}
@media (prefers-reduced-motion: reduce) {
  .sd-fade-active, .sd-pop-active, .sd-pop-leave, .sd-step-enter-active, .sd-step-leave-active { transition: none; }
  .sd-done-icon { animation: none; }
}
</style>
