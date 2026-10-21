<script setup>
import { Check } from 'lucide-vue-next'

defineProps({ image: { type: String, required: true }, name: { type: String, required: true }, fit: { type: String, default: '' }, selected: Boolean, disabled: Boolean })
defineEmits(['select'])
</script>

<template>
  <button type="button" class="poster-style-card" :class="{ 'is-selected': selected }" :title="fit ? `适合${fit}` : undefined" :disabled="disabled" :aria-pressed="selected" @click="$emit('select')">
    <img class="poster-style-image" :src="image" alt="" loading="lazy" />
    <span class="poster-style-meta"><strong>{{ name }}</strong></span>
    <span v-if="selected" class="poster-style-check" aria-hidden="true"><Check :size="14" :stroke-width="2.5" /></span>
  </button>
</template>
