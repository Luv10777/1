<script setup>
import { computed, nextTick, ref } from 'vue'
import { materialLabels } from '../domain/videoMaterials'

defineOptions({ inheritAttrs: false })
const props = defineProps({
  images: { type: Array, default: () => [] },
  videos: { type: Array, default: () => [] },
  audios: { type: Array, default: () => [] },
  disabled: Boolean,
})
const model = defineModel({ type: String, default: '' })
const field = ref(null)
const open = ref(false)
const options = computed(() => Object.entries(materialLabels).flatMap(([type, label]) =>
  props[type + 's'].map((entry, index) => ({ entry, type, token: '@' + label + (index + 1) }))))
const focus = () => field.value?.focus()
defineExpose({ focus })

function justTypedAt() {
  const el = field.value
  return el.selectionStart === el.selectionEnd && /[@＠]$/.test(el.value.slice(0, el.selectionStart))
}
function insert(token) {
  const el = field.value
  const start = el.selectionStart - (justTypedAt() ? 1 : 0)
  const next = el.value.slice(0, start) + token + ' ' + el.value.slice(el.selectionEnd)
  if (el.maxLength >= 0 && next.length > el.maxLength) return
  model.value = next
  open.value = false
  nextTick(() => {
    focus()
    el.setSelectionRange(start + token.length + 1, start + token.length + 1)
  })
}
function keydown(event) {
  if (!open.value || !options.value.length || event.isComposing) return
  if (event.key === 'Escape' || event.key === 'Enter') {
    event.preventDefault()
    event.stopImmediatePropagation()
    if (event.key === 'Enter') insert(options.value[0].token)
    else open.value = false
  }
}
</script>

<template>
  <textarea ref="field" v-bind="$attrs" v-model="model" :disabled="disabled" @input="open = justTypedAt()" @click="open = justTypedAt()" @keydown.capture="keydown" />
  <div v-if="options.length" class="mention-tools">
    <button type="button" :disabled="disabled" :aria-expanded="open" @click="open = !open">@ 素材</button>
    <small>例如「@图片1 是老板，@图片2 是员工」</small>
    <div v-if="open && !disabled" class="mention-options" role="group" aria-label="选择要引用的素材">
      <button v-for="item in options" :key="item.token" type="button" @click="insert(item.token)">
        <img v-if="item.type === 'image' && item.entry.url" :src="item.entry.url" alt="">
        <strong>{{ item.token }}</strong><span>{{ item.entry.name }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.mention-tools { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 10px; font-size: 12px; margin: 4px 0; }
.mention-tools button { border: 1px solid currentColor; background: transparent; color: inherit; border-radius: 6px; padding: 4px 8px; cursor: pointer; }
.mention-options { flex-basis: 100%; display: grid; max-height: 180px; overflow-y: auto; gap: 4px; }
.mention-options button { display: flex; align-items: center; gap: 6px; text-align: left; min-width: 0; }
.mention-options span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mention-options img { width: 28px; height: 28px; object-fit: cover; border-radius: 4px; }
</style>
