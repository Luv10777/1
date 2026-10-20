<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { imageApi, downloadImage } from '../services/imageCreation'
import { videoApi } from '../services/videoWorkflow'
import { ArrowUpRight, Check, Clock3, Download, Edit3, Image as ImageIcon, LayoutGrid, List, Play, Plus, Search, X } from 'lucide-vue-next'

const router = useRouter()
const filters = [
  { key: 'all', label: '全部' },
  { key: 'image', label: '图片' },
  { key: 'video', label: '视频' },
]
const activeFilter = ref('all')
const searchQuery = ref('')
const layout = ref('grid')
const works = ref([])
const worksPage = ref(0)
const imageTotalPages = ref(0)
const videoTotalPages = ref(0)
const worksLoading = ref(false)
const worksError = ref('')
const selectedWork = ref(null)

const filteredWorks = computed(() => {
  const query = searchQuery.value.trim().toLocaleLowerCase()
  return works.value.filter(work => (activeFilter.value === 'all' || work.type === activeFilter.value)
    && (!query || `${work.title} ${work.tags.join(' ')}`.toLocaleLowerCase().includes(query)))
})
const hasMoreWorks = computed(() => {
  const totalPages = activeFilter.value === 'image' ? imageTotalPages.value
    : activeFilter.value === 'video' ? videoTotalPages.value
      : Math.max(imageTotalPages.value, videoTotalPages.value)
  return worksPage.value + 1 < totalPages
})

function formatDate(value) {
  return new Date(value).toLocaleDateString('zh-CN')
}

function videoTitle(prompt) {
  const text = (prompt || '').trim().split(/\r?\n/)[0]
  return text.length > 54 ? `${text.slice(0, 54)}…` : text || '视频作品'
}

function mapImage(row) {
  if (!row.url) return null
  return {
    id: `image-${row.id}`,
    creationId: row.creationId,
    type: 'image',
    typeLabel: '图片',
    title: row.title || '图片作品',
    createdAt: row.createdAt,
    tags: [row.workflow === 'PRODUCT_SET' ? '产品套图' : '图片海报', row.quality],
    url: row.url,
    quality: row.quality,
    dimensions: row.width && row.height ? `${row.width} × ${row.height} px` : '未记录尺寸',
    workflow: row.workflow,
  }
}

function mapVideo(row) {
  if (!row.outputUrl) return null
  const duration = row.actualDurationMs ? `${Math.round(row.actualDurationMs / 1000)} 秒` : `${row.durationSeconds} 秒`
  const dimensions = row.actualWidth && row.actualHeight
    ? `${row.actualWidth} × ${row.actualHeight} px`
    : `${row.resolution} · ${row.ratio}`
  return {
    id: `video-${row.id}`,
    workflowId: row.id,
    type: 'video',
    typeLabel: '视频',
    title: videoTitle(row.prompt),
    createdAt: row.createdAt,
    tags: [row.model, `${row.resolution} · ${row.ratio}`, duration],
    url: row.outputUrl,
    model: row.model,
    dimensions,
    duration,
    prompt: row.prompt,
    ratio: row.ratio,
    durationSeconds: row.durationSeconds,
  }
}

async function loadWorks(page = 0) {
  if (worksLoading.value) return
  worksLoading.value = true
  worksError.value = ''
  try {
    const [images, videos] = await Promise.all([imageApi.works(page), videoApi.works(page)])
    const pageWorks = [
      ...(images.items || []).map(mapImage),
      ...(videos.items || []).map(mapVideo),
    ].filter(Boolean)
    works.value = [...(page === 0 ? [] : works.value), ...pageWorks]
      .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
    imageTotalPages.value = images.totalPages || 0
    videoTotalPages.value = videos.totalPages || 0
    worksPage.value = page
  } catch (error) {
    worksError.value = error.message || '作品暂时无法加载'
  } finally {
    worksLoading.value = false
  }
}

