<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppShell from './layouts/AppShell.vue'
import DesktopShell from './layouts/DesktopShell.vue'
import { inDesktop } from './utils/desktop'

const route = useRoute()
const isPublic = computed(() => Boolean(route.meta.public))
</script>

<template>
  <RouterView v-if="isPublic" />
  <!-- 桌面端只做直播，不带平台的导航。 -->
  <DesktopShell v-else-if="inDesktop">
    <RouterView />
  </DesktopShell>
  <AppShell v-else>
    <RouterView />
  </AppShell>
</template>
