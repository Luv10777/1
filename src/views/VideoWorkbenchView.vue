<script setup>
import AppSelect from '../components/AppSelect.vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { AudioLines, ChevronRight, Film, History, ImagePlus, Plus, Sparkles, X } from 'lucide-vue-next'
import VideoPreviewPanel from '../components/VideoPreviewPanel.vue'
import VideoPlanPanel from '../components/VideoPlanPanel.vue'
import '../video-studio.css'
import YanhuoIcon from '../components/YanhuoIcon.vue'
import { get, post } from '../utils/request'
import { videoModels, videoModelLabel, isVideoInProgress, videoConversationTitle, videoHistoryTime, videoCompletionNotice, snapshotVideoForm, videoFormFromWorkflow } from '../services/videoConversations'
import { billing, dateTime, number, refreshBilling, requireAccount } from '../stores/billing'
import { getVideoQuote, submitVideoQuote, uploadVideoReference } from '../services/videoWorkflow'
import { auth } from '../stores/auth'
import { validateVideoMaterials, videoMaterialLimits } from '../domain/videoMaterials'

const prompt = ref('')
const format = ref('auto')
const duration = ref(10)
const resolution = ref('720p')
const selectedModel = ref('SEEDANCE_2_5')
const referenceImages = ref([])
const referenceVideos = ref([])
const referenceAudios = ref([])
const imageInput = ref(null)
const videoInput = ref(null)
const audioInput = ref(null)
const referenceMaterials = { image: referenceImages, video: referenceVideos, audio: referenceAudios }
const capabilities = ref([])
const conversations = ref([])
const activeConversationId = ref('')
const historyOpen = ref(false)
const rightTab = ref('preview')
const canvas = ref(null)
const settingsOpen = ref(false)
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
let morphTimer
const pollTimers = new Map()
const objectUrls = new Set()
let disposed = false
const uploadController = new AbortController()

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
const materialLimits = computed(() => videoMaterialLimits(selectedModel.value))
const materialLimitLabel = computed(() => `图片 ${materialLimits.value.image} · 视频 ${materialLimits.value.video} · 音频 ${materialLimits.value.audio}`)
const activeConversation = computed(() => conversations.value.find(item => item.id === activeConversationId.value))
const workflow = computed(() => activeConversation.value?.workflow)
const isGenerating = computed(() => Boolean(activeConversation.value?.submitting) || isVideoInProgress(workflow.value))
const notice = computed(() => activeConversation.value?.notice || '')
const outputUrl = computed(() => workflow.value?.outputUrl || '')
const stageLabel = computed(() => activeConversation.value?.submitting ? '正在提交创作' : ({ SUBMIT: '正在提交创作', POLL: '画面渲染中', IMPORT: '正在保存成片', QA: '正在检查成片' }[workflow.value?.stage] || '画面渲染中'))
const readForm = () => snapshotVideoForm({ prompt: prompt.value, format: format.value, duration: duration.value, resolution: resolution.value, selectedModel: selectedModel.value, referenceImages: referenceImages.value, referenceVideos: referenceVideos.value, referenceAudios: referenceAudios.value })
const applyForm = form => {
  prompt.value = form.prompt
  format.value = form.format
  duration.value = form.duration
  resolution.value = form.resolution
  selectedModel.value = form.selectedModel
  referenceImages.value = form.referenceImages.map(entry => ({ ...entry }))
  referenceVideos.value = (form.referenceVideos || []).map(entry => ({ ...entry }))
  referenceAudios.value = (form.referenceAudios || []).map(entry => ({ ...entry }))
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
// 创作记录里状态文字的颜色：生成中用朱砂，失败用错误色，其余保持浅灰。
const statusTone = (item) => {
  const status = conversationStatus(item)
  if (status === '生成中' || status === '提交中') return 'working'
  if (status === '生成失败' || status === '提交失败') return 'failed'
  return 'muted'
}
const historyRunning = computed(() => conversations.value.some(item => statusTone(item) === 'working'))
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
    referenceVideos: [],
    referenceAudios: [],
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
const enforceMaterialLimits = () => {
  let trimmed = false
  for (const [type, materials] of Object.entries(referenceMaterials)) {
    if (materials.value.length > materialLimits.value[type]) {
      materials.value = materials.value.slice(0, materialLimits.value[type])
      trimmed = true
    }
  }
  if (trimmed) activeConversation.value.notice = `已按 ${currentCapability.value.label} 的素材上限保留已选内容。`
}
const selectModel = (model) => { selectedModel.value = model; clampSettings(); enforceMaterialLimits() }

const previewUrl = file => { const url = URL.createObjectURL(file); objectUrls.add(url); return url }
const addMaterialFiles = (type, fileList) => {
  if (isGenerating.value || activeConversation.value?.loading) return
  const files = Array.from(fileList || []).filter(file => file.type.startsWith(`${type}/`))
  const materials = referenceMaterials[type]
  const remaining = Math.max(0, materialLimits.value[type] - materials.value.length)
  if (files.length > remaining) {
    const label = { image: '张图片', video: '个参考视频', audio: '个音频' }[type]
    activeConversation.value.notice = `当前模型最多支持 ${materialLimits.value[type]} ${label}。`
  }
  materials.value = [...materials.value, ...files.slice(0, remaining).map(file => ({ id: crypto.randomUUID(), file, name: file.name, url: previewUrl(file), assetId: null }))]
}
const handleMaterialChange = (type, event) => { addMaterialFiles(type, event.target.files); event.target.value = '' }
const handleMaterialDrop = (type, event) => { event.preventDefault(); addMaterialFiles(type, event.dataTransfer.files) }
const removeMaterial = (type, asset) => { referenceMaterials[type].value = referenceMaterials[type].value.filter(item => item.id !== asset.id) }

const uploadAsset = async (entry, type, item) => {
  // Keep the displayed draft's upload ID as well as the conversation snapshot's ID.
  const source = activeConversationId.value === item.id
    ? referenceMaterials[type.toLowerCase()].value.find(material => material.id === entry.id) || entry : entry
  try {
    return await uploadVideoReference(source, type, {
      signal: uploadController.signal,
      onStatus: asset => {
        entry.assetId = source.assetId
        if (asset.status === 'VERIFYING') item.notice = '正在校验参考视频，请稍候…'
      },
    })
  } finally {
    entry.assetId = source.assetId
  }
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
  try { await requireAccount() }
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
    validateVideoMaterials({ model: item.selectedModel, images: item.referenceImages, videos: item.referenceVideos, audios: item.referenceAudios })
    const imageAssetIds = await Promise.all(item.referenceImages.map(entry => uploadAsset(entry, 'IMAGE', item)))
    const videoAssetIds = await Promise.all(item.referenceVideos.map(entry => uploadAsset(entry, 'VIDEO', item)))
    const audioAssetIds = await Promise.all(item.referenceAudios.map(entry => uploadAsset(entry, 'AUDIO', item)))
    if (disposed) return
    item.notice = '参考素材已就绪，正在预估费用…'
    const payload = {
      prompt: item.prompt.trim(),
      referenceImageAssetIds: imageAssetIds,
      referenceVideoAssetIds: videoAssetIds,
      referenceAudioAssetIds: audioAssetIds,
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
    if (disposed) return
    item.error = true
    item.notice = error.message || '视频任务提交失败，请稍后重试。'
  } finally {
    item.submitting = false
  }
}
const closeQuote = () => { if (!quoteBusy.value) { quoteDialog.value?.close(); quoted.value = null; quoteTarget = null } }
const confirmQuote = async () => {
  if (!quoted.value?.quote.available || quoteBusy.value || !quoteTarget) return
  try { await requireAccount() }
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
    // 确认开始生成后切到成片预览看进度（例如刚从视频方案填入后点了生成）。
    if (activeConversationId.value === item.id) { applyForm(item); openRightTab('preview') }
    if (isVideoInProgress(result)) schedulePoll(item)
    quoteBusy.value = false
    closeQuote()
  } catch (e) { quoteError.value = e.message || '视频提交失败，请重试'; item.notice = quoteError.value; item.error = true }
  finally { quoteBusy.value = false; item.submitting = false; refreshBilling() }
}
const retryVideo = async () => {
  const item = activeConversation.value
  if (!item?.workflow?.retryable || item.submitting) return
  try { await requireAccount() }
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
const openRightTab = async (tab) => {
  rightTab.value = tab
  historyOpen.value = false
  await nextTick()
  // 手机上预览区在表单下方，点了入口直接滚过去。
  if (window.matchMedia('(max-width: 760px)').matches) canvas.value?.$el?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
const applyPlan = (form, letter, images) => {
  // 从灵感记录复用时换回当时的参考图，提示词里的「图1、图2」才对得上。
  if (images) referenceImages.value = images
  prompt.value = form.prompt
  format.value = form.format
  duration.value = form.duration
  saveActiveConversation()
  activeConversation.value.error = false
  activeConversation.value.notice = form.shortened
    ? `已填入方案 ${letter}${images ? '并换回当时的参考图' : ''}；当前模型最长 ${durationMax.value} 秒，时长已缩短，提示词里的分镜可能需要再改。`
    : `已填入方案 ${letter}${images ? '并换回当时的参考图' : ''}，可以再改字后生成。`
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
  uploadController.abort()
  objectUrls.forEach(url => URL.revokeObjectURL(url))
  pollTimers.forEach(timer => window.clearTimeout(timer))
})
watch(selectedModel, () => { clampSettings(); enforceMaterialLimits() })
watch(format, () => {
  isMorphing.value = true
  window.clearTimeout(morphTimer)
  morphTimer = window.setTimeout(() => { isMorphing.value = false }, 900)
})
onBeforeUnmount(() => window.clearTimeout(morphTimer))
</script>

<template>
  <div class="video-workbench-page flex h-[calc(100vh-64px)] w-full overflow-hidden border-t border-gray-200 bg-white">
    <div class="video-workbench-sidebar w-[420px] h-full flex flex-col bg-white border-r border-gray-100 shadow-[4px_0_24px_rgba(0,0,0,0.02)] z-20">
      <div class="video-editor-heading"><div><h1>视频创作</h1></div><button class="vp-entry" type="button" :aria-pressed="rightTab === 'plan'" @click="openRightTab('plan')"><Sparkles :size="15" :stroke-width="1.8" aria-hidden="true" />帮我出方案</button><button class="video-history-trigger" type="button" :aria-expanded="historyOpen" @click="historyOpen = !historyOpen"><span class="material-symbols-outlined">history</span>创作记录</button></div>
      <p v-if="activeConversation?.loading" class="video-history-message" role="status">正在恢复提示词、参考素材与输出选项…</p>
      <fieldset class="video-workbench-scroll vs-form flex-1 overflow-y-auto" :disabled="isGenerating || activeConversation?.loading">
        <section class="vs-group">
          <span class="vs-label">素材<small>均可选</small></span>
          <div class="vs-tiles">
            <button class="vs-tile" type="button" @click="imageInput?.click()" @dragover.prevent @drop="handleMaterialDrop('image', $event)"><ImagePlus :size="18" :stroke-width="1.6" aria-hidden="true" /><span>参考图片</span><small>最多 {{ materialLimits.image }} 张</small></button>
            <button class="vs-tile" type="button" @click="videoInput?.click()" @dragover.prevent @drop="handleMaterialDrop('video', $event)"><Film :size="18" :stroke-width="1.6" aria-hidden="true" /><span>参考视频</span><small>最多 {{ materialLimits.video }} 个</small></button>
            <button class="vs-tile" type="button" @click="audioInput?.click()" @dragover.prevent @drop="handleMaterialDrop('audio', $event)"><AudioLines :size="18" :stroke-width="1.6" aria-hidden="true" /><span>音色</span><small>最多 {{ materialLimits.audio }} 个</small></button>
          </div>
          <div v-if="referenceImages.length || referenceVideos.length || referenceAudios.length" class="vs-files">
            <span v-for="asset in referenceImages" :key="asset.id" class="vs-file"><img v-if="asset.url" :src="asset.url" :alt="asset.name"><span>{{ asset.name }}</span><button type="button" aria-label="删除参考图" @click="removeMaterial('image', asset)"><X :size="12" :stroke-width="2.2" aria-hidden="true" /></button></span>
            <span v-for="asset in referenceVideos" :key="asset.id" class="vs-file"><Film :size="16" :stroke-width="1.6" aria-hidden="true" /><span>{{ asset.name }}</span><button type="button" aria-label="删除参考视频" @click="removeMaterial('video', asset)"><X :size="12" :stroke-width="2.2" aria-hidden="true" /></button></span>
            <span v-for="asset in referenceAudios" :key="asset.id" class="vs-file"><AudioLines :size="16" :stroke-width="1.6" aria-hidden="true" /><span>{{ asset.name }}</span><button type="button" aria-label="删除音色" @click="removeMaterial('audio', asset)"><X :size="12" :stroke-width="2.2" aria-hidden="true" /></button></span>
          </div>
          <input ref="imageInput" class="video-file-input" type="file" accept="image/*" multiple @change="handleMaterialChange('image', $event)">
          <input ref="videoInput" class="video-file-input" type="file" accept="video/*" multiple @change="handleMaterialChange('video', $event)">
          <input ref="audioInput" class="video-file-input" type="file" accept="audio/*" multiple @change="handleMaterialChange('audio', $event)">
        </section>

        <section class="vs-group">
          <label for="video-prompt" class="vs-label">描述你的成片需求</label>
          <textarea id="video-prompt" v-model="prompt" class="vs-textarea" rows="5" placeholder="例如：为这份双人海鲜套餐制作一段诱人的展示视频，镜头从特写拉远，强调食材的新鲜与就餐的松弛感。" />
        </section>

        <section class="vs-settings">
          <button type="button" class="vs-settings-toggle" :aria-expanded="settingsOpen" aria-controls="video-settings" @click="settingsOpen = !settingsOpen"><span>输出设置</span><small>{{ currentCapability.label }} · {{ ratioOptions.find(option => option.value === format)?.label }} · {{ duration }} 秒 · {{ resolution }}</small><ChevronRight :size="15" class="vs-chevron" :class="{ open: settingsOpen }" aria-hidden="true" /></button>
          <div v-show="settingsOpen" id="video-settings" class="vs-settings-body">
            <div class="vs-segmented vs-models" role="group" aria-label="视频模型"><button v-for="model in (capabilities.length ? capabilities : fallbackCapabilities)" :key="model.id" type="button" :aria-pressed="selectedModel === model.id" :class="{ active: selectedModel === model.id }" @click="selectModel(model.id)">{{ model.label }}</button></div>
            <label class="vs-setting-row"><span>画面比例</span><AppSelect v-model="format" class="vs-row-select" aria-label="选择画面比例"><option v-for="option in ratioOptions" :key="option.value" :value="option.value">{{ option.label }}</option></AppSelect></label>
            <div class="vs-setting-row vs-duration"><label for="video-duration">视频时长</label><strong>{{ duration }} 秒</strong><input id="video-duration" v-model.number="duration" class="vs-range" type="range" min="5" :max="durationMax" step="1"></div>
            <div class="vs-setting-row"><span>分辨率</span><div class="vs-segmented" role="group" aria-label="分辨率"><button v-for="item in availableResolutions" :key="item" type="button" :aria-pressed="resolution === item" :class="{ active: resolution === item }" @click="resolution = item">{{ item }}</button></div></div>
            <small class="vs-setting-note">{{ currentCapability.label }} 可用素材：{{ materialLimitLabel }}</small>
          </div>
        </section>
      </fieldset>

      <div class="video-workbench-action vs-footer">
        <p v-if="notice" class="vs-notice" :class="{ 'is-error': activeConversation?.error }" role="status">{{ notice }}</p>
        <button class="vs-generate" type="button" :disabled="isGenerating || activeConversation?.loading" @click="generate">{{ isGenerating ? '正在生成视频…' : '预估费用并生成' }}</button>
        <small>先预估烟火，确认后开始生成</small>
        <p v-if="workflow?.chargedCredits != null" class="vs-notice">本次交付消耗 {{ number(workflow.chargedCredits) }} 烟火。</p>
        <button v-if="workflow?.retryable" class="generation-billing-link" type="button" :disabled="activeConversation?.submitting" @click="retryVideo">预估恢复费用</button>
        <button v-if="isGenerating && workflow?.id" class="generation-billing-link" type="button" :disabled="activeConversation?.submitting" @click="cancelVideo">取消生成，退回未消耗烟火</button>
      </div>
    </div>

    <VideoPreviewPanel
      ref="canvas"
      :class="{ 'is-planning': rightTab === 'plan' }"
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
    >
      <template #title>
        <div class="vp-tabs" role="tablist" aria-label="右侧内容">
          <button type="button" role="tab" :aria-selected="rightTab === 'preview'" :class="{ active: rightTab === 'preview' }" @click="rightTab = 'preview'">成片预览</button>
          <button type="button" role="tab" :aria-selected="rightTab === 'plan'" :class="{ active: rightTab === 'plan' }" @click="rightTab = 'plan'">视频方案</button>
        </div>
      </template>
      <template #actions>
        <button type="button" class="vs-drawer-toggle" :aria-expanded="historyOpen" aria-controls="video-history" @click="historyOpen = !historyOpen"><History :size="16" :stroke-width="1.7" aria-hidden="true" />创作记录<i v-if="historyRunning" aria-hidden="true" /><span v-if="historyRunning" class="sr-only">，有任务生成中</span></button>
      </template>
      <VideoPlanPanel
        v-show="rightTab === 'plan'"
        :images="referenceImages"
        :video="referenceVideos[0]"
        :upload="uploadAsset"
        :max-duration="durationMax"
        :ratios="ratioOptions.map(option => option.value)"
        :locked="isGenerating || Boolean(activeConversation?.loading)"
        @apply="applyPlan"
      />
      <div v-if="historyOpen" class="vs-drawer-backdrop" aria-hidden="true" @click="historyOpen = false" />
      <aside v-if="historyOpen" id="video-history" class="vs-drawer" aria-label="视频创作记录" @keydown.esc="historyOpen = false">
        <div class="vs-drawer-head">
          <span>创作记录<small v-if="conversations.length">{{ conversations.length }}</small></span>
          <button type="button" class="vs-drawer-icon" aria-label="新对话" title="新对话" @click="startNewConversation"><Plus :size="18" :stroke-width="1.8" aria-hidden="true" /></button>
          <button type="button" class="vs-drawer-icon" aria-label="关闭创作记录" @click="historyOpen = false"><X :size="18" :stroke-width="1.8" aria-hidden="true" /></button>
        </div>
        <div class="vs-drawer-list">
          <button
            v-for="item in conversations"
            :key="item.id"
            class="vs-drawer-item"
            :class="{ active: item.id === activeConversationId }"
            :aria-current="item.id === activeConversationId ? 'true' : undefined"
            :title="`${item.title} · ${videoModelLabel(item.selectedModel)} · ${item.resolution}`"
            type="button"
            @click="selectConversation(item)"
          >
            <span class="vs-drawer-thumb"><video v-if="item.workflow?.outputUrl" :src="`${item.workflow.outputUrl}#t=0.1`" muted playsinline preload="metadata" aria-hidden="true" /><Film v-else :size="18" :stroke-width="1.6" aria-hidden="true" /></span>
            <span class="vs-drawer-text"><strong>{{ item.title }}</strong><small><span :class="`is-${statusTone(item)}`">{{ conversationStatus(item) }}</span> · {{ item.duration }} 秒<template v-if="item.updatedAt"> · {{ videoHistoryTime(item.updatedAt) }}</template></small></span>
          </button>
          <p v-if="!conversations.length" class="vs-drawer-empty">从一段新的商家故事开始。</p>
          <p v-if="historyLoading" class="vs-drawer-empty" role="status">正在读取创作记录…</p>
          <template v-else-if="historyError">
            <p class="vs-drawer-empty is-error" role="status">{{ historyError }}</p>
            <button type="button" class="vs-drawer-more" @click="loadHistory(historyPage)">重新加载</button>
          </template>
          <button v-else-if="historyHasMore" type="button" class="vs-drawer-more" @click="loadHistory(historyPage + 1)">加载更早的创作</button>
        </div>
      </aside>
    </VideoPreviewPanel>
    <dialog ref="quoteDialog" class="yanhuo-payment video-price-dialog" aria-labelledby="video-price-title" @cancel.prevent="closeQuote">
      <template v-if="quoted"><header><img src="/images/brand/yifangzhi-mark.png" alt="" width="32" height="32"><h2 id="video-price-title">确认视频费用</h2><button class="yanhuo-icon" type="button" aria-label="关闭报价" :disabled="quoteBusy" @click="closeQuote">×</button></header><p class="yanhuo-payment-item">{{ videoModelLabel(quoted.request.model) }} · {{ quoted.request.resolution }} · {{ quoted.request.durationSeconds }} 秒</p><template v-if="quoted.quote.available"><p class="yanhuo-payment-amount"><YanhuoIcon :size="28" />{{ number(quoted.quote.credits) }}</p><p class="yanhuo-note">{{ quoted.quote.message }}</p><dl class="video-price-details"><div><dt>实际模型</dt><dd>{{ quoted.quote.providerModel }}</dd></div><div><dt>预估用量</dt><dd>{{ number(quoted.quote.estimatedTokens) }} 视频 Token</dd></div><div><dt>费率核验</dt><dd>{{ dateTime(quoted.quote.verifiedAt) }}</dd></div><div><dt>可用烟火</dt><dd>{{ billing.error ? '暂不可读' : number(billing.summary?.available || 0) }}</dd></div></dl><p v-if="billing.summary && billing.summary.available < quoted.quote.credits" class="yanhuo-alert">烟火不足，请先充值。</p><button class="yanhuo-primary" type="button" :disabled="quoteBusy || !!billing.error || (billing.summary?.available || 0) < quoted.quote.credits" @click="confirmQuote">{{ quoteBusy ? '正在提交...' : '确认费用，开始生成' }}</button></template><p v-else class="yanhuo-alert">{{ quoted.quote.message }}</p><RouterLink v-if="(billing.summary?.available || 0) < quoted.quote.credits" class="generation-billing-link" to="/billing" @click="closeQuote">前往套餐与充值</RouterLink><p v-if="quoteError" class="yanhuo-alert" role="alert">{{ quoteError }}</p></template>
    </dialog>
  </div>
</template>
