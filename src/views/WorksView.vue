<script setup>
import { billingWalletLabel } from '../domain/billing'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { stableUrl } from '../utils/stableUrl'
import { libraryApi, markLibraryRemoved, markLibrarySaved, syncLibraryFolder } from '../services/library'
import { downloadMedia } from '../services/mediaDownload'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import LibraryFolderDialog from '../components/LibraryFolderDialog.vue'
import { ArrowUpRight, Check, SquareCheck, Clock3, Download, Edit3, Folder, FolderInput, FolderPlus, Image as ImageIcon, LayoutGrid, List, ListPlus, Pencil, Play, Plus, Search, Square, Trash2, X } from 'lucide-vue-next'

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
const totalPages = ref(0)
const worksLoading = ref(false)
const worksError = ref('')
const selectedWork = ref(null)
const downloadingWorks = ref(new Set())
const downloadError = ref(null)

// 文件夹：all 是全部作品，root 是没放进文件夹的，其余是文件夹 ID。
const folders = ref([])
const rootCount = ref(0)
const activeFolder = ref('all')
const foldersError = ref('')
const currentFolder = computed(() => folders.value.find(folder => folder.id === activeFolder.value) || null)
const allCount = computed(() => rootCount.value + folders.value.reduce((sum, folder) => sum + folder.workCount, 0))
const folderName = id => folders.value.find(folder => folder.id === id)?.name || null
// 文件夹标签上只显示名字，件数在打开后的标题行里显示。
const placeCount = computed(() => currentFolder.value ? currentFolder.value.workCount : activeFolder.value === 'root' ? rootCount.value : allCount.value)

// 新建 / 改名共用一个输入框；folder 为空是新建。
const nameDialog = ref(null)
const nameBusy = ref(false)
const nameError = ref('')
// 移动作品、移出作品库、删除文件夹。
const movingWork = ref(null)
const moveBusy = ref(false)
const moveError = ref('')
const removingWork = ref(null)
const deletingFolder = ref(null)
const actionError = ref('')
// 批量整理：选中若干作品一起移动。pickTarget 有值时是从某个文件夹点「添加作品」进来的，选完直接放进它。
const selecting = ref(false)
const selectedIds = ref(new Set())
const pickTarget = ref(null)
const batchMoving = ref(false)
const notice = ref('')

const filteredWorks = computed(() => {
  const query = searchQuery.value.trim().toLocaleLowerCase()
  return works.value.filter(work => !query || `${work.title} ${work.tags.join(' ')}`.toLocaleLowerCase().includes(query))
})
const hasMoreWorks = computed(() => worksPage.value + 1 < totalPages.value)
const selectedWorks = computed(() => works.value.filter(work => selectedIds.value.has(work.id)))
const allVisibleSelected = computed(() => filteredWorks.value.length > 0 && filteredWorks.value.every(work => selectedIds.value.has(work.id)))

function formatDate(value) {
  return new Date(value).toLocaleDateString('zh-CN')
}

function videoTitle(prompt) {
  const text = (prompt || '').trim().split(/\r?\n/)[0]
  return text.length > 54 ? `${text.slice(0, 54)}…` : text || '视频作品'
}

function mapImage(row, entry) {
  if (!row?.url) return null
  return {
    id: `image-${row.id}`,
    kind: 'IMAGE',
    sourceId: row.id,
    libraryId: entry.id,
    folderId: entry.folderId,
    creationId: row.creationId,
    type: 'image',
    typeLabel: '图片',
    title: row.title || '图片作品',
    createdAt: row.createdAt,
    tags: [row.workflow === 'PRODUCT_SET' ? '产品套图' : '灵感绘图', row.quality, billingWalletLabel(row.billingWallet)].filter(Boolean),
    billingWallet: row.billingWallet,
    url: row.url,
    // 列表用小图，详情和下载用原图；更早的图没有小图时换回原图。
    thumb: row.thumbUrl || row.url,
    quality: row.quality,
    dimensions: row.width && row.height ? `${row.width} × ${row.height} px` : '未记录尺寸',
    workflow: row.workflow,
  }
}

