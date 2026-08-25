<script setup>
defineProps({
  icon: { type: String, default: '○' },
  title: { type: String, default: '暂无数据' },
  description: { type: String, default: '' },
  actionText: { type: String, default: '' }
})

const emit = defineEmits(['action'])
</script>

<template>
  <div class="empty-component">
    <div class="empty-icon">{{ icon }}</div>
    <h3>{{ title }}</h3>
    <p v-if="description">{{ description }}</p>
    <button v-if="actionText" class="primary-button" @click="emit('action')">
      {{ actionText }}
    </button>
  </div>
</template>

<style scoped>
.empty-component {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 64px 24px;
  text-align: center;
}

.empty-icon {
  font-size: 48px;
  opacity: 0.3;
  margin-bottom: 16px;
}

.empty-component h3 {
  font-size: 16px;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.9);
  margin: 0 0 8px 0;
}

.empty-component p {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.5);
  margin: 0 0 24px 0;
  max-width: 400px;
}
</style>
