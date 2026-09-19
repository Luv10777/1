<script setup>
import { computed, ref, nextTick, onMounted, onBeforeUnmount, watch } from 'vue'
import { Check, ChevronDown } from 'lucide-vue-next'

const props = defineProps({
  id: { type: String, required: true },
  modelValue: { type: String, default: '' },
  label: { type: String, required: true },
  options: { type: Array, default: () => [] },
  disabled: Boolean,
})
const emit = defineEmits(['update:modelValue'])
const trigger = ref(null)
const panel = ref(null)
const opened = ref(false)
const activeIndex = ref(0)
const selected = computed(() => props.options.find(item => item.value === props.modelValue))
let resizeObserver
let frame
let search = ''
let searchTimer

function position() {
  if (!opened.value || !trigger.value || !panel.value) return
  const rect = trigger.value.getBoundingClientRect()
  const viewportHeight = window.innerHeight
  const below = viewportHeight - rect.bottom - 16
  const above = rect.top - 16
  const desiredHeight = Math.min(300, panel.value.scrollHeight)
  const upward = below < desiredHeight && above > below
  const available = Math.max(60, upward ? above : below)
  const width = Math.min(rect.width, window.innerWidth - 24)
  Object.assign(panel.value.style, {
    width: `${width}px`,
    maxHeight: `${Math.min(300, available)}px`,
    left: `${Math.max(12, Math.min(rect.left, window.innerWidth - width - 12))}px`,
    top: upward ? 'auto' : `${rect.bottom + 8}px`,
    bottom: upward ? `${viewportHeight - rect.top + 8}px` : 'auto',
    transformOrigin: upward ? 'bottom center' : 'top center',
  })
  panel.value.dataset.side = upward ? 'top' : 'bottom'
}
function schedulePosition(event) {
  // ResizeObserver supplies entries, while resize events target Window.
  if (event?.target instanceof Node && panel.value?.contains(event.target)) return
  cancelAnimationFrame(frame)
  frame = requestAnimationFrame(position)
}
function revealActive() {
  const item = panel.value?.children[activeIndex.value]
  if (!item) return
  const top = item.offsetTop
  if (top < panel.value.scrollTop) panel.value.scrollTop = top
  else if (top + item.offsetHeight > panel.value.scrollTop + panel.value.clientHeight)
    panel.value.scrollTop = top + item.offsetHeight - panel.value.clientHeight
}
async function open() {
  if (props.disabled || !props.options.length) return
  opened.value = true
  activeIndex.value = Math.max(0, props.options.findIndex(item => item.value === props.modelValue))
  await nextTick()
  if (!opened.value || !panel.value) return
  panel.value.showPopover()
  position()
  revealActive()
}
function close() {
  opened.value = false
  panel.value?.hidePopover()
  search = ''
  clearTimeout(searchTimer)
}
function choose(index) {
  const option = props.options[index]
  if (!option) return
  emit('update:modelValue', option.value)
  close()
  trigger.value?.focus({ preventScroll: true })
}
function outside(event) {
  if (!trigger.value?.contains(event.target) && !panel.value?.contains(event.target)) close()
}
async function onKeydown(event) {
  if (event.key === 'Tab') { close(); return }
  if (event.key === 'Escape') { event.preventDefault(); close(); return }
  if (['ArrowDown', 'ArrowUp', 'Home', 'End', 'Enter', ' '].includes(event.key)) {
    event.preventDefault()
    if (!opened.value) { await open(); return }
    if (event.key === 'Enter' || event.key === ' ') { choose(activeIndex.value); return }
    const last = props.options.length - 1
    if (event.key === 'Home') activeIndex.value = 0
    else if (event.key === 'End') activeIndex.value = last
    else activeIndex.value = Math.max(0, Math.min(last, activeIndex.value + (event.key === 'ArrowDown' ? 1 : -1)))
    await nextTick()
    revealActive()
  } else if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
    if (!opened.value) await open()
    search += event.key.toLowerCase()
    clearTimeout(searchTimer)
    searchTimer = setTimeout(() => { search = '' }, 700)
    const index = props.options.findIndex(item => item.label.toLowerCase().startsWith(search))
    if (index >= 0) { activeIndex.value = index; await nextTick(); revealActive() }
  }
}
watch(() => props.disabled, value => { if (value) close() })
watch(() => props.options, () => { if (opened.value) close() })
onMounted(() => {
  document.addEventListener('pointerdown', outside)
  document.addEventListener('focusin', outside)
  document.addEventListener('scroll', schedulePosition, true)
  window.addEventListener('resize', schedulePosition)
  resizeObserver = new ResizeObserver(schedulePosition)
  resizeObserver.observe(trigger.value)
})
onBeforeUnmount(() => {
  clearTimeout(searchTimer)
  cancelAnimationFrame(frame)
  resizeObserver?.disconnect()
  document.removeEventListener('pointerdown', outside)
  document.removeEventListener('focusin', outside)
  document.removeEventListener('scroll', schedulePosition, true)
  window.removeEventListener('resize', schedulePosition)
})
</script>

<template>
  <div class="pub-select">
    <button :id="id" ref="trigger" type="button" class="pub-select-trigger" role="combobox" aria-haspopup="listbox" :aria-label="label" :aria-expanded="opened" :aria-controls="`${id}-listbox`" :aria-activedescendant="opened ? `${id}-option-${activeIndex}` : undefined" :disabled="disabled || !options.length" @click="opened ? close() : open()" @keydown="onKeydown">
      <span v-if="$slots.icon" class="pub-select-icon"><slot name="icon" /></span>
      <span class="pub-select-value">{{ selected?.label || '请选择' }}</span>
      <ChevronDown class="pub-select-chevron" :size="16" />
    </button>
    <div :id="`${id}-listbox`" ref="panel" popover="manual" class="pub-select-menu" role="listbox" :aria-label="label" @keydown="onKeydown">
      <div v-for="(option, index) in options" :id="`${id}-option-${index}`" :key="option.value" role="option" :aria-selected="modelValue === option.value" class="pub-select-option" :class="{ active: index === activeIndex }" @pointermove="activeIndex = index" @mousedown.prevent @click="choose(index)">
        <span v-if="$slots.icon" class="pub-select-option-icon"><slot name="icon" /></span>
        <span class="pub-select-option-copy"><b>{{ option.label }}</b><small v-if="option.description">{{ option.description }}</small></span>
        <Check v-if="modelValue === option.value" class="pub-select-check" :size="16" :stroke-width="2.5" />
      </div>
    </div>
  </div>
</template>
