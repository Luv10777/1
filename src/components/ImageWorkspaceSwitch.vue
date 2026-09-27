<script setup>
import { nextTick, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const switching = ref(false)
const workspaces = [
  { path: '/image/create/poster', label: '营销海报', icon: 'campaign' },
  { path: '/image/create/product-set', label: '产品套图', icon: 'grid_view' },
]

async function navigate(event, path) {
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  event.preventDefault()
  if (route.path === path || switching.value) return

  switching.value = true
  const update = async () => {
    await router.push(path)
    await nextTick()
  }
  try {
    if (document.startViewTransition && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      document.documentElement.classList.add('image-workspace-transitioning')
      try {
        await document.startViewTransition(update).finished
      } finally {
        document.documentElement.classList.remove('image-workspace-transitioning')
      }
    } else {
      await update()
    }
  } finally {
    switching.value = false
  }
}
</script>

<template>
  <nav class="image-workspace-switch video-mode-switch studio-mode-switch" aria-label="切换图片工作区">
    <a v-for="workspace in workspaces" :key="workspace.path" :href="workspace.path" :class="{ active: route.path === workspace.path }" :aria-current="route.path === workspace.path ? 'page' : undefined" @click="navigate($event, workspace.path)">
      <span class="material-symbols-outlined" aria-hidden="true">{{ workspace.icon }}</span>{{ workspace.label }}
    </a>
  </nav>
</template>