async function downloadWork(work) {
  if (work.type !== 'image' || !work.url) return
  try {
    await downloadImage(work.url, `一方志作品-${work.creationId}.png`)
  } catch (error) {
    worksError.value = error.message || '下载失败'
  }
}

function editWork(work) {
  closeDetails()
  if (work.type === 'image') {
    router.push({
      path: work.workflow === 'PRODUCT_SET' ? '/image/create/product-set' : '/image/create/poster',
      query: { creation: work.creationId },
    })
    return
  }
  router.push({
    path: '/video/workbench',
    query: { prompt: work.prompt, ratio: work.ratio, duration: work.durationSeconds },
  })
}

function closeDetails() {
  selectedWork.value = null
}

function handleModalKeydown(event) {
  if (event.key === 'Escape') closeDetails()
}

onMounted(() => {
  window.addEventListener('keydown', handleModalKeydown)
  loadWorks()
})
onBeforeUnmount(() => window.removeEventListener('keydown', handleModalKeydown))
</script>

<template>
  <section class="works-library">
    <header class="works-header">
      <div class="works-heading">
        <div class="works-title-copy">
          <h1>数字方志库</h1>
        </div>
        <button class="new-work-button" type="button" @click="router.push('/image/create')">
          <Plus :size="16" stroke-width="1.8" /> 新建创作
        </button>
      </div>
      <div class="works-toolbar">
        <div class="filter-tabs" role="tablist" aria-label="作品类型筛选">
          <button
            v-for="filter in filters"
            :key="filter.key"
            class="filter-tab"
            :class="{ active: activeFilter === filter.key }"
            type="button"
            role="tab"
            :aria-selected="activeFilter === filter.key"
            @click="activeFilter = filter.key"
          >
            {{ filter.label }}
          </button>
        </div>
        <div class="toolbar-actions">
          <label class="search-field">
            <Search :size="16" stroke-width="1.8" aria-hidden="true" />
            <input v-model="searchQuery" type="search" placeholder="搜索作品" aria-label="搜索作品" />
          </label>
          <div class="layout-switch" role="group" aria-label="切换布局">
            <button :class="{ active: layout === 'grid' }" type="button" aria-label="网格布局" @click="layout = 'grid'"><LayoutGrid :size="16" /></button>
            <button :class="{ active: layout === 'list' }" type="button" aria-label="列表布局" @click="layout = 'list'"><List :size="17" /></button>
          </div>
        </div>
      </div>
    </header>

    <div class="library-meta"><span>已载入 {{ filteredWorks.length }} 件作品</span></div>
    <p v-if="worksError" class="works-error" role="alert">
      {{ worksError }} <button type="button" @click="loadWorks(worksPage)">重试</button>
    </p>

    <section class="works-grid" :class="{ 'list-layout': layout === 'list' }" aria-live="polite">
      <article
        v-for="work in filteredWorks"
        :key="work.id"
        class="work-card"
        tabindex="0"
        @click="selectedWork = work"
        @keydown.enter="selectedWork = work"
      >
        <div class="work-thumb">
          <img v-if="work.type === 'image'" class="work-media" :src="work.url" :alt="work.title" loading="lazy" />
          <video v-else class="work-media" :src="work.url" :aria-label="work.title" muted playsinline preload="metadata" />
          <span v-if="work.type === 'video'" class="video-play-icon" aria-hidden="true"><Play :size="20" fill="currentColor" /></span>
          <div class="thumb-overlay">
            <button v-if="work.type === 'image'" class="download-button" type="button" aria-label="下载图片" @click.stop="downloadWork(work)">
              <Download :size="17" stroke-width="1.8" />
            </button>
          </div>
          <span class="status-pill"><Check :size="12" stroke-width="2.2" /> 已完成</span>
        </div>
        <div class="work-body">
          <div class="work-title-row">
            <h2>{{ work.title }}</h2>
            <button class="more-button" type="button" aria-label="查看详情" @click.stop="selectedWork = work"><ArrowUpRight :size="16" stroke-width="1.7" /></button>
          </div>
          <div class="work-date"><Clock3 :size="13" stroke-width="1.8" /> {{ formatDate(work.createdAt) }}</div>
          <div class="work-tags"><span v-for="tag in work.tags" :key="tag">{{ tag }}</span></div>
        </div>
      </article>
    </section>

    <div v-if="!worksLoading && !worksError && !filteredWorks.length" class="empty-state">
      <ImageIcon :size="22" stroke-width="1.5" />
      <strong>{{ works.length ? '没有找到匹配的作品' : '暂无作品' }}</strong>
    </div>
    <p v-if="worksLoading && !works.length" class="loading-state">正在读取作品…</p>
    <button v-if="hasMoreWorks" class="works-load-more" type="button" :disabled="worksLoading" @click="loadWorks(worksPage + 1)">
      {{ worksLoading ? '正在读取…' : '加载更多' }}
    </button>

    <div v-if="selectedWork" class="detail-scrim" @click.self="closeDetails">
      <section class="detail-modal" role="dialog" aria-modal="true" :aria-label="`${selectedWork.title} 详情`">
        <header class="detail-header">
          <div>
            <p class="detail-eyebrow">{{ selectedWork.typeLabel }} / HISTORY</p>
            <h2>{{ selectedWork.title }}</h2>
            <p class="detail-date">生成于 {{ formatDate(selectedWork.createdAt) }} · 已完成</p>
          </div>
          <button class="close-modal" type="button" aria-label="关闭详情" @click="closeDetails"><X :size="18" /></button>
        </header>
        <div class="generation-detail">
          <div class="detail-media">
            <img v-if="selectedWork.type === 'image'" :src="selectedWork.url" :alt="selectedWork.title" />
            <video v-else :src="selectedWork.url" controls playsinline preload="metadata" />
          </div>
          <p v-if="selectedWork.type === 'video' && selectedWork.prompt" class="detail-prompt">{{ selectedWork.prompt }}</p>
          <div class="parameter-grid">
            <div><span>类型</span><strong>{{ selectedWork.typeLabel }}</strong></div>
            <div><span>{{ selectedWork.type === 'image' ? '画质' : '模型' }}</span><strong>{{ selectedWork.quality || selectedWork.model }}</strong></div>
            <div><span>尺寸</span><strong>{{ selectedWork.dimensions }}</strong></div>
            <div v-if="selectedWork.duration"><span>时长</span><strong>{{ selectedWork.duration }}</strong></div>
          </div>
        </div>
        <footer class="detail-footer">
          <span>作品 ID · {{ selectedWork.id }}</span>
          <div>
            <button class="secondary-modal-button" type="button" @click="closeDetails">关闭</button>
            <button class="primary-modal-button" type="button" @click="editWork(selectedWork)"><Edit3 :size="14" /> 继续创作</button>
          </div>
        </footer>
      </section>
    </div>
  </section>
