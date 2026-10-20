<script setup>
import AppSelect from '../components/AppSelect.vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import VideoPreviewPanel from '../components/VideoPreviewPanel.vue'
import YanhuoIcon from '../components/YanhuoIcon.vue'
import { get, post } from '../utils/request'
import { videoModels, videoModelLabel, isVideoInProgress, videoConversationTitle, videoCompletionNotice, snapshotVideoForm, videoFormFromWorkflow } from '../services/videoConversations'
import { billing, dateTime, number, refreshBilling, requireMembership } from '../stores/billing'
import { getVideoQuote, submitVideoQuote } from '../services/videoWorkflow'
import { auth } from '../stores/auth'

const prompt = ref('')
const format = ref('auto')
const duration = ref(10)
const resolution = ref('720p')
const selectedModel = ref('SEEDANCE_2_5')
const referenceImages = ref([])
const referenceVideo = ref(null)
const imageInput = ref(null)
const videoInput = ref(null)
const capabilities = ref([])
const conversations = ref([])
const activeConversationId = ref('')
const historyOpen = ref(false)
const historyLoading = ref(false)
const historyError = ref('')
const historyPage = ref(0)
const historyHasMore = ref(false)
const router = useRouter()
const route = useRoute()
const isMorphing = ref(false)
const quoted = ref(null)
const quoteError = ref('')
const quoteBusy = ref(false)
const quoteDialog = ref(null)
let quoteTarget = null
const member = computed(() => billing.summary?.memberActive && billing.summary?.plan?.videoEnabled)
let morphTimer
const pollTimers = new Map()
const objectUrls = new Set()
let disposed = false

const fallbackCapabilities = videoModels
const ratioOptions = [
  { value: 'auto', label: '自适应' },
  { value: '16:9', label: '16:9' },
  { value: '4:3', label: '4:3' },
  { value: '1:1', label: '1:1' },
  { value: '3:4', label: '3:4' },
  { value: '9:16', label: '9:16' },
  { value: '21:9', label: '21:9' },
]

const currentCapability = computed(() => (capabilities.value.length ? capabilities.value : fallbackCapabilities).find(item => item.id === selectedModel.value) || fallbackCapabilities[0])
const durationMax = computed(() => currentCapability.value.maxDurationSeconds)
const availableResolutions = computed(() => currentCapability.value.resolutions)
const activeConversation = computed(() => conversations.value.find(item => item.id === activeConversationId.value))
const workflow = computed(() => activeConversation.value?.workflow)
const isGenerating = computed(() => Boolean(activeConversation.value?.submitting) || isVideoInProgress(workflow.value))
const notice = computed(() => activeConversation.value?.notice || '')
const outputUrl = computed(() => workflow.value?.outputUrl || '')
const stageLabel = computed(() => activeConversation.value?.submitting ? '正在提交创作' : ({ SUBMIT: '正在提交创作', POLL: '画面渲染中', IMPORT: '正在保存成片', QA: '正在检查成片' }[workflow.value?.stage] || '画面渲染中'))
const readForm = () => snapshotVideoForm({ prompt: prompt.value, format: format.value, duration: duration.value, resolution: resolution.value, selectedModel: selectedModel.value, referenceImages: referenceImages.value, referenceVideo: referenceVideo.value })
const applyForm = form => {
  prompt.value = form.prompt
  format.value = form.format
  duration.value = form.duration
  resolution.value = form.resolution
  selectedModel.value = form.selectedModel
  referenceImages.value = form.referenceImages.map(entry => ({ ...entry }))
  referenceVideo.value = form.referenceVideo ? { ...form.referenceVideo } : null
}

