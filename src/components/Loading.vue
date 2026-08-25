<script setup>
defineProps({
  size: { type: String, default: 'medium' },
  text: { type: String, default: '加载中...' }
})
</script>

<template>
  <div class="loading-component" :class="`loading-${size}`">
    <div class="spinner" />
    <p v-if="text">{{ text }}</p>
  </div>
</template>

<style scoped>
.loading-component {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 24px;
}

.loading-component p {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.5);
  margin: 0;
}

.spinner {
  width: 32px;
  height: 32px;
  border: 3px solid rgba(139, 92, 246, 0.2);
  border-top-color: rgb(139, 92, 246);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

.loading-small .spinner {
  width: 20px;
  height: 20px;
  border-width: 2px;
}

.loading-large .spinner {
  width: 48px;
  height: 48px;
  border-width: 4px;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
