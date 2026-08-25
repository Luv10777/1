<script setup>
defineProps({
  title: { type: String, default: '出错了' },
  message: { type: String, default: '请求失败，请稍后重试' },
  code: { type: String, default: '' },
  retryable: { type: Boolean, default: true }
})

const emit = defineEmits(['retry'])
</script>

<template>
  <div class="error-state">
    <div class="error-icon">⚠</div>
    <h3>{{ title }}</h3>
    <p>{{ message }}</p>
    <p v-if="code" class="error-code">错误代码: {{ code }}</p>
    <button v-if="retryable" class="primary-button" @click="emit('retry')">
      重试
    </button>
  </div>
</template>

<style scoped>
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 64px 24px;
  text-align: center;
}

.error-icon {
  font-size: 48px;
  color: rgb(251, 191, 36);
  margin-bottom: 16px;
}

.error-state h3 {
  font-size: 16px;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.9);
  margin: 0 0 8px 0;
}

.error-state p {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.5);
  margin: 0 0 8px 0;
}

.error-code {
  font-family: 'SF Mono', Consolas, monospace;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.3);
  margin-bottom: 24px !important;
}
</style>