const newConversationId = () => `video-conversation-${crypto.randomUUID()}`
const conversationStatus = (item) => {
  if (item.submitting) return '提交中'
  if (item.workflow?.status === 'SUCCEEDED') return '已完成'
  if (item.workflow && !['FAILED', 'CANCELLED'].includes(item.workflow.status)) return '生成中'
  if (item.workflow?.status === 'FAILED') return '生成失败'
  if (item.workflow?.status === 'CANCELLED') return '已取消'
  if (item.error) return '提交失败'
  return '草稿'
}
const createConversation = (form = null) => {
  const item = {
    id: newConversationId(),
    title: '新的视频创作',
    prompt: '',
    format: 'auto',
    duration: 10,
    resolution: '720p',
    selectedModel: 'SEEDANCE_2_5',
    referenceImages: [],
    referenceVideo: null,
    workflow: null,
    submitting: false,
    notice: '',
    error: false,
    updatedAt: Date.now(),
  }
  if (form) Object.assign(item, snapshotVideoForm(form), { title: videoConversationTitle(form.prompt) })
  conversations.value.unshift(item)
  activeConversationId.value = item.id
  applyForm(item)
  return activeConversation.value
}
const saveActiveConversation = () => {
  const item = activeConversation.value
  if (!item || item.workflow || item.submitting) return
  Object.assign(item, readForm(), { title: videoConversationTitle(prompt.value) })
}
const updateWorkflow = (item, result) => {
  item.workflow = result
  item.error = ['FAILED', 'CANCELLED'].includes(result.status)
  item.notice = result.status === 'SUCCEEDED' ? videoCompletionNotice(result) : item.error ? (result.error || '视频生成失败，请稍后重试。') : ''
  refreshBilling()
}
const selectConversation = async (item) => {
  saveActiveConversation()
  activeConversationId.value = item.id
  historyOpen.value = false
  applyForm(item)
  if (!item.workflow?.id) return
  item.loading = true
  try {
    const result = await get(`/api/video/workflows/${item.workflow.id}`)
    if (disposed) return
    Object.assign(item, videoFormFromWorkflow(result))
    updateWorkflow(item, result)
    if (activeConversationId.value === item.id) applyForm(item)
    if (isVideoInProgress(result)) schedulePoll(item)
  } catch (error) {
    item.notice = error.message || '读取创作记录失败，请重新选择重试。'
  } finally {
    item.loading = false
  }
}
const startNewConversation = () => {
  saveActiveConversation()
  createConversation()
  historyOpen.value = false
}

createConversation()
const recreationPrompt = window.history.state?.recreationPrompt
if (typeof recreationPrompt === 'string' && recreationPrompt.trim()) prompt.value = recreationPrompt
else if (typeof route.query.prompt === 'string' && route.query.prompt.trim()) prompt.value = route.query.prompt
if (typeof route.query.ratio === 'string' && ratioOptions.some(option => option.value === route.query.ratio)) format.value = route.query.ratio
if (typeof route.query.duration === 'string' && Number(route.query.duration)) duration.value = Math.min(30, Math.max(5, Number(route.query.duration)))
if (recreationPrompt && Number(route.query.duration) > 30) activeConversation.value.notice = '原视频超过 30 秒，已带入完整脚本。当前模型单次最多生成 30 秒，请缩短脚本或分段使用。'
saveActiveConversation()

const loadHistory = async (nextPage = 0) => {
  if (!auth.requireLogin()) return
  if (historyLoading.value) return
  historyLoading.value = true
  historyError.value = ''
  try {
    const page = await get('/api/video/workflows', { page: nextPage, size: 20 })
    if (disposed) return
    for (const result of page.items || []) {
      if (conversations.value.some(item => item.workflow?.id === result.id)) continue
      conversations.value.push({ id: `workflow-${result.id}`, title: videoConversationTitle(result.prompt),
        ...videoFormFromWorkflow(result), workflow: result, updatedAt: Date.parse(result.createdAt), notice: '', loading: false })
    }
    historyPage.value = nextPage
    historyHasMore.value = nextPage + 1 < page.totalPages
  } catch (error) {
    historyError.value = error.message || '创作记录暂时无法加载'
  } finally {
    historyLoading.value = false
  }
}
onMounted(() => { if (auth.isAuthenticated) loadHistory() })

if (auth.isAuthenticated) get('/api/video/capabilities').then(data => { if (Array.isArray(data) && data.length) capabilities.value = data }).catch(() => {})

