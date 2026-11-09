<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { CornerDownLeft, Search } from 'lucide-vue-next'

/** 按 Ctrl K 打开的跳转面板：输入页面名或说明，回车跳过去。 */
const props = defineProps({ open: Boolean, groups: { type: Array, required: true } })
const emit = defineEmits(['close'])
const router = useRouter()
const query = ref('')
const active = ref(0)
const input = ref(null)

const entries = computed(() => props.groups.flatMap(group => group.items.map(item => ({ ...item, group: group.label || '常用' }))))
const matches = computed(() => {
  const text = query.value.trim().toLowerCase()
  return text ? entries.value.filter(item => `${item.label}${item.description}${item.group}`.toLowerCase().includes(text)) : entries.value
})

watch(() => props.open, async value => {
  if (!value) return
  query.value = ''
  active.value = 0
  await nextTick()
  input.value?.focus()
})
watch(query, () => { active.value = 0 })

function go(item) {
  if (!item) return
  emit('close')
  router.push({ name: item.name })
}
function onKey(event) {
  if (event.key === 'ArrowDown') { event.preventDefault(); active.value = Math.min(active.value + 1, matches.value.length - 1) }
  else if (event.key === 'ArrowUp') { event.preventDefault(); active.value = Math.max(active.value - 1, 0) }
  else if (event.key === 'Enter') { event.preventDefault(); go(matches.value[active.value]) }
  else if (event.key === 'Escape') { event.preventDefault(); emit('close') }
}
</script>

<template>
  <div v-if="open" class="admin-palette-backdrop" @pointerdown.self="emit('close')">
    <div class="admin-palette" role="dialog" aria-modal="true" aria-label="跳转到页面">
      <label class="admin-palette-input"><Search :size="17" /><span class="sr-only">搜索页面</span><input ref="input" v-model="query" placeholder="输入页面名称，例如「兑换码」「实名」" autocomplete="off" @keydown="onKey" /></label>
      <ul role="listbox" aria-label="页面">
        <li v-for="(item, index) in matches" :key="item.name" role="option" :aria-selected="index === active">
          <button type="button" :class="{ active: index === active }" @pointermove="active = index" @click="go(item)"><component :is="item.icon" :size="16" /><span>{{ item.label }}<small>{{ item.description }}</small></span><em>{{ item.group }}</em><CornerDownLeft v-if="index === active" :size="14" /></button>
        </li>
        <li v-if="!matches.length" class="admin-palette-empty">没有找到「{{ query }}」相关的页面。</li>
      </ul>
    </div>
  </div>
</template>