</template>

<style scoped>
.works-library {
  --surface: var(--color-bg-surface);
  --ink: var(--color-primary);
  --muted: var(--color-text-muted);
  --line: var(--color-border-subtle);
  min-height: 100%;
  padding: 8px 0 40px;
  color: var(--ink);
  background: var(--color-bg-canvas);
  view-transition-name: works-library;
}
.works-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 8px 2px 18px; border-bottom: 1px solid var(--line); }
.works-title-copy h1 { margin: 0; font: 650 30px/1.15 var(--font-serif); }
.new-work-button { display: inline-flex; align-items: center; gap: 8px; min-height: 36px; padding: 0 13px; border: 1px solid var(--color-accent); border-radius: 4px; color: var(--color-on-accent); background: var(--color-accent); font-size: 12px; }
.new-work-button:hover { background: var(--color-accent-hover); }
.works-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 13px 0 12px; }
.filter-tabs { display: flex; align-items: center; gap: 3px; }
.filter-tab { min-width: 52px; min-height: 32px; padding: 0 12px; border: 0; border-bottom: 2px solid transparent; color: var(--muted); background: transparent; font-size: 12px; }
.filter-tab:hover { color: var(--ink); }
.filter-tab.active { border-bottom-color: var(--color-accent); color: var(--color-accent-text); font-weight: 600; }
.toolbar-actions { display: flex; align-items: center; gap: 8px; }
.search-field { display: flex; align-items: center; gap: 8px; width: min(230px, 28vw); height: 34px; padding: 0 10px; border: 1px solid var(--line); border-radius: 4px; color: var(--muted); background: var(--surface); }
.search-field:focus-within { border-color: var(--color-accent); }
.search-field input { width: 100%; border: 0; outline: 0; color: var(--ink); background: transparent; font-size: 12px; }
.search-field input::placeholder { color: var(--muted); }
.layout-switch { display: flex; gap: 2px; padding: 2px; border: 1px solid var(--line); border-radius: 4px; background: var(--surface); }
.layout-switch button { display: grid; place-items: center; width: 29px; height: 28px; border: 0; color: var(--muted); background: transparent; }
.layout-switch button.active { color: var(--color-accent-text); background: var(--color-bg-subtle); }
.library-meta { margin: 0 0 10px; color: var(--muted); font: 500 12px var(--font-mono); }
.works-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 240px), 1fr)); align-items: start; gap: 14px; }
.work-card { min-width: 0; overflow: hidden; border: 1px solid var(--line); border-radius: 6px; background: var(--surface); transition: border-color .16s ease, transform .16s ease; cursor: pointer; }
.work-card:hover { transform: translateY(-2px); border-color: var(--color-accent); }
.work-card:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 2px; }
.work-thumb { position: relative; overflow: hidden; aspect-ratio: 4 / 3; background: var(--color-bg-subtle); }
.work-media { display: block; width: 100%; height: 100%; object-fit: cover; }
.work-thumb video { background: #202020; }
.video-play-icon { position: absolute; top: 50%; left: 50%; display: grid; place-items: center; width: 42px; height: 42px; border: 1px solid rgba(255, 255, 255, .78); border-radius: 50%; color: #fff; background: rgba(20, 20, 20, .48); transform: translate(-50%, -50%); pointer-events: none; }
.thumb-overlay { position: absolute; inset: 0; display: flex; justify-content: flex-end; padding: 10px; background: rgba(20, 20, 20, .12); opacity: 0; transition: opacity .16s ease; }
.work-card:hover .thumb-overlay, .work-card:focus-within .thumb-overlay { opacity: 1; }
.download-button { display: grid; place-items: center; width: 32px; height: 32px; border: 1px solid var(--line); border-radius: 4px; color: var(--ink); background: var(--surface); cursor: pointer; }
.status-pill { position: absolute; left: 9px; bottom: 9px; display: inline-flex; align-items: center; gap: 5px; min-height: 23px; padding: 0 8px; border: 1px solid var(--line); border-radius: 4px; color: var(--ink); background: var(--surface); font-size: 12px; }
.work-body { padding: 11px 12px 12px; }
.work-title-row { display: flex; align-items: flex-start; gap: 6px; }
.work-title-row h2 { display: -webkit-box; flex: 1; min-width: 0; overflow: hidden; margin: 0; font: 600 13px/1.45 var(--font-serif); -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.more-button { display: grid; place-items: center; flex: 0 0 auto; width: 24px; height: 24px; margin-top: -3px; border: 0; color: var(--muted); background: transparent; cursor: pointer; }
.more-button:hover { color: var(--ink); }
.work-date { display: flex; align-items: center; gap: 5px; margin-top: 7px; color: var(--muted); font: 12px var(--font-mono); }
.work-tags { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 9px; }
.work-tags span { max-width: 100%; overflow: hidden; padding: 3px 6px; border: 1px solid var(--line); border-radius: 4px; color: var(--muted); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.list-layout { grid-template-columns: 1fr; }
.list-layout .work-card { display: grid; grid-template-columns: minmax(180px, 260px) minmax(0, 1fr); }
.list-layout .work-body { display: flex; flex-direction: column; justify-content: center; padding: 14px 16px; }
.empty-state { display: grid; justify-items: center; gap: 9px; padding: 72px 16px; color: var(--muted); }
.empty-state strong { color: var(--ink); font-size: 13px; font-weight: 500; }
.loading-state { padding: 28px 0; color: var(--muted); font-size: 12px; text-align: center; }
.works-error { margin: 0 0 12px; color: var(--color-accent-text); font-size: 12px; }
.works-error button { padding: 0; border: 0; color: inherit; background: transparent; text-decoration: underline; cursor: pointer; }
.works-load-more { display: block; margin: 20px auto 0; padding: 8px 16px; border: 1px solid var(--line); border-radius: 4px; color: var(--ink); background: var(--surface); cursor: pointer; }
.works-load-more:disabled { opacity: .55; cursor: default; }
.detail-scrim { position: fixed; inset: 0; z-index: 120; display: grid; place-items: center; padding: 20px; background: rgba(18, 18, 18, .42); backdrop-filter: blur(5px); }
.detail-modal { width: min(760px, 100%); max-height: min(820px, calc(100vh - 40px)); overflow: auto; border: 1px solid var(--line); border-radius: 6px; background: var(--surface); }
.detail-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; padding: 20px 22px 16px; border-bottom: 1px solid var(--line); }
.detail-eyebrow { margin: 0 0 7px; color: var(--muted); font: 600 12px var(--font-mono); }
.detail-header h2 { margin: 0; color: var(--ink); font: 600 19px/1.35 var(--font-serif); }
.detail-date { margin: 7px 0 0; color: var(--muted); font: 12px var(--font-mono); }
.close-modal { display: grid; place-items: center; flex: 0 0 auto; width: 32px; height: 32px; border: 1px solid var(--line); border-radius: 4px; color: var(--muted); background: var(--surface); cursor: pointer; }
.generation-detail { padding: 18px 22px 20px; }
.detail-media { display: grid; place-items: center; min-height: 160px; max-height: 54vh; overflow: hidden; background: var(--color-bg-subtle); }
.detail-media img, .detail-media video { display: block; max-width: 100%; max-height: 54vh; object-fit: contain; }
.detail-prompt { margin: 14px 0 0; color: var(--ink); font-size: 12px; line-height: 1.7; white-space: pre-wrap; }
.parameter-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 8px; margin-top: 14px; }
.parameter-grid div { display: grid; gap: 6px; min-width: 0; padding: 10px; border: 1px solid var(--line); border-radius: 4px; }
.parameter-grid span { color: var(--muted); font: 12px var(--font-mono); }
.parameter-grid strong { overflow: hidden; color: var(--ink); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.detail-footer { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 13px 22px; border-top: 1px solid var(--line); color: var(--muted); font: 12px var(--font-mono); }
.detail-footer > div { display: flex; gap: 7px; }
.secondary-modal-button, .primary-modal-button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 32px; padding: 0 10px; border-radius: 4px; font-size: 12px; cursor: pointer; }
.secondary-modal-button { border: 1px solid var(--line); color: var(--muted); background: var(--surface); }
.primary-modal-button { border: 1px solid var(--color-accent); color: var(--color-on-accent); background: var(--color-accent); }
@media (max-width: 640px) {
  .works-heading, .works-toolbar { align-items: flex-start; flex-direction: column; }
  .new-work-button { align-self: flex-start; }
  .toolbar-actions { width: 100%; }
  .search-field { width: auto; flex: 1; }
  .list-layout .work-card { display: block; }
  .detail-scrim { padding: 10px; }
  .detail-modal { max-height: calc(100vh - 20px); }
  .detail-header, .generation-detail { padding-right: 15px; padding-left: 15px; }
  .detail-footer { align-items: flex-start; flex-direction: column; padding-right: 15px; padding-left: 15px; }
  .detail-footer > div { width: 100%; }
  .detail-footer button { flex: 1; }
}
@media (prefers-reduced-motion: reduce) {
  .work-card, .thumb-overlay { transition: none; }
  .work-card:hover { transform: none; }
}
</style>