const clampSettings = () => {
  duration.value = Math.min(durationMax.value, Math.max(5, duration.value))
  if (!availableResolutions.value.includes(resolution.value)) resolution.value = availableResolutions.value[0]
}
const selectModel = (model) => { selectedModel.value = model; clampSettings() }

const addImageFiles = (fileList) => {
  const images = Array.from(fileList || []).filter(file => file.type.startsWith('image/'))
  const remaining = Math.max(0, 6 - referenceImages.value.length)
  if (images.length > remaining) activeConversation.value.notice = '最多添加 6 张参考图。'
  referenceImages.value = [...referenceImages.value, ...images.slice(0, remaining).map(file => ({ id: crypto.randomUUID(), file, name: file.name, url: previewUrl(file), assetId: null }))]
}
const previewUrl = file => { const url = URL.createObjectURL(file); objectUrls.add(url); return url }
const addVideoFile = (fileList) => {
  const file = Array.from(fileList || []).find(item => item.type.startsWith('video/'))
  if (!file) return
  referenceVideo.value = { id: crypto.randomUUID(), file, name: file.name, url: previewUrl(file), assetId: null }
}
const handleImageChange = (event) => { addImageFiles(event.target.files); event.target.value = '' }
const handleVideoChange = (event) => { addVideoFile(event.target.files); event.target.value = '' }
const handleImageDrop = (event) => { event.preventDefault(); addImageFiles(event.dataTransfer.files) }
const removeImage = (asset) => { referenceImages.value = referenceImages.value.filter(item => item.id !== asset.id) }
const removeVideo = () => { referenceVideo.value = null }

const uploadAsset = async (entry, type) => {
  if (entry.assetId) return entry.assetId
  if (!entry.file) throw new Error('参考素材已不可用，请移除后重新上传。')
  const ticket = await post('/api/assets/upload-url', { name: entry.file.name, type, mimeType: entry.file.type })
  const response = await fetch(ticket.uploadUrl, { method: 'PUT', headers: { 'Content-Type': entry.file.type }, body: entry.file })
  if (!response.ok) throw new Error('参考素材上传失败')
  const confirmed = await post(`/api/assets/${ticket.assetId}/confirm`, { sizeBytes: entry.file.size })
  entry.assetId = confirmed.id
  return entry.assetId
}

const schedulePoll = item => {
  window.clearTimeout(pollTimers.get(item.id))
  if (!disposed) pollTimers.set(item.id, window.setTimeout(() => pollWorkflow(item), 2500))
}
const pollWorkflow = async item => {
  if (disposed) return
  try {
    const result = await get(`/api/video/workflows/${item.workflow.id}`)
    if (disposed) return
    updateWorkflow(item, result)
    if (isVideoInProgress(result)) schedulePoll(item)
  } catch (error) {
    item.notice = error.message || '状态更新暂时中断，正在重试…'
    if (isVideoInProgress(item.workflow)) schedulePoll(item)
  }
}

