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
    <button class="mention-trigger" type="button" :disabled="disabled" :aria-expanded="open" aria-haspopup="listbox" @click="open = !open"><span class="mention-trigger-mark">@</span><span>引用素材</span></button>
    <small>标注身份或用途，例如 <b>@图片1 是老板</b></small>
    <div v-if="open && !disabled" class="mention-options" role="group" aria-label="选择要引用的素材">
      <button v-for="item in options" :key="item.token" type="button" role="option" @click="insert(item.token)">
        <img v-if="item.type === 'image' && item.entry.url" :src="item.entry.url" alt="">
        <span class="mention-option-token">{{ item.token }}</span><span class="mention-option-name">{{ item.entry.name || '未命名素材' }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.mention-tools { display: flex; flex-wrap: wrap; align-items: center; gap: 7px 10px; margin: 8px 0 2px; color: #708096; font-size: 12px; line-height: 1.4; }
.mention-trigger { display: inline-flex; align-items: center; gap: 5px; height: 29px; padding: 0 10px 0 7px; border: 1px solid #c8d9e9; border-radius: 8px; background: #f4f8fc; color: #27577f; font: 600 12px/1 var(--font-sans, inherit); cursor: pointer; transition: border-color .18s ease, background .18s ease, box-shadow .18s ease; }
.mention-trigger:hover, .mention-trigger[aria-expanded="true"] { border-color: #8eb5d4; background: #eaf3fa; box-shadow: 0 2px 8px rgb(42 93 132 / 10%); }
.mention-trigger:disabled { cursor: not-allowed; opacity: .55; }
.mention-trigger-mark { display: grid; place-items: center; width: 17px; height: 17px; border-radius: 5px; background: #d9ebf8; color: #21628f; font-size: 13px; font-weight: 700; }
.mention-tools small { color: #8997a8; }
.mention-tools small b { color: #617488; font-weight: 600; }
.mention-options { flex-basis: 100%; display: grid; gap: 4px; max-height: 180px; padding: 6px; overflow-y: auto; border: 1px solid #dce7f0; border-radius: 10px; background: rgb(255 255 255 / 96%); box-shadow: 0 8px 24px rgb(35 64 88 / 12%); }
.mention-options button { display: flex; align-items: center; gap: 8px; width: 100%; min-width: 0; padding: 6px 7px; border: 0; border-radius: 7px; background: transparent; color: #30465b; text-align: left; cursor: pointer; }
.mention-options button:hover { background: #f1f6fa; }
.mention-options img { width: 30px; height: 30px; flex: 0 0 30px; object-fit: cover; border-radius: 6px; background: #eef3f7; }
.mention-option-token { flex: 0 0 auto; color: #21628f; font-weight: 700; }
.mention-option-name { min-width: 0; overflow: hidden; color: #7b8999; text-overflow: ellipsis; white-space: nowrap; }
</style>