function mapVideo(row, entry) {
  if (!row?.outputUrl) return null
  const duration = row.actualDurationMs ? `${Math.round(row.actualDurationMs / 1000)} 秒` : `${row.durationSeconds} 秒`
  const dimensions = row.actualWidth && row.actualHeight
    ? `${row.actualWidth} × ${row.actualHeight} px`
    : `${row.resolution} · ${row.ratio}`
  return {
    id: `video-${row.id}`,
    kind: 'VIDEO',
    sourceId: row.id,
    libraryId: entry.id,
    folderId: entry.folderId,
    workflowId: row.id,
    type: 'video',
    typeLabel: '视频',
    title: videoTitle(row.prompt),
    createdAt: row.createdAt,
    tags: [row.model, `${row.resolution} · ${row.ratio}`, duration, billingWalletLabel(row.billingWallet)].filter(Boolean),
    billingWallet: row.billingWallet,
    url: row.outputUrl,
    model: row.model,
    dimensions,
    duration,
    prompt: row.prompt,
    ratio: row.ratio,
    durationSeconds: row.durationSeconds,
  }
}

// 切换文件夹或类型时作废还在路上的旧请求，免得旧结果覆盖新列表。
let worksRequest = 0
async function loadWorks(page = 0) {
  if (worksLoading.value && page > 0) return
  const request = ++worksRequest
  worksLoading.value = true
  worksError.value = ''
  try {
    const kind = { image: 'IMAGE', video: 'VIDEO' }[activeFilter.value] || ''
    const result = await libraryApi.works({ folder: activeFolder.value, kind, page })
    if (request !== worksRequest) return
    const pageWorks = (result.items || [])
      .map(entry => entry.kind === 'IMAGE' ? mapImage(entry.image, entry) : mapVideo(entry.video, entry))
      .filter(Boolean)
    works.value = [...(page === 0 ? [] : works.value), ...pageWorks]
    totalPages.value = result.totalPages || 0
    worksPage.value = page
  } catch (error) {
    if (request === worksRequest) worksError.value = error.message || '作品暂时无法加载'
  } finally {
    if (request === worksRequest) worksLoading.value = false
  }
}

async function loadFolders() {
  foldersError.value = ''
  try {
    const result = await libraryApi.folders()
    folders.value = result.folders || []
    rootCount.value = result.rootCount || 0
    if (typeof activeFolder.value === 'number' && !currentFolder.value) selectFolder('all')
  } catch (error) {
    foldersError.value = error.message || '文件夹暂时无法读取'
  }
}

function selectFolder(key) {
  if (activeFolder.value === key) return
  exitSelecting()
  notice.value = ''
  activeFolder.value = key
  loadWorks(0)
}

function startSelecting(target = null) {
  notice.value = ''
  actionError.value = ''
  selectedIds.value = new Set()
  pickTarget.value = target
  selecting.value = true
}

function exitSelecting() {
  selecting.value = false
  pickTarget.value = null
  selectedIds.value = new Set()
}

/** 从文件夹点「添加作品」：去未归类里挑，选完放进这个文件夹。 */
function pickIntoFolder(folder) {
  selectFolder('root')
  startSelecting(folder)
}

function toggleSelected(work) {
  const next = new Set(selectedIds.value)
  if (next.has(work.id)) next.delete(work.id)
  else next.add(work.id)
  selectedIds.value = next
}

function toggleAllVisible() {
  const next = new Set(selectedIds.value)
  const select = !allVisibleSelected.value
  for (const work of filteredWorks.value) select ? next.add(work.id) : next.delete(work.id)
  selectedIds.value = next
}

function onCardKeydown(event, work) {
  // 只处理卡片本身的按键，卡片里按钮上的回车不打开详情。
  if (event.target !== event.currentTarget) return
  if (event.key === 'Enter') openCard(work)
  else if (event.key === ' ' && selecting.value) { event.preventDefault(); toggleSelected(work) }
}

function openCard(work) {
  if (selecting.value) toggleSelected(work)
  else selectedWork.value = work
}

