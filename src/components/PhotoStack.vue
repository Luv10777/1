<script setup>
import { computed } from 'vue'

const props = defineProps({
  images: {
    type: Array,
    default: () => [],
  },
  alt: {
    type: String,
    default: '营销海报案例',
  },
  label: {
    type: String,
    default: '案例组图',
  },
})

const layers = computed(() => {
  const source = props.images.filter(Boolean)
  if (!source.length) return []
  return [source[0], source[1] || source[0], source[2] || source[0]].slice(0, 3)
})
</script>

<template>
  <div class="photo-stack relative group" :aria-label="label">
    <img
      v-for="(image, index) in layers"
      :key="`${image}-${index}`"
      :src="image"
      :alt="index === 2 ? alt : ''"
      :aria-hidden="index === 2 ? undefined : 'true'"
      class="photo-stack-card absolute inset-0 rounded-xl border shadow-lg transition-all duration-500 ease-out"
      :class="[`photo-stack-card-${index + 1}`]"
    />
    <span class="photo-stack-shine" aria-hidden="true" />
  </div>
</template>
