<script setup>
defineProps({
  colorStops: { type: Array, default: () => ['#000000', '#312e81', '#1e1b4b'] },
  blend: { type: Number, default: 0.8 },
  speed: { type: Number, default: 0.4 },
})
</script>

<template>
  <div class="rb-aurora" :style="{ '--aurora-blend': blend, '--aurora-speed': `${Math.max(speed, .1) * 18}s`, '--aurora-a': colorStops[0], '--aurora-b': colorStops[1], '--aurora-c': colorStops[2] }" aria-hidden="true" />
</template>
