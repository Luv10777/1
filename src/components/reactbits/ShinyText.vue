<script setup>
defineProps({
  text: { type: String, required: true },
  speed: { type: Number, default: 3 },
  className: { type: String, default: '' },
})
</script>

<template>
  <span class="rb-shiny-text" :class="className" :style="{ '--shiny-speed': `${Math.max(speed, .5)}s` }">{{ text }}</span>
</template>
