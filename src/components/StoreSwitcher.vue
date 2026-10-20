<script setup>
import { computed } from 'vue'
import { Listbox, ListboxButton, ListboxOption, ListboxOptions } from '@headlessui/vue'
import { Check, ChevronDown, Store } from 'lucide-vue-next'

const props = defineProps({
  stores: { type: Array, default: () => [] },
  modelValue: { type: [Number, String], default: null },
  loading: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue'])
const current = computed(() => props.stores.find(store => store.id === props.modelValue))
const label = computed(() => current.value?.name || (props.loading ? '加载门店…' : props.stores.length ? '选择门店' : '尚无门店'))
</script>

<template>
  <Listbox :model-value="modelValue" :disabled="loading || !stores.length" @update:model-value="value => emit('update:modelValue', value)">
    <div class="store-switcher">
      <ListboxButton class="store-switcher-button" :title="label">
        <Store :size="15" aria-hidden="true" />
        <span class="store-switcher-name"><span class="sr-only">当前门店：</span>{{ label }}</span>
        <ChevronDown :size="15" class="store-switcher-chevron" aria-hidden="true" />
      </ListboxButton>
      <ListboxOptions class="store-switcher-menu">
        <ListboxOption v-for="store in stores" :key="store.id" v-slot="{ active, selected }" :value="store.id" as="template">
          <li class="store-switcher-option" :class="{ active, selected }">
            <span class="store-switcher-initial" aria-hidden="true">{{ store.name.slice(0, 1) }}</span>
            <span class="store-switcher-label">{{ store.name }}</span>
            <Check v-if="selected" :size="15" class="store-switcher-check" aria-hidden="true" />
          </li>
        </ListboxOption>
      </ListboxOptions>
    </div>
  </Listbox>
</template>

<style scoped>
.store-switcher { position: relative; min-width: 0; }
.store-switcher-button { display: flex; align-items: center; gap: 8px; max-width: 200px; height: 34px; padding: 0 10px 0 12px; border: 1px solid var(--color-border-subtle); border-radius: 17px; background: var(--color-bg-surface); color: var(--color-primary); font: 500 13px/1 var(--font-sans); transition: border-color .18s, background .18s; }
.store-switcher-button:hover:not(:disabled) { border-color: var(--color-primary); }
.store-switcher-button:disabled { cursor: default; opacity: .7; }
.store-switcher-button > svg:first-child { flex: none; color: var(--color-accent-text); }
.store-switcher-name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.store-switcher-chevron { flex: none; color: var(--color-text-muted); transition: transform .18s; }
.store-switcher-button[aria-expanded='true'] { border-color: var(--color-primary); }
.store-switcher-button[aria-expanded='true'] .store-switcher-chevron { transform: rotate(180deg); }
.store-switcher-menu { position: absolute; top: calc(100% + 6px); right: 0; z-index: 70; min-width: 220px; max-width: 300px; max-height: 320px; margin: 0; padding: 6px; overflow-y: auto; list-style: none; border: 1px solid var(--color-border-subtle); border-radius: 8px; background: var(--color-bg-surface); box-shadow: var(--shadow-paper-elevated); outline: none; }
.store-switcher-option { display: flex; align-items: center; gap: 10px; min-height: 40px; padding: 6px 10px; border-radius: 4px; color: var(--color-primary); font-size: 13px; cursor: pointer; }
.store-switcher-option.active { background: var(--color-bg-subtle); }
.store-switcher-option.selected { color: var(--color-accent-text); font-weight: 500; }
.store-switcher-initial { display: grid; place-items: center; flex: 0 0 26px; width: 26px; height: 26px; border-radius: 4px; background: var(--color-bg-subtle); color: var(--color-primary); font: 500 13px var(--font-serif); }
.store-switcher-option.selected .store-switcher-initial { background: color-mix(in srgb, var(--color-accent) 12%, transparent); color: var(--color-accent-text); }
.store-switcher-label { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.store-switcher-check { flex: none; }
.sr-only { position: absolute; width: 1px; height: 1px; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
@media (prefers-reduced-motion: reduce) { .store-switcher-chevron, .store-switcher-button { transition: none; } }
</style>
