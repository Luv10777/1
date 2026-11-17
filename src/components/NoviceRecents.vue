<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { ChevronRight, Film, ImageIcon, Play, Search, X } from 'lucide-vue-next'
import { imageApi } from '../services/imageCreation'
import { get } from '../utils/request'
import { videoConversationTitle } from '../services/videoConversations'
import { NOVICE_RECENTS_CHANGED } from '../stores/uiMode'

// 新手版侧栏的「最近」：灵感绘图和视频创作各自的记录按时间合在一起。
// 侧栏只放最近 3 条（缩略图都是原图，少放几张也省流量），其余在「全部对话」弹窗里按天分组、可搜索、可往前翻。
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
const open = ref(false)
const query = ref('')
const searchInput = ref(null)

const imageEntry = entry => ({ key: `image:${entry.id}`, kind: 'image', title: entry.title || entry.brief || '绘图创作', at: Date.parse(entry.createdAt) || 0, thumb: entry.previewUrl || '' })
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
const recent = computed(() => items.value.slice(0, SIDEBAR_COUNT))
const hasMore = computed(() => more.value.image || more.value.video)
const filtered = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  return keyword ? items.value.filter(item => item.title.toLowerCase().includes(keyword)) : items.value
})

const DAY = 86400000
const groups = computed(() => {
  const today = new Date(); today.setHours(0, 0, 0, 0)
  const start = today.getTime()
  const buckets = [['今天', start], ['昨天', start - DAY], ['近 7 天', start - 6 * DAY], ['近 30 天', start - 29 * DAY], ['更早', -Infinity]].map(([label, from]) => ({ label, from, items: [] }))
  for (const item of filtered.value) buckets.find(bucket => item.at >= bucket.from).items.push(item)
  return buckets.filter(bucket => bucket.items.length)
})
const timeLabel = at => {
  const date = new Date(at)
  const today = new Date(); today.setHours(0, 0, 0, 0)
  return at >= today.getTime()
    ? date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
    : `${date.getMonth() + 1}/${date.getDate()}`
}

async function showAll() {
  open.value = true
  await nextTick()
  searchInput.value?.focus()
}
function close() { open.value = false; query.value = '' }
function choose() { close(); emit('open') }
const onKey = event => { if (open.value && event.key === 'Escape') close() }
// 弹窗开着时换了页面（比如点了某条记录）就收起。
watch(() => route.fullPath, () => { if (open.value) close() })

