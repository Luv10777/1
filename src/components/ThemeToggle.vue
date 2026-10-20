<script setup>
import { Moon, Sun } from 'lucide-vue-next'
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
    <component :is="theme.isLight ? Moon : Sun" :size="17" :stroke-width="1.8" aria-hidden="true" />
  </button>
</template>
