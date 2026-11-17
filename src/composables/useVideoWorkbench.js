import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { get, post } from '../utils/request'
import { videoModels, isVideoInProgress, videoConversationTitle, videoCompletionNotice, snapshotVideoForm, videoFormFromWorkflow } from '../services/videoConversations'
import { refreshBilling, requireAccount } from '../stores/billing'
import { getVideoQuote, submitVideoQuote, uploadVideoReference } from '../services/videoWorkflow'
import { auth } from '../stores/auth'
import { validateVideoMaterials, videoMaterialLimits } from '../domain/videoMaterials'
import { FREE_SCENE, fetchVideoScenes, findVideoScene, videoSceneFields } from '../services/videoScenes'

// 不再提供「自适应」：模型按参考图自己猜比例，常常和商家要发的平台对不上；改成默认不选，生成前引导选择。
export const videoRatioOptions = [
  { value: '16:9', label: '16:9' },
  { value: '4:3', label: '4:3' },
  { value: '1:1', label: '1:1' },
  { value: '3:4', label: '3:4' },
  { value: '9:16', label: '9:16' },
  { value: '21:9', label: '21:9' },
]

/** 一条视频创作当前进行到哪一步。 */
export const videoStageLabel = item => item?.submitting ? '正在提交创作' : ({ SUBMIT: '正在提交创作', POLL: '画面渲染中', IMPORT: '正在保存成片', QA: '正在检查成片' }[item?.workflow?.stage] || '画面渲染中')

/**
 * 视频创作的状态与动作：素材、场景、输出设置、报价、提交、轮询和创作记录。
 * 视频工作台和新手版对话页共用；界面相关的事（打开报价弹窗、切到预览）通过回调交给页面。
 */