const generate = async () => {
  if (isGenerating.value || activeConversation.value?.loading) return
  try { await requireMembership() }
  catch (error) { activeConversation.value.notice = error.message; return }
  if (disposed || isGenerating.value) return
  if (!prompt.value.trim()) { activeConversation.value.notice = '请先描述你的成片需求。'; return }
  const form = readForm()
  if (activeConversation.value?.workflow) createConversation(form)
  saveActiveConversation()
  const item = activeConversation.value
  item.submitting = true
  item.error = false
  item.notice = '正在上传参考素材并提交视频任务…'
  try {
    const imageAssetIds = await Promise.all(item.referenceImages.map(entry => uploadAsset(entry, 'IMAGE')))
    const videoAssetId = item.referenceVideo ? await uploadAsset(item.referenceVideo, 'VIDEO') : null
    const payload = {
      prompt: item.prompt.trim(),
      referenceImageAssetIds: imageAssetIds,
      referenceVideoAssetId: videoAssetId,
      model: item.selectedModel,
      ratio: item.format,
      durationSeconds: item.duration,
      resolution: item.resolution,
    }
    const signature = JSON.stringify(payload)
    if (item.payloadSignature !== signature) { item.requestKey = `video-${crypto.randomUUID()}`; item.payloadSignature = signature }
    const request = { requestKey: item.requestKey, ...payload }
    const quote = await getVideoQuote(request)
    quoted.value = { quote, request }
    quoteTarget = item
    quoteError.value = ''
    await refreshBilling()
    await nextTick()
    quoteDialog.value?.showModal()
    item.notice = quote.available ? '费用预估已完成，请确认后开始生成。' : quote.message
  } catch (error) {
    item.error = true
    item.notice = error.message || '视频任务提交失败，请稍后重试。'
  } finally {
    item.submitting = false
  }
}
const closeQuote = () => { if (!quoteBusy.value) { quoteDialog.value?.close(); quoted.value = null; quoteTarget = null } }
const confirmQuote = async () => {
  if (!quoted.value?.quote.available || quoteBusy.value || !quoteTarget) return
  try { await requireMembership() }
  catch (error) { quoteError.value = error.message; return }
  if (disposed || quoteBusy.value || !quoted.value || !quoteTarget) return
  const { request, quote } = quoted.value
  const item = quoteTarget
  quoteBusy.value = true; item.submitting = true; quoteError.value = ''
  try {
    const result = quoted.value.retryId
      ? await post('/api/video/workflows/' + quoted.value.retryId + '/retry', { taskId: quoted.value.taskId, quoteId: quote.quoteId })
      : await submitVideoQuote(request, quote.quoteId)
    updateWorkflow(item, result)
    if (activeConversationId.value === item.id) applyForm(item)
    if (isVideoInProgress(result)) schedulePoll(item)
    quoteBusy.value = false
    closeQuote()
  } catch (e) { quoteError.value = e.message || '视频提交失败，请重试'; item.notice = quoteError.value; item.error = true }
  finally { quoteBusy.value = false; item.submitting = false; refreshBilling() }
}
const retryVideo = async () => {
  const item = activeConversation.value
  if (!item?.workflow?.retryable || item.submitting) return
  try { await requireMembership() }
  catch (error) { item.notice = error.message; return }
  if (disposed || item.submitting) return
  item.submitting = true
  try {
    const quote = await get('/api/video/workflows/' + item.workflow.id + '/retry-quote')
    quoted.value = { quote, retryId: item.workflow.id, taskId: item.workflow.taskId, request: { model: item.workflow.model, resolution: item.workflow.resolution, durationSeconds: item.workflow.durationSeconds } }
    quoteTarget = item; quoteError.value = ''; await refreshBilling(); await nextTick(); quoteDialog.value?.showModal()
  } catch (e) { item.notice = e.message || '恢复费用暂时无法预估'; item.error = true }
  finally { item.submitting = false }
}
const cancelVideo = async () => {
  const item = activeConversation.value
  if (!item?.workflow?.id || item.submitting) return
  item.submitting = true
  try { updateWorkflow(item, await post('/api/video/workflows/' + item.workflow.id + '/cancel', {})) }
  catch (e) { item.notice = e.message || '取消失败，请刷新任务后重试' }
  finally { item.submitting = false; refreshBilling() }
}
const switchWorkspace = (path) => {
  if (path === router.currentRoute.value.path) return
  if (typeof document.startViewTransition === 'function') {
    document.startViewTransition(() => router.push(path))
    return
  }
  router.push(path)
}
onBeforeUnmount(() => {
  disposed = true
  objectUrls.forEach(url => URL.revokeObjectURL(url))
  pollTimers.forEach(timer => window.clearTimeout(timer))
})
watch(selectedModel, clampSettings)
watch(format, () => {
  isMorphing.value = true
  window.clearTimeout(morphTimer)
  morphTimer = window.setTimeout(() => { isMorphing.value = false }, 900)
})
onBeforeUnmount(() => window.clearTimeout(morphTimer))
</script>

