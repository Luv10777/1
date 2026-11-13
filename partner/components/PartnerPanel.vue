<script setup>
import { Dialog, DialogDescription, DialogPanel, DialogTitle } from '@headlessui/vue'
import { X } from 'lucide-vue-next'

/**
 * 服务商后台的详情抽屉（右侧滑出）和弹窗（居中）。沿用管理后台「用户管理」的样式。
 * 操作进行中（busy）时不能关闭，避免提交到一半关掉看不到结果。
 */
const props = defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, default: '' },
  eyebrow: { type: String, default: '' },
  modal: { type: Boolean, default: false },
  busy: { type: Boolean, default: false },
})
const emit = defineEmits(['close'])
const close = () => { if (!props.busy) emit('close') }
</script>

<template>
  <Dialog :open="open" class="management-dialog" @close="close">
    <div class="mg-backdrop" aria-hidden="true" />
    <div :class="modal ? 'partner-modal-position' : 'mg-drawer-position'">
      <DialogPanel :class="['management-page', modal ? 'partner-modal' : 'mg-drawer']">
        <header class="mg-drawer-header">
          <div><span v-if="eyebrow">{{ eyebrow }}</span><DialogTitle as="h2">{{ title }}</DialogTitle></div>
          <button class="mg-page-button" type="button" :disabled="busy" aria-label="关闭" @click="close"><X :size="20" /></button>
        </header>
        <DialogDescription v-if="$slots.summary" as="div" class="mg-detail-description"><slot name="summary" /></DialogDescription>
        <div class="mg-drawer-body"><slot /></div>
        <footer v-if="$slots.actions" class="mg-drawer-actions"><slot name="actions" /></footer>
      </DialogPanel>
    </div>
  </Dialog>
</template>