export function useVideoWorkbench({ onQuote, onQuoteClose, onSubmitted } = {}) {
  const route = useRoute()
  const prompt = ref('')
  const format = ref('')
  const duration = ref(10)
  const resolution = ref('720p')
  const selectedModel = ref('SEEDANCE_2_5')
  const referenceImages = ref([])
  const referenceVideos = ref([])
  const referenceAudios = ref([])
  const scenes = ref([])
  const sceneKey = ref(FREE_SCENE)
  // 提示词来自已采纳方案时记下那批方案，生成时服务端就不再附加场景参考。
  const planId = ref(null)
  const referenceMaterials = { image: referenceImages, video: referenceVideos, audio: referenceAudios }
  const capabilities = ref([])
  const conversations = ref([])
  const activeConversationId = ref('')
  const historyLoading = ref(false)
  const historyError = ref('')
  const historyPage = ref(0)
  const historyHasMore = ref(false)
  const quoted = ref(null)
  const quoteError = ref('')
  const quoteBusy = ref(false)
  let quoteTarget = null
  const pollTimers = new Map()
  const objectUrls = new Set()
  let disposed = false
  const uploadController = new AbortController()

  const fallbackCapabilities = videoModels
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
  const stageLabel = computed(() => videoStageLabel(activeConversation.value))
  const currentScene = computed(() => findVideoScene(scenes.value, sceneKey.value))
  const readForm = () => snapshotVideoForm({ prompt: prompt.value, format: format.value, duration: duration.value, resolution: resolution.value, selectedModel: selectedModel.value, referenceImages: referenceImages.value, referenceVideos: referenceVideos.value, referenceAudios: referenceAudios.value, sceneKey: sceneKey.value, planId: planId.value })
  const applyForm = form => {
    sceneKey.value = form.sceneKey || FREE_SCENE
    planId.value = form.planId ?? null
    prompt.value = form.prompt
    format.value = videoRatioOptions.some(option => option.value === form.format) ? form.format : ''
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
      format: '',
      duration: 10,
      resolution: '720p',
      selectedModel: 'SEEDANCE_2_5',
      referenceImages: [],
      referenceVideos: [],
      referenceAudios: [],
      sceneKey: FREE_SCENE,
      planId: null,
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
  }

  createConversation()
  const recreationPrompt = window.history.state?.recreationPrompt
  if (typeof recreationPrompt === 'string' && recreationPrompt.trim()) prompt.value = recreationPrompt
  else if (typeof route.query.prompt === 'string' && route.query.prompt.trim()) prompt.value = route.query.prompt
  if (typeof route.query.ratio === 'string' && videoRatioOptions.some(option => option.value === route.query.ratio)) format.value = route.query.ratio
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
  /** 按服务端任务 ID 打开一条视频记录；记录不在已加载的列表里时先单独读取。 */
  const openWorkflow = async (workflowId) => {
    const id = String(workflowId)
    let item = conversations.value.find(entry => String(entry.workflow?.id) === id)
    if (!item) {
      item = { id: `workflow-${id}`, title: '视频创作', workflow: { id }, notice: '', loading: false, ...readForm() }
      conversations.value.unshift(item)
    }
    await selectConversation(item)
    return item
  }
  onMounted(() => { if (auth.isAuthenticated) loadHistory() })

  if (auth.isAuthenticated) get('/api/video/capabilities').then(data => { if (Array.isArray(data) && data.length) capabilities.value = data }).catch(() => {})
  // 场景列表取不到时场景一栏不显示，按自由创作提交。
  if (auth.isAuthenticated) fetchVideoScenes().then(data => { if (Array.isArray(data)) scenes.value = data }).catch(() => {})

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

  // item is the conversation being generated; the plan panel uploads the displayed materials and passes none.
  const uploadAsset = async (entry, type, item) => {
    // Keep the displayed draft's upload ID as well as the conversation snapshot's ID.
    const source = item && activeConversationId.value === item.id
      ? referenceMaterials[type.toLowerCase()].value.find(material => material.id === entry.id) || entry : entry
    try {
      return await uploadVideoReference(source, type, {
        signal: uploadController.signal,
        onStatus: asset => {
          entry.assetId = source.assetId
          if (item && asset.status === 'VERIFYING') item.notice = '正在校验参考视频，请稍候…'
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
    if (!format.value) { activeConversation.value.notice = '请先选择画面比例：发抖音、小红书、视频号选 9:16 竖屏，电脑或横屏播放选 16:9。'; return }
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
        ...videoSceneFields(item),
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
      onQuote?.(item)
      item.notice = quote.available ? '费用预估已完成，请确认后开始生成。' : quote.message
    } catch (error) {
      if (disposed) return
      item.error = true
      item.notice = error.message || '视频任务提交失败，请稍后重试。'
    } finally {
      item.submitting = false
    }
  }
  const closeQuote = () => { if (!quoteBusy.value) { onQuoteClose?.(); quoted.value = null; quoteTarget = null } }
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
      if (activeConversationId.value === item.id) { applyForm(item); onSubmitted?.(item) }
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
      quoteTarget = item; quoteError.value = ''; await refreshBilling(); await nextTick(); onQuote?.(item)
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
  const applyPlan = (form, letter, images, batchId) => {
    // 从灵感记录复用时换回当时的参考图，提示词里的「图1、图2」才对得上。
    if (images) referenceImages.value = images
    prompt.value = form.prompt
    planId.value = batchId ?? null
    // 方案没定比例时保留你已经选的。
    if (form.format) format.value = form.format
    duration.value = form.duration
    saveActiveConversation()
    activeConversation.value.error = false
    activeConversation.value.notice = form.shortened
      ? `已填入方案 ${letter}${images ? '并换回当时的参考图' : ''}；当前模型最长 ${durationMax.value} 秒，时长已缩短，提示词里的分镜可能需要再改。`
      : `已填入方案 ${letter}${images ? '并换回当时的参考图' : ''}，可以再改字后生成。`
  }
  onBeforeUnmount(() => {
    disposed = true
    uploadController.abort()
    objectUrls.forEach(url => URL.revokeObjectURL(url))
    pollTimers.forEach(timer => window.clearTimeout(timer))
  })
  watch(selectedModel, () => { clampSettings(); enforceMaterialLimits() })
  // 描述框清空就不再算「来自方案」，之后写的内容会附加场景参考。
  watch(prompt, value => { if (!value.trim()) planId.value = null })

  return {
    prompt, format, duration, resolution, selectedModel, referenceImages, referenceVideos, referenceAudios, scenes, sceneKey, planId,
    capabilities, fallbackCapabilities, conversations, activeConversationId, historyLoading, historyError, historyPage, historyHasMore,
    quoted, quoteError, quoteBusy, currentCapability, durationMax, availableResolutions, materialLimits, materialLimitLabel,
    activeConversation, workflow, isGenerating, notice, outputUrl, stageLabel, currentScene, historyRunning,
    conversationStatus, statusTone, selectConversation, startNewConversation, openWorkflow, loadHistory, selectModel,
    addMaterialFiles, handleMaterialChange, handleMaterialDrop, removeMaterial, uploadAsset,
    generate, closeQuote, confirmQuote, retryVideo, cancelVideo, applyPlan,
  }
}
