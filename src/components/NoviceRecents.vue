<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { ChevronDown, Film, ImageIcon, Play } from 'lucide-vue-next'
import { imageApi } from '../services/imageCreation'
import { get } from '../utils/request'
import { videoConversationTitle } from '../services/videoConversations'
import { NOVICE_RECENTS_CHANGED } from '../stores/uiMode'
import { stableUrl } from '../utils/stableUrl'

// 新手版侧栏的「最近」：灵感绘图和视频创作各自的记录按时间合在一起。
// 默认只放最近 3 条（缩略图都是原图，少放几张也省流量）；点「查看全部」就地展开，按天分组、在侧栏里滚动，可以往前翻。
const SIDEBAR_COUNT = 3
const PAGE_SIZE = 20
const emit = defineEmits(['open'])
const route = useRoute()
const images = ref([])
const videos = ref([])
const pages = ref({ image: 0, video: 0 })
const more = ref({ image: false, video: false })
const failed = ref(false)
const loaded = ref(false)
const loadingMore = ref(false)
const expanded = ref(false)

// 图片优先用小图（长边 640）；更早的图没有小图，加载失败时换回原图。
const imageEntry = entry => ({ key: `image:${entry.id}`, kind: 'image', title: entry.title || entry.brief || '绘图创作', at: Date.parse(entry.createdAt) || 0, thumb: entry.previewThumbUrl || entry.previewUrl || '', full: entry.previewUrl || '' })
const thumbFailed = item => { if (item.full && item.thumb !== item.full) item.thumb = item.full }
const videoEntry = entry => ({ key: `video:${entry.id}`, kind: 'video', title: videoConversationTitle(entry.prompt), at: Date.parse(entry.createdAt) || 0, thumb: entry.outputUrl || '' })

async function fetchPage(kind, page) {
  const result = kind === 'image' ? await imageApi.history('POSTER', page) : await get('/api/video/workflows', { page, size: PAGE_SIZE })
  more.value = { ...more.value, [kind]: page + 1 < (result.totalPages || 0) }
  pages.value = { ...pages.value, [kind]: page }
  return (result.items || []).map(kind === 'image' ? imageEntry : videoEntry)
}
async function load() {
  const [image, video] = await Promise.allSettled([fetchPage('image', 0), fetchPage('video', 0)])
  failed.value = image.status === 'rejected' && video.status === 'rejected'
  if (image.status === 'fulfilled') images.value = image.value
  if (video.status === 'fulfilled') videos.value = video.value
  loaded.value = true
}
async function loadMore() {
  if (loadingMore.value) return
  loadingMore.value = true
  try {
    const kinds = ['image', 'video'].filter(kind => more.value[kind])
    const results = await Promise.allSettled(kinds.map(kind => fetchPage(kind, pages.value[kind] + 1)))
    results.forEach((result, index) => {
      if (result.status !== 'fulfilled') return
      const target = kinds[index] === 'image' ? images : videos
      target.value = [...target.value, ...result.value.filter(item => !target.value.some(existing => existing.key === item.key))]
    })
  } finally {
    loadingMore.value = false
  }
}

const items = computed(() => [...images.value, ...videos.value].sort((a, b) => b.at - a.at))
const hasMore = computed(() => more.value.image || more.value.video)
const canExpand = computed(() => items.value.length > SIDEBAR_COUNT || hasMore.value)

