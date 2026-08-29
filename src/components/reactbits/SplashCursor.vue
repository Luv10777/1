<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({ opacity: { type: Number, default: 0.16 } })
const root = ref(null)
let frame
let targetX = 50
let targetY = 50
let x = 50
let y = 50

const move = (event) => {
  if (!root.value) return
  const rect = root.value.getBoundingClientRect()
  targetX = ((event.clientX - rect.left) / rect.width) * 100
  targetY = ((event.clientY - rect.top) / rect.height) * 100
}
const animate = () => {
  x += (targetX - x) * 0.08
  y += (targetY - y) * 0.08
  if (root.value) root.value.style.setProperty('--splash-x', `${x}%`), root.value.style.setProperty('--splash-y', `${y}%`)
  frame = requestAnimationFrame(animate)
}
onMounted(() => { window.addEventListener('pointermove', move); frame = requestAnimationFrame(animate) })
onBeforeUnmount(() => { window.removeEventListener('pointermove', move); cancelAnimationFrame(frame) })
</script>

<template>
  <div ref="root" class="rb-splash-cursor" :style="{ '--splash-opacity': props.opacity }" aria-hidden="true" />
</template>
