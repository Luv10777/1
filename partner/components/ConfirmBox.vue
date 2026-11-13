<script setup>
/**
 * 页内确认：提交前把这次要改什么、改完会怎样写清楚，再点确认。代替浏览器自带的 confirm 弹框。
 * danger 用于作废、移除这类不能撤销的操作。
 */
defineProps({
  busy: { type: Boolean, default: false },
  confirmText: { type: String, default: '确认' },
  danger: { type: Boolean, default: false },
})
defineEmits(['confirm', 'cancel'])
</script>

<template>
  <div class="partner-confirm" :class="{ danger }" role="alertdialog" aria-live="polite">
    <div class="partner-confirm-text"><slot /></div>
    <div class="partner-confirm-actions">
      <button class="mg-button" type="button" :disabled="busy" @click="$emit('cancel')">返回修改</button>
      <button class="mg-button" :class="danger ? 'mg-danger' : 'mg-primary'" type="button" :disabled="busy" @click="$emit('confirm')">{{ busy ? '正在提交…' : confirmText }}</button>
    </div>
  </div>
</template>
