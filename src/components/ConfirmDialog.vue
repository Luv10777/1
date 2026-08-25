<script setup>
defineProps({
  open: { type: Boolean, required: true },
  title: { type: String, default: '确认操作' },
  message: { type: String, default: '确定要执行此操作吗？' },
  confirmText: { type: String, default: '确认' },
  cancelText: { type: String, default: '取消' },
  danger: { type: Boolean, default: false }
})

const emit = defineEmits(['confirm', 'cancel'])
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="dialog-overlay" @click="emit('cancel')">
      <div class="dialog-content" @click.stop>
        <h3>{{ title }}</h3>
        <p>{{ message }}</p>
        <div class="dialog-actions">
          <button class="secondary-button" @click="emit('cancel')">
            {{ cancelText }}
          </button>
          <button
            class="primary-button"
            :class="{ 'danger-button': danger }"
            @click="emit('confirm')"
          >
            {{ confirmText }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.8);
  backdrop-filter: blur(8px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.dialog-content {
  background: rgba(20, 20, 30, 0.95);
  border: 1px solid rgba(139, 92, 246, 0.2);
  border-radius: 12px;
  padding: 24px;
  max-width: 420px;
  width: calc(100% - 48px);
  box-shadow: 0 24px 48px rgba(0, 0, 0, 0.5);
}

.dialog-content h3 {
  font-size: 16px;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.95);
  margin: 0 0 12px 0;
}

.dialog-content p {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.6);
  margin: 0 0 24px 0;
  line-height: 1.5;
}

.dialog-actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
}

.secondary-button {
  padding: 8px 16px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 6px;
  color: rgba(255, 255, 255, 0.7);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}

.secondary-button:hover {
  background: rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.9);
}

.danger-button {
  background: rgb(239, 68, 68) !important;
  border-color: rgb(239, 68, 68) !important;
}

.danger-button:hover {
  background: rgb(220, 38, 38) !important;
}
</style>
