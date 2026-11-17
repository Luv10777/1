<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { Film, ImageIcon, Play } from 'lucide-vue-next'
import { imageApi } from '../services/imageCreation'
import { get } from '../utils/request'
import { videoConversationTitle } from '../services/videoConversations'
import { NOVICE_RECENTS_CHANGED } from '../stores/uiMode'

// 新手版侧栏的「最近」：灵感绘图和视频创作各自的记录按时间合在一起，按天分组，点开回到对话页。
// 同一个对话里图片、视频都能做，所以这里不标类型，只用缩略图区分。
const emit = defineEmits(['open'])
const route = useRoute()
const items = ref([])
const failed = ref(false)
const loaded = ref(false)

async function load() {
  const [images, videos] = await Promise.allSettled([
    imageApi.history('POSTER', 0),
    get('/api/video/workflows', { page: 0, size: 20 }),
  ])
  failed.value = images.status === 'rejected' && videos.status === 'rejected'
  loaded.value = true
  const merged = [
    ...(images.status === 'fulfilled' ? images.value.items || [] : []).map(entry => ({
      key: `image:${entry.id}`, kind: 'image', title: entry.title || entry.brief || '绘图创作', at: Date.parse(entry.createdAt) || 0, thumb: entry.previewUrl || '',
    })),
    ...(videos.status === 'fulfilled' ? videos.value.items || [] : []).map(entry => ({
      key: `video:${entry.id}`, kind: 'video', title: videoConversationTitle(entry.prompt), at: Date.parse(entry.createdAt) || 0, thumb: entry.outputUrl || '',
    })),
  ]
  items.value = merged.sort((a, b) => b.at - a.at).slice(0, 30)
}

const DAY = 86400000
const groups = computed(() => {
  const today = new Date(); today.setHours(0, 0, 0, 0)
  const start = today.getTime()
  const buckets = [['今天', start], ['昨天', start - DAY], ['近 7 天', start - 6 * DAY], ['更早', -Infinity]].map(([label, from]) => ({ label, from, items: [] }))
  for (const item of items.value) buckets.find(bucket => item.at >= bucket.from).items.push(item)
  return buckets.filter(bucket => bucket.items.length)
})
const timeLabel = at => {
  const date = new Date(at)
  const today = new Date(); today.setHours(0, 0, 0, 0)
  return at >= today.getTime()
    ? date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
    : `${date.getMonth() + 1}/${date.getDate()}`
}

onMounted(() => { load(); window.addEventListener(NOVICE_RECENTS_CHANGED, load) })
onBeforeUnmount(() => window.removeEventListener(NOVICE_RECENTS_CHANGED, load))
</script>

<template>
  <section class="novice-recents" aria-label="最近的创作">
    <div v-for="group in groups" :key="group.label" class="novice-recent-group">
      <h2>{{ group.label }}</h2>
      <RouterLink
        v-for="item in group.items"
        :key="item.key"
        :to="{ name: 'studio', query: { open: item.key } }"
        class="novice-recent"
        :class="{ active: route.query.open === item.key }"
        :title="item.title"
        @click="emit('open')"
      >
        <span class="novice-recent-thumb" aria-hidden="true">
          <img v-if="item.kind === 'image' && item.thumb" :src="item.thumb" alt="" loading="lazy" />
          <video v-else-if="item.kind === 'video' && item.thumb" :src="`${item.thumb}#t=0.1`" muted playsinline preload="metadata" />
          <ImageIcon v-else-if="item.kind === 'image'" :size="15" :stroke-width="1.6" />
          <Film v-else :size="15" :stroke-width="1.6" />
          <i v-if="item.kind === 'video' && item.thumb"><Play :size="9" fill="currentColor" :stroke-width="0" /></i>
        </span>
        <span class="novice-recent-title">{{ item.title }}</span>
        <time class="novice-recent-time" :datetime="new Date(item.at).toISOString()">{{ timeLabel(item.at) }}</time>
      </RouterLink>
    </div>
    <p v-if="failed" class="novice-recent-empty">记录暂时读不到</p>
    <p v-else-if="loaded && !items.length" class="novice-recent-empty">做过的图片和视频会出现在这里</p>
  </section>
</template>

<style scoped>
.novice-recents { display: flex; flex: 1 1 auto; flex-direction: column; gap: 14px; min-height: 0; margin: 0 -4px; padding: 0 4px; overflow-y: auto; scrollbar-width: thin; }
.novice-recent-group { display: flex; flex-direction: column; gap: 2px; }
.novice-recent-group h2 { position: sticky; top: 0; z-index: 1; margin: 0; padding: 2px 10px 6px; background: var(--color-bg-canvas); color: var(--ink-muted); font-size: 11px; font-weight: 500; letter-spacing: .14em; }
.novice-recent { display: flex; align-items: center; gap: 10px; min-height: 44px; padding: 4px 8px; border-radius: 10px; color: var(--ink); font-size: 13px; text-decoration: none; transition: background .15s; }
.novice-recent:hover { background: color-mix(in srgb, var(--color-bg-surface) 70%, transparent); }
.novice-recent.active { background: var(--color-bg-surface); box-shadow: 0 0 0 1px var(--line), 0 4px 12px -8px rgb(15 23 42 / 30%); }
.novice-recent-thumb { position: relative; display: grid; flex: 0 0 34px; place-items: center; width: 34px; height: 34px; overflow: hidden; border-radius: 8px; background: var(--color-bg-subtle); box-shadow: inset 0 0 0 1px var(--line); color: var(--ink-muted); }
.novice-recent-thumb img, .novice-recent-thumb video { width: 100%; height: 100%; object-fit: cover; }
.novice-recent-thumb i { position: absolute; right: 2px; bottom: 2px; display: grid; place-items: center; width: 13px; height: 13px; border-radius: 50%; background: rgb(15 23 42 / 65%); color: #fff; }
.novice-recent-title { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.novice-recent.active .novice-recent-title { font-weight: 600; }
.novice-recent-time { flex: none; color: var(--ink-muted); font-size: 11px; font-variant-numeric: tabular-nums; }
.novice-recent-empty { margin: 4px 10px; color: var(--ink-muted); font-size: 12px; line-height: 1.6; }
</style>