onMounted(() => {
  load()
  window.addEventListener(NOVICE_RECENTS_CHANGED, load)
  window.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => {
  window.removeEventListener(NOVICE_RECENTS_CHANGED, load)
  window.removeEventListener('keydown', onKey)
})
</script>

<template>
  <section class="novice-recents" aria-label="最近的创作">
    <h2>最近</h2>
    <RouterLink
      v-for="item in recent"
      :key="item.key"
      :to="{ name: 'studio', query: { open: item.key } }"
      class="novice-recent"
      :class="{ active: route.query.open === item.key }"
      :title="item.title"
      @click="emit('open')"
    >
      <span class="novice-recent-thumb" aria-hidden="true">
        <img v-if="item.kind === 'image' && item.thumb" :src="item.thumb" alt="" loading="lazy" decoding="async" />
        <video v-else-if="item.kind === 'video' && item.thumb" :src="`${item.thumb}#t=0.1`" muted playsinline preload="metadata" />
        <ImageIcon v-else-if="item.kind === 'image'" :size="15" :stroke-width="1.6" />
        <Film v-else :size="15" :stroke-width="1.6" />
        <i v-if="item.kind === 'video' && item.thumb"><Play :size="9" fill="currentColor" :stroke-width="0" /></i>
      </span>
      <span class="novice-recent-title">{{ item.title }}</span>
      <time class="novice-recent-time" :datetime="new Date(item.at).toISOString()">{{ timeLabel(item.at) }}</time>
    </RouterLink>
    <button v-if="items.length > SIDEBAR_COUNT || hasMore" type="button" class="novice-all" @click="showAll">
      <span>全部对话</span><small>{{ items.length }}{{ hasMore ? '+' : '' }}</small><ChevronRight :size="14" :stroke-width="1.8" aria-hidden="true" />
    </button>
    <p v-if="failed" class="novice-recent-empty">记录暂时读不到</p>
    <p v-else-if="loaded && !items.length" class="novice-recent-empty">做过的图片和视频会出现在这里</p>
  </section>

  <Teleport to="body">
    <Transition name="nr-dialog">
      <div v-if="open" class="nr-backdrop" @click.self="close">
        <section class="nr-dialog" role="dialog" aria-modal="true" aria-labelledby="nr-dialog-title">
          <header class="nr-head">
            <h2 id="nr-dialog-title">全部对话</h2>
            <button type="button" class="nr-close" aria-label="关闭" @click="close"><X :size="18" :stroke-width="1.8" aria-hidden="true" /></button>
          </header>
          <label class="nr-search">
            <Search :size="16" :stroke-width="1.8" aria-hidden="true" />
            <span class="sr-only">搜索对话</span>
            <input ref="searchInput" v-model="query" type="search" placeholder="搜索对话标题" />
          </label>
          <div class="nr-list">
            <section v-for="group in groups" :key="group.label" class="nr-group" :aria-label="group.label">
              <h3>{{ group.label }}</h3>
              <RouterLink
                v-for="item in group.items"
                :key="item.key"
                :to="{ name: 'studio', query: { open: item.key } }"
                class="nr-item"
                :class="{ active: route.query.open === item.key }"
                @click="choose"
              >
                <span class="nr-thumb" aria-hidden="true">
                  <img v-if="item.kind === 'image' && item.thumb" :src="item.thumb" alt="" loading="lazy" decoding="async" />
                  <video v-else-if="item.kind === 'video' && item.thumb" :src="`${item.thumb}#t=0.1`" muted playsinline preload="metadata" />
                  <ImageIcon v-else-if="item.kind === 'image'" :size="16" :stroke-width="1.6" />
                  <Film v-else :size="16" :stroke-width="1.6" />
                  <i v-if="item.kind === 'video' && item.thumb"><Play :size="9" fill="currentColor" :stroke-width="0" /></i>
                </span>
                <span class="nr-title">{{ item.title }}</span>
                <time :datetime="new Date(item.at).toISOString()">{{ timeLabel(item.at) }}</time>
              </RouterLink>
            </section>
            <p v-if="!filtered.length" class="nr-empty">{{ query ? '没有找到相关的对话' : '还没有对话' }}</p>
            <button v-if="hasMore" type="button" class="nr-more" :disabled="loadingMore" @click="loadMore">{{ loadingMore ? '正在加载…' : '加载更早的对话' }}</button>
          </div>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.novice-recents { display: flex; flex: 0 0 auto; flex-direction: column; gap: 2px; }
.novice-recents h2 { margin: 2px 10px 6px; color: var(--ink-muted); font-size: 11px; font-weight: 500; letter-spacing: .14em; }
.novice-recent { display: flex; align-items: center; gap: 10px; min-height: 44px; padding: 4px 8px; border-radius: 10px; color: var(--ink); font-size: 13px; text-decoration: none; transition: background .15s; }
.novice-recent:hover { background: color-mix(in srgb, var(--color-bg-surface) 70%, transparent); }
.novice-recent.active { background: var(--color-bg-surface); box-shadow: 0 0 0 1px var(--line), 0 4px 12px -8px rgb(15 23 42 / 30%); }
.novice-recent-thumb, .nr-thumb { position: relative; display: grid; flex: 0 0 34px; place-items: center; width: 34px; height: 34px; overflow: hidden; border-radius: 8px; background: var(--color-bg-subtle); box-shadow: inset 0 0 0 1px var(--line); color: var(--ink-muted); }
.novice-recent-thumb img, .novice-recent-thumb video, .nr-thumb img, .nr-thumb video { width: 100%; height: 100%; object-fit: cover; }
.novice-recent-thumb i, .nr-thumb i { position: absolute; right: 2px; bottom: 2px; display: grid; place-items: center; width: 13px; height: 13px; border-radius: 50%; background: rgb(15 23 42 / 65%); color: #fff; }
.novice-recent-title { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.novice-recent.active .novice-recent-title { font-weight: 600; }
.novice-recent-time { flex: none; color: var(--ink-muted); font-size: 11px; font-variant-numeric: tabular-nums; }
.novice-all { display: flex; align-items: center; gap: 6px; min-height: 36px; margin-top: 2px; padding: 0 10px; border: 0; border-radius: 10px; background: transparent; color: var(--ink-muted); font: inherit; font-size: 13px; text-align: left; cursor: pointer; transition: background .15s, color .15s; }
.novice-all span { flex: 1; }
.novice-all small { padding: 0 7px; border-radius: 9px; background: var(--color-bg-subtle); font-size: 11px; line-height: 18px; font-variant-numeric: tabular-nums; }
.novice-all svg { transition: transform .3s cubic-bezier(.34, 1.56, .64, 1); }
.novice-all:hover { background: color-mix(in srgb, var(--color-bg-surface) 70%, transparent); color: var(--ink); }
.novice-all:hover svg { transform: translateX(3px); }
.novice-recent-empty { margin: 4px 10px; color: var(--ink-muted); font-size: 12px; line-height: 1.6; }

/* 全部对话：居中弹窗，顶部搜索，按天分组，可往前翻。 */
.nr-backdrop { position: fixed; inset: 0; z-index: 80; display: grid; place-items: center; padding: 24px 16px; background: rgb(15 23 42 / 32%); backdrop-filter: blur(3px); }
.nr-dialog { display: flex; flex-direction: column; width: min(560px, 100%); max-height: min(680px, 86vh); overflow: hidden; border-radius: 20px; background: var(--color-bg-surface); box-shadow: 0 0 0 1px var(--line), 0 30px 80px -24px rgb(15 23 42 / 45%); color: var(--ink); }
.nr-head { display: flex; align-items: center; justify-content: space-between; padding: 18px 20px 10px; }
.nr-head h2 { margin: 0; font: 600 18px var(--font-serif); letter-spacing: .06em; }
.nr-close { display: grid; place-items: center; width: 34px; height: 34px; padding: 0; border: 0; border-radius: 50%; background: transparent; color: var(--ink-muted); cursor: pointer; }
.nr-close:hover { background: var(--color-bg-subtle); color: var(--ink); }
.nr-search { display: flex; align-items: center; gap: 8px; margin: 0 20px 10px; padding: 0 12px; border-radius: 12px; background: var(--color-bg-subtle); color: var(--ink-muted); }
.nr-search input { flex: 1; min-width: 0; height: 40px; border: 0; outline: none; background: transparent; color: var(--ink); font: inherit; font-size: 14px; }
.nr-search:focus-within { box-shadow: 0 0 0 1px var(--line-strong); }
.nr-list { flex: 1; min-height: 0; padding: 0 12px 16px; overflow-y: auto; scrollbar-width: thin; }
.nr-group h3 { position: sticky; top: 0; z-index: 1; margin: 0; padding: 10px 10px 6px; background: var(--color-bg-surface); color: var(--ink-muted); font-size: 11px; font-weight: 500; letter-spacing: .14em; }
.nr-item { display: flex; align-items: center; gap: 12px; min-height: 52px; padding: 6px 10px; border-radius: 12px; color: var(--ink); font-size: 14px; text-decoration: none; transition: background .15s; }
.nr-item:hover, .nr-item.active { background: var(--color-bg-subtle); }
.nr-item .nr-thumb { flex-basis: 40px; width: 40px; height: 40px; border-radius: 10px; }
.nr-title { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nr-item time { flex: none; color: var(--ink-muted); font-size: 12px; font-variant-numeric: tabular-nums; }
.nr-empty { margin: 24px 0; color: var(--ink-muted); font-size: 13px; text-align: center; }
.nr-more { display: block; margin: 10px auto 0; min-height: 36px; padding: 0 16px; border: 0; border-radius: 18px; background: var(--color-bg-subtle); color: var(--ink); font: inherit; font-size: 13px; cursor: pointer; }
.nr-more:disabled { cursor: progress; opacity: .6; }
.nr-dialog-enter-active { transition: opacity .25s ease; }
.nr-dialog-enter-active .nr-dialog { transition: transform .4s cubic-bezier(.2, .9, .3, 1.15), opacity .25s ease; }
.nr-dialog-leave-active { transition: opacity .18s ease; }
.nr-dialog-leave-active .nr-dialog { transition: transform .18s ease, opacity .18s ease; }
.nr-dialog-enter-from, .nr-dialog-leave-to { opacity: 0; }
.nr-dialog-enter-from .nr-dialog { opacity: 0; transform: translateY(12px) scale(.97); }
.nr-dialog-leave-to .nr-dialog { opacity: 0; transform: scale(.98); }
@media (prefers-reduced-motion: reduce) { .nr-dialog-enter-active, .nr-dialog-leave-active, .nr-dialog-enter-active .nr-dialog, .nr-dialog-leave-active .nr-dialog, .novice-all svg { transition: none; } }
</style>
