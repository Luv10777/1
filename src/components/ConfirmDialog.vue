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
          <button class="dialog-button" @click="emit('cancel')">
            {{ cancelText }}
          </button>
          <button
            class="dialog-button is-primary"
            :class="{ 'is-danger': danger }"
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
.dialog-overlay { position: fixed; inset: 0; z-index: 1000; display: flex; align-items: center; justify-content: center; background: color-mix(in srgb, #14181c 45%, transparent); }
.dialog-content { width: calc(100% - 48px); max-width: 360px; padding: 22px 24px 20px; border: 1px solid var(--color-border-subtle); border-radius: 10px; background: var(--color-bg-surface); color: var(--color-primary); box-shadow: var(--shadow-paper-elevated); }
.dialog-content h3 { margin: 0 0 8px; font-size: 16px; font-weight: 600; }
.dialog-content p { margin: 0 0 20px; color: var(--color-text-muted); font-size: 13px; line-height: 1.6; }
.dialog-actions { display: flex; align-items: center; justify-content: flex-end; gap: 10px; }
.dialog-button { box-sizing: border-box; display: inline-flex; align-items: center; justify-content: center; min-width: 72px; height: 36px; min-height: 0; margin: 0; padding: 0 16px; border: 1px solid var(--color-border-subtle); border-radius: 4px; background: transparent; color: var(--color-primary); font: 600 13px/1 var(--font-sans); cursor: pointer; }
.dialog-button:hover { background: var(--color-bg-subtle); }
.dialog-button.is-primary { border-color: var(--color-accent); background: var(--color-accent); color: var(--color-on-accent); }
.dialog-button.is-primary:hover { background: var(--color-accent-hover); }
.dialog-button.is-danger { border-color: var(--color-error); background: var(--color-error); }
.dialog-button.is-danger:hover { background: color-mix(in srgb, var(--color-error) 86%, black); }
</style>
