<script setup>
import { theme } from '../stores/theme'
</script>

<template>
  <button
    class="theme-toggle"
    type="button"
    :aria-label="theme.isLight ? '切换到深色模式' : '切换到浅色模式'"
    :title="theme.isLight ? '切换到深色模式' : '切换到浅色模式'"
    @click="theme.toggle"
  >
    <svg v-if="theme.isLight" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M20 15.1A8.5 8.5 0 0 1 8.9 4a7 7 0 1 0 11.1 11.1Z" />
    </svg>
    <svg v-else viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="12" r="3.5" />
      <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </svg>
    <span class="theme-toggle-label">{{ theme.isLight ? '深色' : '浅色' }}</span>
  </button>
</template>