function startBatchMove() {
  if (!selectedWorks.value.length) return
  if (pickTarget.value) { moveSelected(pickTarget.value.id); return }
  moveError.value = ''
  batchMoving.value = true
}

/** 一次最多 100 件，多了分批发；后端整批要么都动、要么都不动。 */
async function moveSelected(folderId) {
  const chosen = selectedWorks.value
  if (!chosen.length || moveBusy.value) return
  moveBusy.value = true
  moveError.value = ''
  actionError.value = ''
  let moved = 0, failure = ''
  try {
    for (let start = 0; start < chosen.length; start += 100) {
      const batch = chosen.slice(start, start + 100)
      await libraryApi.moveMany(batch.map(work => work.libraryId), folderId)
      for (const work of batch) work.folderId = folderId
      moved += batch.length
    }
  } catch (error) {
    failure = error.message || '移动失败，请重试'
    if (batchMoving.value && !moved) moveError.value = failure
    else actionError.value = failure
  }
  try {
    await loadFolders()
    const name = folderName(folderId)
    for (const work of chosen.slice(0, moved)) markLibrarySaved(work.kind, work.sourceId, { folderId, folderName: name })
    if (!moved) return
    const target = pickTarget.value
    batchMoving.value = false
    exitSelecting()
    if (target) {
      selectFolder(target.id)
    } else if (activeFolder.value !== 'all') {
      const here = activeFolder.value === 'root' ? null : activeFolder.value
      works.value = works.value.filter(work => work.folderId === here)
    }
    const where = folderId == null ? '移回未归类' : `放进「${name}」`
    const rest = failure ? `，其余 ${chosen.length - moved} 件没有移动` : ''
    notice.value = `已把 ${moved} 件作品${where}${rest}`
  } finally {
    moveBusy.value = false
  }
}

function selectFilter(key) {
  if (activeFilter.value === key) return
  activeFilter.value = key
  loadWorks(0)
}

function openNameDialog(folder = null) {
  nameError.value = ''
  nameDialog.value = { folder, name: folder?.name || '' }
}

async function submitName() {
  const name = nameDialog.value?.name.trim()
  if (!name || nameBusy.value) return
  nameBusy.value = true
  nameError.value = ''
  try {
    const { folder } = nameDialog.value
    if (folder) {
      const renamed = await libraryApi.renameFolder(folder.id, name)
      syncLibraryFolder(folder.id, renamed.name)
    } else {
      const created = await libraryApi.createFolder(name)
      activeFolder.value = created.id
      worksRequest++
      works.value = []
      totalPages.value = 0
      worksLoading.value = false
    }
    nameDialog.value = null
    await loadFolders()
  } catch (error) {
    nameError.value = error.message || '文件夹保存失败'
  } finally {
    nameBusy.value = false
  }
}

async function confirmDeleteFolder() {
  const folder = deletingFolder.value
  deletingFolder.value = null
  actionError.value = ''
  try {
    await libraryApi.deleteFolder(folder.id)
    syncLibraryFolder(folder.id, null)
    activeFolder.value = 'all'
    await Promise.all([loadFolders(), loadWorks(0)])
  } catch (error) {
    actionError.value = error.message || '文件夹删除失败'
  }
}

function startMove(work) {
  moveError.value = ''
  movingWork.value = work
}

async function moveWork(folderId) {
  const work = movingWork.value
  if (!work || moveBusy.value) return
  if (folderId === work.folderId) { movingWork.value = null; return }
  moveBusy.value = true
  moveError.value = ''
  try {
    await libraryApi.move(work.libraryId, folderId)
    await loadFolders()
    work.folderId = folderId
    markLibrarySaved(work.kind, work.sourceId, { folderId, folderName: folderName(folderId) })
    // 不是在「全部作品」里移动的，作品已经不属于当前位置。
    if (activeFolder.value !== 'all') {
      works.value = works.value.filter(item => item.id !== work.id)
      if (selectedWork.value?.id === work.id) closeDetails()
    }
    movingWork.value = null
  } catch (error) {
    moveError.value = error.message || '移动失败，请重试'
  } finally {
    moveBusy.value = false
  }
}

