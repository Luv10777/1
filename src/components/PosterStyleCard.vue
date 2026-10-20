<script setup>
import { Check } from 'lucide-vue-next'

defineProps({ look: { type: String, required: true }, name: { type: String, required: true }, fit: { type: String, default: '' },
  title: { type: String, required: true }, note: { type: String, default: '' }, selected: Boolean, disabled: Boolean })
defineEmits(['select'])
</script>

<template>
  <button type="button" class="poster-style-card" :class="{ 'is-selected': selected }" :disabled="disabled" :aria-pressed="selected" @click="$emit('select')">
    <span class="poster-style-swatch" :class="`is-${look}`" :style="{ '--len': [...title].length }" aria-hidden="true"><span class="poster-style-mark" /><b>{{ title }}</b><small v-if="note">{{ note }}</small></span>
    <span class="poster-style-meta"><strong>{{ name }}</strong><span>{{ fit }}</span></span>
    <span v-if="selected" class="poster-style-check" aria-hidden="true"><Check :size="14" :stroke-width="2.5" /></span>
  </button>
</template>
