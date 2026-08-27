<script setup>
import { nextTick, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

const STORAGE_KEY = 'wuyao-route-tabs-v1'
const MAX_TABS = 20
const FIXED_TAB = {
  name: 'dashboard',
  title: '大屏总览',
  path: '/dashboard',
  closable: false,
}

const route = useRoute()
const router = useRouter()
const tabTrack = ref(null)
const visitHistory = ref([])

const isValidTab = (tab) => {
  if (!tab || typeof tab.name !== 'string' || typeof tab.path !== 'string' || typeof tab.title !== 'string') return false
  const resolved = router.resolve(tab.path)
  return Boolean(resolved.name && resolved.name !== 'not-found' && !resolved.meta.public)
}

const restoreTabs = () => {
  let savedTabs = []
  try {
    const parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]')
    if (Array.isArray(parsed)) savedTabs = parsed.filter(isValidTab)
  } catch {
    // Keep the fixed dashboard tab when storage is unavailable or malformed.
  }

  const uniqueTabs = new Map([[FIXED_TAB.name, FIXED_TAB]])
  savedTabs.forEach((tab) => {
    if (tab.name !== FIXED_TAB.name) uniqueTabs.set(tab.name, { ...tab, closable: true })
  })
  return [...uniqueTabs.values()].slice(0, MAX_TABS)
}

const tabs = ref(restoreTabs())
const activeName = () => String(route.name || '')

const persistTabs = () => {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(tabs.value))
  } catch {
    // Tabs remain usable for the current session when storage is unavailable.
  }
}

const rememberVisit = (name) => {
  visitHistory.value = [...visitHistory.value.filter(item => item !== name), name]
}

const revealActiveTab = async () => {
  await nextTick()
  tabTrack.value?.querySelector('[aria-current="page"]')?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
}

watch(() => route.fullPath, () => {
  if (!route.name || route.meta.public) return

  const name = String(route.name)
  const nextTab = {
    name,
    title: String(route.meta.title || '未命名页面'),
    path: route.fullPath,
    closable: name !== FIXED_TAB.name,
  }
  const existingIndex = tabs.value.findIndex(tab => tab.name === name)

  if (existingIndex >= 0) tabs.value.splice(existingIndex, 1, nextTab)
  else {
    tabs.value.push(nextTab)
    if (tabs.value.length > MAX_TABS) {
      const removableIndex = tabs.value.findIndex(tab => tab.closable && tab.name !== name)
      if (removableIndex >= 0) tabs.value.splice(removableIndex, 1)
    }
  }

  rememberVisit(name)
  persistTabs()
  revealActiveTab()
}, { immediate: true })

const closeTab = (tab) => {
  if (!tab.closable) return

  const wasActive = tab.name === activeName()
  tabs.value = tabs.value.filter(item => item.name !== tab.name)
  visitHistory.value = visitHistory.value.filter(name => name !== tab.name)
  persistTabs()

  if (!wasActive) return
  const previousName = [...visitHistory.value].reverse().find(name => tabs.value.some(item => item.name === name))
  const target = tabs.value.find(item => item.name === previousName) || tabs.value.at(-1) || FIXED_TAB
  router.push(target.path)
}

const scrollTabs = (event) => {
  const track = tabTrack.value
  if (!track || track.scrollWidth <= track.clientWidth) return
  event.preventDefault()
  track.scrollLeft += event.deltaY || event.deltaX
}
</script>

<template>
  <nav class="route-tabs" aria-label="已打开页面">
    <div class="route-tabs-inner">
      <div ref="tabTrack" class="route-tabs-track" @wheel="scrollTabs">
        <div
          v-for="tab in tabs"
          :key="tab.name"
          class="route-tab"
          :class="{ active: tab.name === activeName() }"
        >
          <RouterLink
            :to="tab.path"
            class="route-tab-link"
            :aria-current="tab.name === activeName() ? 'page' : undefined"
          >
            {{ tab.title }}
          </RouterLink>
          <button
            v-if="tab.closable"
            class="route-tab-close"
            type="button"
            :aria-label="`关闭${tab.title}`"
            @click="closeTab(tab)"
          >
            ×
          </button>
        </div>
      </div>
    </div>
  </nav>
</template>