async function confirmRemoveWork() {
  const work = removingWork.value
  removingWork.value = null
  actionError.value = ''
  try {
    await libraryApi.remove(work.libraryId)
    markLibraryRemoved(work.kind, work.sourceId)
    works.value = works.value.filter(item => item.id !== work.id)
    if (selectedWork.value?.id === work.id) closeDetails()
    await loadFolders()
  } catch (error) {
    actionError.value = error.message || '移出失败，请重试'
  }
}

async function downloadWork(work) {
  if (!work.url || downloadingWorks.value.has(work.id)) return
  downloadingWorks.value.add(work.id)
  downloadError.value = null
  try {
    const extension = work.type === 'video' ? 'mp4' : 'png'
    await downloadMedia(work.url, `一方志作品-${work.id}.${extension}`)
  } catch (error) {
    downloadError.value = { workId: work.id, message: error.message || '作品下载失败，请重试' }
  } finally {
    downloadingWorks.value.delete(work.id)
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
  // 上面还叠着移动、确认这些弹窗时，Esc 先交给它们。
  if (event.key !== 'Escape' || movingWork.value || batchMoving.value || removingWork.value || deletingFolder.value) return
  if (nameDialog.value) {
    if (!nameBusy.value) nameDialog.value = null
    return
  }
  if (selectedWork.value) closeDetails()
  else if (selecting.value) exitSelecting()
}

onMounted(() => {
  window.addEventListener('keydown', handleModalKeydown)
  loadFolders()
  loadWorks()
})
onBeforeUnmount(() => window.removeEventListener('keydown', handleModalKeydown))
</script>

<template>
  <section class="works-library">
    <header class="works-header">
      <div class="works-heading">
        <div class="works-title-copy">
          <h1>作品库</h1>
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
            @click="selectFilter(filter.key)"
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

    <nav class="folder-bar" aria-label="作品库文件夹">
      <button type="button" class="folder-chip" :class="{ active: activeFolder === 'all' }" :aria-pressed="activeFolder === 'all'" @click="selectFolder('all')">全部作品</button>
      <button type="button" class="folder-chip" :class="{ active: activeFolder === 'root' }" :aria-pressed="activeFolder === 'root'" @click="selectFolder('root')">未归类</button>
      <button
        v-for="folder in folders" :key="folder.id" type="button" class="folder-chip" :class="{ active: activeFolder === folder.id }"
        :aria-pressed="activeFolder === folder.id" @click="selectFolder(folder.id)"
      >
        <Folder :size="14" stroke-width="1.8" aria-hidden="true" /><span>{{ folder.name }}</span>
      </button>
      <button type="button" class="folder-chip folder-new" @click="openNameDialog()"><FolderPlus :size="14" stroke-width="1.8" aria-hidden="true" />新建文件夹</button>
    </nav>
    <p v-if="foldersError" class="works-error" role="alert">{{ foldersError }} <button type="button" @click="loadFolders">重试</button></p>

    <div class="library-meta">
      <span>{{ currentFolder ? currentFolder.name : activeFolder === 'root' ? '未归类' : '全部作品' }} · 共 {{ placeCount }} 件作品<template v-if="filteredWorks.length !== placeCount">（已载入 {{ filteredWorks.length }} 件）</template></span>
      <span v-if="!selecting" class="folder-actions">
        <button v-if="currentFolder" type="button" @click="pickIntoFolder(currentFolder)"><ListPlus :size="13" aria-hidden="true" />添加作品</button>
        <button v-if="works.length" type="button" @click="startSelecting()"><SquareCheck :size="13" aria-hidden="true" />批量整理</button>
        <template v-if="currentFolder">
          <button type="button" @click="openNameDialog(currentFolder)"><Pencil :size="13" aria-hidden="true" />重命名</button>
          <button type="button" class="is-danger" @click="deletingFolder = currentFolder"><Trash2 :size="13" aria-hidden="true" />删除文件夹</button>
        </template>
      </span>
    </div>
    <p v-if="selecting && pickTarget" class="pick-hint">从未归类里勾选作品，点下面的「放进「{{ pickTarget.name }}」」。</p>
    <p v-if="notice" class="works-notice" role="status">{{ notice }}</p>
    <p v-if="actionError" class="works-error" role="alert">{{ actionError }}</p>
    <p v-if="worksError" class="works-error" role="alert">
      {{ worksError }} <button type="button" @click="loadWorks(worksPage)">重试</button>
    </p>
    <p v-if="downloadError && !selectedWork" class="works-error" role="alert">{{ downloadError.message }}</p>

    <section class="works-grid" :class="{ 'list-layout': layout === 'list', 'is-selecting': selecting }" aria-live="polite">
      <article
        v-for="work in filteredWorks"
        :key="work.id"
        class="work-card"
        :class="{ 'is-selected': selecting && selectedIds.has(work.id) }"
        tabindex="0"
        :role="selecting ? 'checkbox' : undefined"
        :aria-checked="selecting ? selectedIds.has(work.id) : undefined"
        @click="openCard(work)"
        @keydown="onCardKeydown($event, work)"
      >
        <div class="work-thumb">
          <span v-if="selecting" class="select-mark" aria-hidden="true"><SquareCheck v-if="selectedIds.has(work.id)" :size="18" stroke-width="2" /><Square v-else :size="18" stroke-width="1.8" /></span>
          <img v-if="work.type === 'image'" class="work-media" :src="stableUrl(work.thumb)" :alt="work.title" loading="lazy" decoding="async" @error="work.thumb = work.url" />
          <video v-else class="work-media" :src="`${stableUrl(work.url)}#t=0.1`" :aria-label="work.title" muted playsinline preload="metadata" />
          <span v-if="work.type === 'video'" class="video-play-icon" aria-hidden="true"><Play :size="20" fill="currentColor" /></span>
          <div v-if="!selecting" class="thumb-overlay">
            <button class="download-button" type="button" :aria-label="downloadingWorks.has(work.id) ? '正在下载' : `下载${work.typeLabel}`" :title="`下载${work.typeLabel}`" :disabled="downloadingWorks.has(work.id)" :aria-busy="downloadingWorks.has(work.id)" @click.stop="downloadWork(work)">
              <Download :size="17" stroke-width="1.8" />
            </button>
            <button class="download-button" type="button" aria-label="移动到文件夹" title="移动到文件夹" @click.stop="startMove(work)">
              <FolderInput :size="17" stroke-width="1.8" />
            </button>
            <button class="download-button" type="button" aria-label="移出作品库" title="移出作品库" @click.stop="removingWork = work">
              <Trash2 :size="17" stroke-width="1.8" />
            </button>
          </div>
          <span class="status-pill"><Check :size="12" stroke-width="2.2" /> 已完成</span>
        </div>
        <div class="work-body">
          <div class="work-title-row">
            <h2>{{ work.title }}</h2>
            <button v-if="!selecting" class="more-button" type="button" aria-label="查看详情" @click.stop="selectedWork = work"><ArrowUpRight :size="16" stroke-width="1.7" /></button>
          </div>
          <div class="work-date"><Clock3 :size="13" stroke-width="1.8" /> {{ formatDate(work.createdAt) }}<template v-if="activeFolder === 'all' && folderName(work.folderId)"> · <Folder :size="12" stroke-width="1.8" aria-hidden="true" />{{ folderName(work.folderId) }}</template></div>
          <div class="work-tags"><span v-for="tag in work.tags" :key="tag">{{ tag }}</span></div>
        </div>
      </article>
    </section>

    <div v-if="!worksLoading && !worksError && !filteredWorks.length" class="empty-state">
      <ImageIcon :size="22" stroke-width="1.5" />
      <strong>{{ works.length ? '没有找到匹配的作品' : currentFolder ? '这个文件夹还是空的' : selecting && pickTarget ? '未归类里没有作品了' : '暂无作品' }}</strong>
      <p v-if="!works.length && currentFolder">可以把作品库里已有的作品放进来。</p>
      <p v-else-if="!works.length">生成的图片和视频点「保存到作品库」后会出现在这里。</p>
      <button v-if="!works.length && currentFolder && rootCount" type="button" class="empty-action" @click="pickIntoFolder(currentFolder)"><ListPlus :size="14" aria-hidden="true" />从未归类里挑选作品</button>
    </div>
    <p v-if="worksLoading && !works.length" class="loading-state">正在读取作品…</p>
    <button v-if="hasMoreWorks" class="works-load-more" type="button" :disabled="worksLoading" @click="loadWorks(worksPage + 1)">
      {{ worksLoading ? '正在读取…' : '加载更多' }}
    </button>

    <div v-if="selecting" class="batch-bar" role="toolbar" aria-label="批量整理">
      <span class="batch-count">已选 {{ selectedWorks.length }} 件</span>
      <button type="button" :disabled="!filteredWorks.length" @click="toggleAllVisible">{{ allVisibleSelected ? '取消全选' : `全选已载入（${filteredWorks.length}）` }}</button>
      <span class="batch-spacer" />
      <button type="button" @click="exitSelecting">取消</button>
      <button type="button" class="is-primary" :disabled="!selectedWorks.length || moveBusy" :aria-busy="moveBusy" @click="startBatchMove">
        <FolderInput :size="14" aria-hidden="true" />{{ moveBusy ? '正在移动…' : pickTarget ? `放进「${pickTarget.name}」` : '移动到…' }}
      </button>
    </div>

    <!-- .works-library 的 view-transition-name 会形成层叠上下文，弹窗留在里面会被顶栏和侧边栏压住。 -->
    <Teleport to="body">
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
              <img v-if="selectedWork.type === 'image'" :src="stableUrl(selectedWork.url)" :alt="selectedWork.title" />
              <video v-else :src="stableUrl(selectedWork.url)" controls playsinline preload="metadata" />
            </div>
            <p v-if="selectedWork.type === 'video' && selectedWork.prompt" class="detail-prompt">{{ selectedWork.prompt }}</p>
            <div class="parameter-grid">
              <div><span>类型</span><strong>{{ selectedWork.typeLabel }}</strong></div>
              <div><span>{{ selectedWork.type === 'image' ? '画质' : '模型' }}</span><strong>{{ selectedWork.quality || selectedWork.model }}</strong></div>
              <div><span>尺寸</span><strong>{{ selectedWork.dimensions }}</strong></div>
              <div v-if="selectedWork.duration"><span>时长</span><strong>{{ selectedWork.duration }}</strong></div>
              <div v-if="selectedWork.billingWallet"><span>扣款来源</span><strong>{{ billingWalletLabel(selectedWork.billingWallet) }}</strong></div>
              <div><span>位置</span><strong>{{ folderName(selectedWork.folderId) || '未归类' }}</strong></div>
            </div>
          </div>
          <footer class="detail-footer">
            <span>作品 ID · {{ selectedWork.id }}</span>
            <div>
              <button class="secondary-modal-button" type="button" @click="removingWork = selectedWork"><Trash2 :size="14" aria-hidden="true" /> 移出作品库</button>
              <button class="secondary-modal-button" type="button" @click="startMove(selectedWork)"><FolderInput :size="14" aria-hidden="true" /> 移动</button>
              <button class="secondary-modal-button" type="button" :disabled="downloadingWorks.has(selectedWork.id)" :aria-busy="downloadingWorks.has(selectedWork.id)" @click="downloadWork(selectedWork)"><Download :size="14" aria-hidden="true" /> {{ downloadingWorks.has(selectedWork.id) ? '正在下载…' : `下载${selectedWork.typeLabel}` }}</button>
              <button class="primary-modal-button" type="button" @click="editWork(selectedWork)"><Edit3 :size="14" /> 继续创作</button>
            </div>
          </footer>
          <p v-if="downloadError?.workId === selectedWork.id" class="works-error detail-download-error" role="alert">{{ downloadError.message }}</p>
        </section>
      </div>
    </Teleport>

    <LibraryFolderDialog
      :open="!!movingWork" title="移动到" confirm-text="移到这里" :current-folder-id="movingWork?.folderId ?? null"
      :busy="moveBusy" :error="moveError" @confirm="moveWork" @cancel="movingWork = null" @folders-changed="loadFolders"
    />
    <LibraryFolderDialog
      :open="batchMoving" :title="`把 ${selectedWorks.length} 件作品移到`" confirm-text="移到这里"
      :current-folder-id="typeof activeFolder === 'number' ? activeFolder : null"
      :busy="moveBusy" :error="moveError" @confirm="moveSelected" @cancel="batchMoving = false" @folders-changed="loadFolders"
    />
    <ConfirmDialog
      :open="!!removingWork" title="移出作品库" danger confirm-text="移出"
      message="作品会从作品库里移除，生成记录不受影响，之后仍可在创作页重新保存。"
      @confirm="confirmRemoveWork" @cancel="removingWork = null"
    />
    <ConfirmDialog
      :open="!!deletingFolder" title="删除文件夹" danger confirm-text="删除"
      :message="deletingFolder?.workCount ? `「${deletingFolder.name}」里的 ${deletingFolder.workCount} 件作品会一起移出作品库，生成记录不受影响。` : `确定删除「${deletingFolder?.name}」吗？`"
      @confirm="confirmDeleteFolder" @cancel="deletingFolder = null"
    />
    <Teleport to="body">
      <div v-if="nameDialog" class="detail-scrim" @click.self="!nameBusy && (nameDialog = null)">
        <form class="folder-name-dialog" role="dialog" aria-modal="true" :aria-label="nameDialog.folder ? '重命名文件夹' : '新建文件夹'" @submit.prevent="submitName">
          <h2>{{ nameDialog.folder ? '重命名文件夹' : '新建文件夹' }}</h2>
          <input v-model="nameDialog.name" maxlength="30" placeholder="文件夹名称" aria-label="文件夹名称" :disabled="nameBusy" autofocus />
          <p v-if="nameError" class="works-error" role="alert">{{ nameError }}</p>
          <footer>
            <button class="secondary-modal-button" type="button" :disabled="nameBusy" @click="nameDialog = null">取消</button>
            <button class="primary-modal-button" type="submit" :disabled="nameBusy || !nameDialog.name.trim()">{{ nameBusy ? '正在保存…' : nameDialog.folder ? '保存' : '创建' }}</button>
          </footer>
        </form>
      </div>
    </Teleport>
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
.library-meta { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 0 0 10px; color: var(--muted); font: 500 12px var(--font-mono); }
.folder-bar { display: flex; flex-wrap: wrap; gap: 6px; margin: 0 0 12px; }
.folder-chip { display: inline-flex; align-items: center; gap: 6px; max-width: 220px; min-height: 30px; padding: 0 10px; border: 1px solid var(--line); border-radius: 4px; color: var(--muted); background: var(--surface); font-size: 12px; cursor: pointer; }
.folder-chip span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.folder-chip:hover { color: var(--ink); }
.folder-chip.active { border-color: var(--color-accent); color: var(--color-accent-text); font-weight: 600; }
.folder-new { border-style: dashed; color: var(--color-accent-text); }
.folder-actions { display: inline-flex; gap: 12px; }
.folder-actions button { display: inline-flex; align-items: center; gap: 4px; padding: 0; border: 0; color: var(--muted); background: transparent; font: inherit; cursor: pointer; }
.folder-actions button:hover { color: var(--ink); }
.folder-actions button.is-danger:hover { color: var(--color-error); }
.folder-name-dialog { display: grid; gap: 12px; width: min(360px, 100%); padding: 18px 20px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface); }
.folder-name-dialog h2 { margin: 0; color: var(--ink); font: 600 16px/1.3 var(--font-serif); }
.folder-name-dialog input { height: 36px; padding: 0 10px; border: 1px solid var(--line); border-radius: 4px; color: var(--ink); background: var(--surface); font-size: 13px; }
.folder-name-dialog input:focus { border-color: var(--color-accent); outline: 0; }
.folder-name-dialog footer { display: flex; justify-content: flex-end; gap: 7px; }
.folder-name-dialog .works-error { margin: 0; }
.works-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 240px), 1fr)); align-items: start; gap: 14px; }
.work-card { min-width: 0; overflow: hidden; border: 1px solid var(--line); border-radius: 6px; background: var(--surface); transition: border-color .16s ease, transform .16s ease; cursor: pointer; }
.work-card:hover { transform: translateY(-2px); border-color: var(--color-accent); }
.work-card:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 2px; }
.work-thumb { position: relative; overflow: hidden; aspect-ratio: 4 / 3; background: var(--color-bg-subtle); }
.work-media { display: block; width: 100%; height: 100%; object-fit: cover; }
.work-thumb video { background: #202020; }
.video-play-icon { position: absolute; top: 50%; left: 50%; display: grid; place-items: center; width: 42px; height: 42px; border: 1px solid rgba(255, 255, 255, .78); border-radius: 50%; color: #fff; background: rgba(20, 20, 20, .48); transform: translate(-50%, -50%); pointer-events: none; }
.thumb-overlay { position: absolute; inset: 0; display: flex; align-items: flex-start; justify-content: flex-end; gap: 6px; padding: 10px; background: rgba(20, 20, 20, .12); opacity: 0; transition: opacity .16s ease; }
.work-card:hover .thumb-overlay, .work-card:focus-within .thumb-overlay { opacity: 1; }
.download-button { display: grid; place-items: center; width: 32px; height: 32px; border: 1px solid var(--line); border-radius: 4px; color: var(--ink); background: var(--surface); cursor: pointer; }
.download-button:disabled, .secondary-modal-button:disabled { opacity: .55; cursor: wait; }
.download-button:focus-visible, .secondary-modal-button:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 2px; }
.detail-download-error { padding: 0 22px; }
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
.empty-state p { margin: 0; font-size: 12px; }
.empty-action { display: inline-flex; align-items: center; gap: 6px; margin-top: 4px; min-height: 32px; padding: 0 12px; border: 1px solid var(--color-accent); border-radius: 4px; color: var(--color-accent-text); background: var(--surface); font-size: 12px; cursor: pointer; }
.pick-hint, .works-notice { margin: 0 0 10px; color: var(--muted); font-size: 12px; }
.works-notice { color: var(--color-accent-text); }
.is-selecting .work-card:hover { transform: none; }
.work-card.is-selected { border-color: var(--color-accent); box-shadow: 0 0 0 1px var(--color-accent); }
.select-mark { position: absolute; top: 9px; left: 9px; z-index: 1; display: grid; place-items: center; width: 28px; height: 28px; border-radius: 4px; color: var(--ink); background: var(--surface); }
.is-selected .select-mark { color: var(--color-accent-text); }
.batch-bar { position: sticky; bottom: 12px; z-index: 5; display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 16px; padding: 10px 12px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface); box-shadow: var(--shadow-paper-elevated); }
.batch-count { color: var(--ink); font: 600 12px var(--font-mono); }
.batch-spacer { flex: 1; }
.batch-bar button { display: inline-flex; align-items: center; gap: 6px; min-height: 32px; padding: 0 12px; border: 1px solid var(--line); border-radius: 4px; color: var(--ink); background: var(--surface); font-size: 12px; cursor: pointer; }
.batch-bar button.is-primary { border-color: var(--color-accent); color: var(--color-on-accent); background: var(--color-accent); }
.batch-bar button:disabled { opacity: .55; cursor: default; }
.loading-state { padding: 28px 0; color: var(--muted); font-size: 12px; text-align: center; }
.works-error { margin: 0 0 12px; color: var(--color-error); font-size: 12px; }
.works-error button { padding: 0; border: 0; color: inherit; background: transparent; text-decoration: underline; cursor: pointer; }
.works-load-more { display: block; margin: 20px auto 0; padding: 8px 16px; border: 1px solid var(--line); border-radius: 4px; color: var(--ink); background: var(--surface); cursor: pointer; }
.works-load-more:disabled { opacity: .55; cursor: default; }
.detail-scrim { --surface: var(--color-bg-surface); --ink: var(--color-primary); --muted: var(--color-text-muted); --line: var(--color-border-subtle); position: fixed; inset: 0; z-index: 120;display: grid; place-items: center; padding: 20px; background: rgba(18, 18, 18, .42); backdrop-filter: blur(5px); }
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