const DAY = 86400000
// 收起时不分组，只列最近 3 条；展开后按天分组。
const groups = computed(() => {
  if (!expanded.value) return [{ label: '', items: items.value.slice(0, SIDEBAR_COUNT) }]
  const today = new Date(); today.setHours(0, 0, 0, 0)
  const start = today.getTime()
  const buckets = [['今天', start], ['昨天', start - DAY], ['近 7 天', start - 6 * DAY], ['近 30 天', start - 29 * DAY], ['更早', -Infinity]].map(([label, from]) => ({ label, from, items: [] }))
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
  <section class="novice-recents" :class="{ expanded }" aria-label="最近的创作">
    <h2>最近</h2>
    <div id="novice-recent-list" class="novice-recent-list">
      <div v-for="group in groups" :key="group.label || 'recent'" class="novice-recent-group">
        <h3 v-if="group.label">{{ group.label }}</h3>
        <TransitionGroup name="novice-recent-item">
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
              <img v-if="item.kind === 'image' && item.thumb" :src="stableUrl(item.thumb)" alt="" loading="lazy" decoding="async" @error="thumbFailed(item)" />
              <video v-else-if="item.kind === 'video' && item.thumb" :src="`${stableUrl(item.thumb)}#t=0.1`" muted playsinline preload="metadata" />
              <ImageIcon v-else-if="item.kind === 'image'" :size="15" :stroke-width="1.6" />
              <Film v-else :size="15" :stroke-width="1.6" />
              <i v-if="item.kind === 'video' && item.thumb"><Play :size="9" fill="currentColor" :stroke-width="0" /></i>
            </span>
            <span class="novice-recent-title">{{ item.title }}</span>
            <time class="novice-recent-time" :datetime="new Date(item.at).toISOString()">{{ timeLabel(item.at) }}</time>
          </RouterLink>
        </TransitionGroup>
      </div>
      <button v-if="expanded && hasMore" type="button" class="novice-more" :disabled="loadingMore" @click="loadMore">{{ loadingMore ? '正在加载…' : '加载更早的对话' }}</button>
    </div>
    <button v-if="canExpand" type="button" class="novice-all" :aria-expanded="expanded" aria-controls="novice-recent-list" @click="expanded = !expanded">
      <span>{{ expanded ? '收起' : '查看全部' }}</span>
      <small v-if="!expanded">{{ items.length }}{{ hasMore ? '+' : '' }}</small>
      <ChevronDown :size="14" :stroke-width="1.8" aria-hidden="true" />
    </button>
    <p v-if="failed" class="novice-recent-empty">记录暂时读不到</p>
    <p v-else-if="loaded && !items.length" class="novice-recent-empty">做过的图片和视频会出现在这里</p>
  </section>
</template>

<style scoped>
.novice-recents { display: flex; flex: 0 0 auto; flex-direction: column; gap: 2px; min-height: 0; }
/* 展开后占满侧栏中间的空间，在里面滚动；底下的「我的作品」等入口不被挤走。 */
.novice-recents.expanded { flex: 1 1 auto; }
.novice-recents h2 { margin: 2px 10px 6px; color: var(--ink-muted); font-size: 11px; font-weight: 500; letter-spacing: .14em; }
.novice-recent-list { display: flex; flex-direction: column; gap: 10px; min-height: 0; }
.novice-recents.expanded .novice-recent-list { flex: 1 1 auto; margin: 0 -4px; padding: 0 4px; overflow-y: auto; scrollbar-width: thin; }
.novice-recent-group { display: flex; flex-direction: column; gap: 2px; }
.novice-recent-group h3 { position: sticky; top: 0; z-index: 1; margin: 0; padding: 2px 10px 6px; background: var(--color-bg-canvas); color: var(--ink-muted); font-size: 11px; font-weight: 500; letter-spacing: .14em; }
.novice-recent { display: flex; align-items: center; gap: 10px; min-height: 44px; padding: 4px 8px; border-radius: 10px; color: var(--ink); font-size: 13px; text-decoration: none; transition: background .15s; }
.novice-recent:hover { background: color-mix(in srgb, var(--color-bg-surface) 70%, transparent); }
.novice-recent.active { background: var(--color-bg-surface); box-shadow: 0 0 0 1px var(--line), 0 4px 12px -8px rgb(15 23 42 / 30%); }
.novice-recent-thumb { position: relative; display: grid; flex: 0 0 34px; place-items: center; width: 34px; height: 34px; overflow: hidden; border-radius: 8px; background: var(--color-bg-subtle); box-shadow: inset 0 0 0 1px var(--line); color: var(--ink-muted); }
.novice-recent-thumb img, .novice-recent-thumb video { width: 100%; height: 100%; object-fit: cover; }
.novice-recent-thumb i { position: absolute; right: 2px; bottom: 2px; display: grid; place-items: center; width: 13px; height: 13px; border-radius: 50%; background: rgb(15 23 42 / 65%); color: #fff; }
.novice-recent-title { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.novice-recent.active .novice-recent-title { font-weight: 600; }
.novice-recent-time { flex: none; color: var(--ink-muted); font-size: 11px; font-variant-numeric: tabular-nums; }
/* 展开时新出现的条目依次淡入、轻轻落下。 */
.novice-recent-item-enter-active { transition: opacity .3s ease, transform .35s cubic-bezier(.2, .8, .2, 1); }
.novice-recent-item-leave-active { transition: opacity .15s ease; }
.novice-recent-item-enter-from { opacity: 0; transform: translateY(-6px); }
.novice-recent-item-leave-to { opacity: 0; }
.novice-all { display: flex; flex: none; align-items: center; gap: 6px; min-height: 36px; margin-top: 2px; padding: 0 10px; border: 0; border-radius: 10px; background: transparent; color: var(--ink-muted); font: inherit; font-size: 13px; text-align: left; cursor: pointer; transition: background .15s, color .15s; }
.novice-all span { flex: 1; }
.novice-all small { padding: 0 7px; border-radius: 9px; background: var(--color-bg-subtle); font-size: 11px; line-height: 18px; font-variant-numeric: tabular-nums; }
.novice-all svg { transition: transform .35s cubic-bezier(.34, 1.56, .64, 1); }
.novice-all[aria-expanded='true'] svg { transform: rotate(180deg); }
.novice-all:hover { background: color-mix(in srgb, var(--color-bg-surface) 70%, transparent); color: var(--ink); }
.novice-more { align-self: center; min-height: 32px; margin: 4px 0 6px; padding: 0 14px; border: 0; border-radius: 16px; background: var(--color-bg-subtle); color: var(--ink); font: inherit; font-size: 12px; cursor: pointer; }
.novice-more:disabled { cursor: progress; opacity: .6; }
.novice-recent-empty { margin: 4px 10px; color: var(--ink-muted); font-size: 12px; line-height: 1.6; }
@media (prefers-reduced-motion: reduce) { .novice-recent-item-enter-active, .novice-recent-item-leave-active, .novice-all svg { transition: none; } }
</style>
