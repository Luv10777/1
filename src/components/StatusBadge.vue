<script setup>
defineProps({
  status: {
    type: String,
    required: true,
    validator: (v) => ['success', 'warning', 'error', 'info', 'pending'].includes(v)
  },
  text: { type: String, required: true }
})

const statusConfig = {
  success: { color: 'rgb(34, 197, 94)', label: '成功' },
  warning: { color: 'rgb(251, 191, 36)', label: '警告' },
  error: { color: 'rgb(239, 68, 68)', label: '错误' },
  info: { color: 'rgb(59, 130, 246)', label: '信息' },
  pending: { color: 'rgb(156, 163, 175)', label: '待处理' }
}
</script>

<template>
  <span class="status-badge" :style="{ '--badge-color': statusConfig[status].color }">
    <span class="badge-dot" />
    {{ text }}
  </span>
</template>

<style scoped>
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid var(--badge-color, rgba(255, 255, 255, 0.1));
  border-radius: 12px;
  font-size: 12px;
  color: var(--badge-color);
  font-weight: 500;
}

.badge-dot {
  width: 6px;
  height: 6px;
  background: var(--badge-color);
  border-radius: 50%;
  animation: pulse 2s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}
</style>
