<script setup>
import { computed } from 'vue'

const props = defineProps({
  text: { type: String, required: true },
  animateBy: { type: String, default: 'words' },
  delay: { type: Number, default: 50 },
  direction: { type: String, default: 'bottom' },
})
const pieces = computed(() => props.animateBy === 'words' ? props.text.split(' ') : [...props.text])
</script>

<template>
  <span class="rb-blur-text" :class="[`rb-blur-${direction}`, { 'rb-blur-characters': animateBy === 'characters' } ]" aria-label="text">
    <span v-for="(piece, index) in pieces" :key="`${piece}-${index}`" class="rb-blur-piece" :style="{ '--blur-delay': `${index * delay}ms` }">{{ piece }}<span v-if="animateBy === 'words' && index < pieces.length - 1">&nbsp;</span></span>
  </span>
</template>