<template>
  <div class="video-workbench-page flex h-[calc(100vh-64px)] w-full overflow-hidden border-t border-gray-200 bg-white">
    <button v-if="historyOpen" class="video-history-backdrop" aria-label="关闭创作记录" type="button" @click="historyOpen = false" />
    <aside class="video-conversation-sidebar" :class="{ 'is-open': historyOpen }" aria-label="视频创作记录">
      <div class="video-conversation-head">
        <div class="video-conversation-kicker"><span class="video-seal-glyph">志</span><span>视频工作台</span></div>
        <button class="video-new-conversation" type="button" aria-label="新建对话" @click="startNewConversation"><span>＋</span> 新对话</button>
      </div>
      <div class="video-conversation-title-row">
        <span>创作记录</span><small>{{ conversations.length }} 条</small>
        <button class="video-history-close" type="button" aria-label="关闭创作记录" @click="historyOpen = false">×</button>
      </div>
      <div class="video-conversation-list">
        <button
          v-for="item in conversations"
          :key="item.id"
          class="video-conversation-item"
          :class="{ active: item.id === activeConversationId }"
          :aria-current="item.id === activeConversationId ? 'true' : undefined"
          :title="videoModelLabel(item.selectedModel)"
          type="button"
          @click="selectConversation(item)"
        >
          <span class="video-conversation-item-mark" :class="`is-${conversationStatus(item) === '已完成' ? 'done' : conversationStatus(item) === '生成中' ? 'working' : 'draft'}`" aria-hidden="true" />
          <span class="video-conversation-item-copy">
            <strong>{{ item.title }}</strong>
            <small>{{ conversationStatus(item) }} · {{ videoModelLabel(item.selectedModel) }} · {{ item.duration }} 秒 · {{ item.resolution }}</small>
          </span>
          <span class="video-conversation-item-arrow" aria-hidden="true">›</span>
        </button>
        <div v-if="!conversations.length" class="video-conversation-empty">从一段新的商家故事开始。</div>
        <p v-if="historyLoading" class="video-history-message" role="status">正在读取创作记录…</p>
        <div v-else-if="historyError" class="video-history-message" role="status"><span>{{ historyError }}</span><button type="button" @click="loadHistory(historyPage)">重新加载</button></div>
        <button v-else-if="historyHasMore" class="video-history-more" type="button" @click="loadHistory(historyPage + 1)">加载更早的创作</button>
      </div>
      <p class="video-conversation-footnote">每次生成，记录一段故事。<br>已提交的创作会自动保留。</p>
    </aside>
    <div class="video-workbench-sidebar w-[420px] h-full flex flex-col bg-white border-r border-gray-100 shadow-[4px_0_24px_rgba(0,0,0,0.02)] z-20">
      <div class="video-editor-heading"><div><h1>视频创作</h1></div><button class="video-history-trigger" type="button" :aria-expanded="historyOpen" @click="historyOpen = !historyOpen"><span class="material-symbols-outlined">history</span>创作记录</button></div>
      <p v-if="activeConversation?.loading" class="video-history-message" role="status">正在恢复提示词、参考素材与输出选项…</p>
      <fieldset class="video-workbench-scroll flex-1 overflow-y-auto p-8 space-y-8" :disabled="isGenerating || activeConversation?.loading">
        <div class="video-form-section">
          <div class="video-form-heading flex justify-between items-center mb-3">
            <h2 class="text-sm font-semibold text-gray-800">添加参考图 <em>可选</em></h2>
            <span class="text-xs text-gray-400">{{ referenceImages.length }} 张</span>
          </div>
          <div class="video-upload-actions">
            <button class="video-upload-source" type="button" @click="imageInput?.click()" @dragover.prevent @drop="handleImageDrop"><span class="material-symbols-outlined">upload</span><strong>从本地上传</strong><small>JPG、PNG · 可拖拽上传</small></button>
          </div>
          <input ref="imageInput" class="video-file-input" type="file" accept="image/*" multiple @change="handleImageChange">
          <div v-if="referenceImages.length" class="video-asset-strip flex overflow-x-auto gap-2 mt-3">
            <div v-for="asset in referenceImages" :key="asset.id" class="video-asset-thumb relative">
              <img v-if="asset.url" :src="asset.url" :alt="asset.name">
              <span v-else class="video-reference-missing">素材不可用</span>
              <button type="button" aria-label="删除参考图" @click="removeImage(asset)">×</button>
            </div>
          </div>
        </div>

        <div class="video-form-section">
          <div class="video-form-heading flex justify-between items-center mb-3">
            <h2 class="text-sm font-semibold text-gray-800">添加参考视频 <em>可选</em></h2>
            <span class="text-xs text-gray-400">{{ referenceVideo ? '1 个' : '未添加' }}</span>
          </div>
          <button v-if="!referenceVideo" class="video-upload-zone video-upload-video w-full h-24 bg-gray-50 rounded-2xl border border-gray-200 hover:border-cinnabar-300 hover:bg-cinnabar-50/30 transition-all flex flex-col items-center justify-center gap-1 group" type="button" @click="videoInput?.click()">
            <span class="material-symbols-outlined text-cinnabar-500">movie</span>
            <strong class="text-sm font-medium text-gray-600">+ 添加参考视频</strong>
            <small class="text-xs text-gray-400">MP4、MOV、WebM</small>
          </button>
          <input ref="videoInput" class="video-file-input" type="file" accept="video/*" @change="handleVideoChange">
          <div v-if="referenceVideo" class="video-reference-file">
            <span class="material-symbols-outlined">movie</span><span>{{ referenceVideo.name }}</span>
            <button type="button" aria-label="删除参考视频" @click="removeVideo">×</button>
          </div>
        </div>

        <div class="video-form-section">
          <div class="video-form-heading flex justify-between items-center mb-3">
            <h2 class="text-sm font-semibold text-gray-800">描述你的成片需求</h2>
            <span class="text-xs text-gray-400">{{ prompt.length }} 字</span>
          </div>
          <div class="video-textarea-shell bg-gray-50/80 rounded-xl p-1 border border-gray-100 focus-within:border-cinnabar-400 focus-within:ring-4 focus-within:ring-cinnabar-500/10 focus-within:bg-white transition-all">
            <textarea v-model="prompt" class="w-full h-32 bg-transparent resize-none outline-none p-3 text-sm text-gray-800 placeholder-gray-400" placeholder="例如：为这份双人海鲜套餐制作一段诱人的展示视频，镜头从特写拉远，强调食材的新鲜与就餐的松弛感。" />
          </div>
        </div>

        <div class="video-form-section video-settings-section">
          <div class="video-form-heading flex justify-between items-center mb-3">
            <h2 class="text-sm font-semibold text-gray-800">模型与输出</h2>
            <span>{{ currentCapability.label }}</span>
          </div>
          <div class="video-model-grid">
            <button v-for="model in (capabilities.length ? capabilities : fallbackCapabilities)" :key="model.id" type="button" :class="{ selected: selectedModel === model.id }" @click="selectModel(model.id)">
              <strong>{{ model.label }}</strong>
            </button>
          </div>
          <label class="video-ratio-select">
            <span class="video-setting-label">画面比例</span>
            <AppSelect v-model="format" aria-label="选择画面比例">
              <option v-for="option in ratioOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
            </AppSelect>
          </label>
          <div class="video-setting-block">
            <div class="video-duration-label flex items-center justify-between">
              <span class="video-setting-label">视频时长</span>
              <strong>{{ duration }} 秒</strong>
            </div>
            <input v-model.number="duration" class="video-duration-range" :style="{ '--duration-progress': `${((duration - 5) / Math.max(1, durationMax - 5)) * 100}%` }" type="range" min="5" :max="durationMax" step="1">
            <div class="video-range-meta flex justify-between"><span>5s</span><span>{{ durationMax }}s</span></div>
          </div>
          <div class="video-setting-block">
            <span class="video-setting-label">分辨率</span>
            <div class="video-resolution-grid">
              <button v-for="item in availableResolutions" :key="item" type="button" :class="{ selected: resolution === item }" @click="resolution = item">{{ item }}</button>
            </div>
          </div>
        </div>
      </fieldset>

      <div class="video-workbench-action p-6 border-t border-gray-100">
        <p class="generation-billing-note">视频按模型、分辨率与时长预估费用，确认后预占烟火。</p>
        <RouterLink v-if="billing.summary && !member" to="/billing" class="generation-billing-link">{{ billing.summary.memberActive ? '当前套餐未开放视频创作' : '请先开通会员' }} →</RouterLink>
        <button class="video-generate-button w-full py-3.5 bg-[#18181B] hover:bg-black text-white text-sm font-medium rounded-xl shadow-lg shadow-black/20 ring-1 ring-inset ring-white/10 transition-all flex justify-center items-center gap-2" type="button" :disabled="isGenerating || activeConversation?.loading" @click="generate">
          <span>{{ isGenerating ? '正在生成视频…' : '预估费用并生成' }}</span>
          <span>→</span>
        </button>
        <p v-if="notice" class="video-generate-notice" :class="{ 'is-error': activeConversation?.error }" role="status">{{ notice }}</p>
        <p v-if="workflow?.chargedCredits != null" class="generation-billing-note">本次交付消耗 {{ number(workflow.chargedCredits) }} 烟火。</p>
        <button v-if="workflow?.retryable" class="generation-billing-link" type="button" :disabled="activeConversation?.submitting" @click="retryVideo">预估恢复费用</button>
        <button v-if="isGenerating && workflow?.id" class="generation-billing-link" type="button" :disabled="activeConversation?.submitting" @click="cancelVideo">取消生成，退回未消耗烟火</button>
      </div>
    </div>

    <VideoPreviewPanel
      :is-generating="isGenerating"
      :is-morphing="isMorphing"
      :output-url="outputUrl"
      :actual-width="workflow?.actualWidth"
      :actual-height="workflow?.actualHeight"
      :format="workflow?.ratio || format"
      :model-label="workflow?.model ? videoModelLabel(workflow.model) : currentCapability.label"
      :duration="workflow?.durationSeconds || duration"
      :resolution="workflow?.resolution || resolution"
      :stage-label="stageLabel"
      @switch-workspace="switchWorkspace"
    />
    <dialog ref="quoteDialog" class="yanhuo-payment video-price-dialog" aria-labelledby="video-price-title" @cancel.prevent="closeQuote">
      <template v-if="quoted"><header><img src="/images/brand/yifangzhi-mark.png" alt="" width="32" height="32"><h2 id="video-price-title">确认视频费用</h2><button class="yanhuo-icon" type="button" aria-label="关闭报价" :disabled="quoteBusy" @click="closeQuote">×</button></header><p class="yanhuo-payment-item">{{ videoModelLabel(quoted.request.model) }} · {{ quoted.request.resolution }} · {{ quoted.request.durationSeconds }} 秒</p><template v-if="quoted.quote.available"><p class="yanhuo-payment-amount"><YanhuoIcon :size="28" />{{ number(quoted.quote.credits) }}<small> 烟火</small></p><p class="yanhuo-note">{{ quoted.quote.message }}</p><dl class="video-price-details"><div><dt>实际模型</dt><dd>{{ quoted.quote.providerModel }}</dd></div><div><dt>预估用量</dt><dd>{{ number(quoted.quote.estimatedTokens) }} 视频 Token</dd></div><div><dt>费率核验</dt><dd>{{ dateTime(quoted.quote.verifiedAt) }}</dd></div><div><dt>可用烟火</dt><dd>{{ billing.error ? '暂不可读' : number(billing.summary?.available || 0) }}</dd></div></dl><p v-if="billing.summary && billing.summary.available < quoted.quote.credits" class="yanhuo-alert">烟火不足，请先充值。</p><button class="yanhuo-primary" type="button" :disabled="quoteBusy || !member || !!billing.error || (billing.summary?.available || 0) < quoted.quote.credits" @click="confirmQuote">{{ quoteBusy ? '正在提交...' : '确认费用，开始生成' }}</button></template><p v-else class="yanhuo-alert">{{ quoted.quote.message }}</p><RouterLink v-if="!member || (billing.summary?.available || 0) < quoted.quote.credits" class="generation-billing-link" to="/billing" @click="closeQuote">前往套餐与充值 →</RouterLink><p v-if="quoteError" class="yanhuo-alert" role="alert">{{ quoteError }}</p></template>
    </dialog>
  </div>
</template>
