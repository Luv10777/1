<script setup>
import { nextTick, ref, watch } from 'vue'
const props = defineProps({ open: Boolean, title: { type: String, required: true }, busy: Boolean })
const emit = defineEmits(['close'])
const dialog = ref(null)
watch(() => props.open, async open => { await nextTick(); if (open && !dialog.value.open) dialog.value.showModal(); else if (!open && dialog.value.open) dialog.value.close() }, { immediate: true })
function close() { if (!props.busy) emit('close') }
</script>
<template>
  <dialog ref="dialog" class="bo-dialog" aria-labelledby="bo-dialog-title" @cancel.prevent="close" @click="event => { if (event.target === dialog) close() }">
    <header><h2 id="bo-dialog-title">{{ title }}</h2><button class="mg-button" type="button" :disabled="busy" aria-label="关闭详情" @click="close">关闭</button></header>
    <div class="bo-dialog-body"><slot /></div>
  </dialog>
</template>
<style scoped>
.bo-dialog { padding: 0; width: 720px; max-width: calc(100vw - 32px); max-height: calc(100vh - 48px); border: 1px solid #dfe5e2; border-radius: 12px; color: inherit; background: #fff; }
.bo-dialog::backdrop { background: rgb(20 32 29 / 40%); }
.bo-dialog header { display: flex; justify-content: space-between; align-items: center; gap: 20px; padding: 18px 24px; border-bottom: 1px solid #e5e9e7; }
.bo-dialog h2 { font-size: 18px; margin: 0; }.bo-dialog-body { padding: 24px; }
</style>
