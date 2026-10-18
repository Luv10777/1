<script setup>
import { ref, watch } from 'vue'

const props = defineProps({ src: { type: String, default: '' }, name: { type: String, default: '用户' } })
const emit = defineEmits(['load-error'])
const failed = ref(false)
watch(() => props.src, () => { failed.value = false })
function fail() { failed.value = true; emit('load-error') }
</script>

<template>
  <span class="user-avatar" aria-hidden="true">
    <img v-if="src && !failed" :src="src" alt="" referrerpolicy="no-referrer" @error="fail" />
    <template v-else>{{ name.slice(0, 1) || '用' }}</template>
  </span>
</template>

<style scoped>
.user-avatar { overflow: hidden; }
.user-avatar img { display: block; width: 100%; height: 100%; object-fit: cover; }
</style>
