<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { imageApi } from '../services/imageCreation'
import { get } from '../utils/request'
import { videoConversationTitle } from '../services/videoConversations'
import { NOVICE_RECENTS_CHANGED } from '../stores/uiMode'

// 新手版侧栏的「最近」：灵感绘图和视频创作各自的记录按时间合在一起，点开回到对话页。

const emit = defineEmits(['open'])
const route = useRoute()
const items = ref([])
const failed = ref(false)

async function load() {
  const [images, videos] = await Promise.allSettled([
    imageApi.history('POSTER', 0),
    get('/api/video/workflows', { page: 0, size: 20 }),
  ])
  failed.value = images.status === 'rejected' && videos.status === 'rejected'
  const merged = [
    ...(images.status === 'fulfilled' ? images.value.items || [] : []).map(entry => ({
      key: `image:${entry.id}`, kind: 'image', title: entry.title || entry.brief || '绘图创作', at: Date.parse(entry.createdAt) || 0,
    })),
    ...(videos.status === 'fulfilled' ? videos.value.items || [] : []).map(entry => ({
      key: `video:${entry.id}`, kind: 'video', title: videoConversationTitle(entry.prompt), at: Date.parse(entry.createdAt) || 0,
    })),
  ]
  items.value = merged.sort((a, b) => b.at - a.at).slice(0, 12)
}

onMounted(() => { load(); window.addEventListener(NOVICE_RECENTS_CHANGED, load) })
onBeforeUnmount(() => window.removeEventListener(NOVICE_RECENTS_CHANGED, load))
</script>

<template>
  <section class="novice-recents" aria-label="最近的创作">
    <h2>最近</h2>
    <RouterLink
      v-for="item in items"
      :key="item.key"
      :to="{ name: 'studio', query: { open: item.key } }"
      class="novice-recent"
      :class="{ active: route.query.open === item.key }"
      :title="item.title"
      @click="emit('open')"
    >
      <span class="novice-recent-kind">{{ item.kind === 'image' ? '图' : '视频' }}</span>
      <span class="novice-recent-title">{{ item.title }}</span>
    </RouterLink>
    <p v-if="failed" class="novice-recent-empty">记录暂时读不到</p>
    <p v-else-if="!items.length" class="novice-recent-empty">做过的图片和视频会出现在这里</p>
  </section>
</template>

<style scoped>
.novice-recents { display: flex; flex-direction: column; gap: 2px; min-height: 0; overflow-y: auto; }
.novice-recents h2 { margin: 4px 10px 6px; color: var(--ink-muted); font-size: 12px; font-weight: 500; letter-spacing: .12em; }
.novice-recent { display: flex; align-items: center; gap: 8px; min-height: 38px; padding: 0 10px; border-radius: 8px; color: var(--ink); font-size: 13px; text-decoration: none; }
.novice-recent:hover, .novice-recent.active { background: var(--color-bg-surface); box-shadow: 0 0 0 1px var(--line); }
.novice-recent-kind { flex: none; padding: 0 5px; border-radius: 4px; background: var(--color-bg-subtle); color: var(--ink-muted); font-size: 11px; line-height: 18px; }
.novice-recent-title { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.novice-recent-empty { margin: 4px 10px; color: var(--ink-muted); font-size: 12px; line-height: 1.6; }
</style>
