<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({ src: { type: [String, Object], default: '' }, name: { type: String, default: '' } })
const failed = ref(false)
const imageUrl = computed(() => typeof props.src === 'string' ? props.src : props.src?.url || '')
const initial = computed(() => Array.from(props.name.trim())[0] || '物')
watch(imageUrl, () => { failed.value = false })
</script>

<template>
  <span class="product-image" :class="{ 'product-image-placeholder': !imageUrl || failed }">
    <img v-if="imageUrl && !failed" :src="imageUrl" :alt="`${name || '商品'}主图`" @error="failed = true">
    <span v-else class="product-initial" aria-hidden="true">{{ initial }}</span>
  </span>
</template>
