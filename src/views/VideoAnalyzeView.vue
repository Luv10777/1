<script setup>
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { videoAnalysisApi, uploadAnalysisVideo } from '../services/videoAnalysis'
import { VIDEO_ANALYSIS_LIMITS, PRODUCT_REFERENCE_GUIDANCE, analysisTerminal, analysisTime, analysisAudioSummary, analysisReportMode, analysisRecreationSteps, analysisShotDetails, nearestAnalysisFrame, validateAnalysisFile } from '../domain/videoAnalysis'
import { customizeAnalysisRecreation, analysisRecreationRoute } from '../domain/videoAnalysisCustomization'
import { buildVideoAnalysisReport } from '../domain/videoAnalysisReport'

const router = useRouter()
const route = useRoute()
const inputMode = ref('upload')
const videoType = ref('ai')
const recreationRoute = ref('filming')
const videoUrl = ref('')
const reverseNeed = ref('')
const fileInput = ref(null)
const player = ref(null)
const selectedFile = shallowRef(null)
const selectedName = ref('')
const previewUrl = ref('')
const previewDuration = ref(0)
const previewAspectRatio = ref(16 / 9)
const uploadedAssetId = ref(null)
const limits = ref({ ...VIDEO_ANALYSIS_LIMITS, configured: false })
const current = ref(null)
const uploading = ref(false)
const loadingHistory = ref(false)
const error = ref('')
const pollingInterrupted = ref(false)
const historyOpen = ref(false)
const reverseHistories = ref([])
const copied = ref('')
const merchantEdits = ref({})
let objectUrl = ''
let pollTimer
let copyTimer
let pollGeneration = 0
let disposed = false
let submissionKey = ''

const isScanning = computed(() => uploading.value || (current.value && !analysisTerminal(current.value.status)))
const result = computed(() => current.value?.status === 'SUCCEEDED' ? customizeAnalysisRecreation(current.value, merchantEdits.value) : null)
const reportMode = computed(() => result.value ? analysisReportMode(current.value, videoType.value) : videoType.value)
const showGeneration = computed(() => reportMode.value === 'ai' || recreationRoute.value === 'ai')
const recreationSteps = computed(() => analysisRecreationSteps(result.value, reportMode.value, recreationRoute.value))
const modeHint = computed(() => videoType.value === 'ai'
  ? '生成可直接复制的中文总提示词和简短分镜，支持替换品牌、对白与字幕。'
  : '侧重机位、运镜、灯光与拍摄步骤，同时提供用 AI 重做的方案。')
const needPlaceholder = computed(() => videoType.value === 'ai'
  ? '例如：保留镜头动作和节奏，替换成我的品牌和口播…'
  : '例如：用手机怎么拍？灯光怎么摆？或重点拆解用 AI 重做的方法…')
const quickFocus = computed(() => videoType.value === 'ai'
  ? [{ label: '提示词复刻', text: '侧重可直接复制的中文整体提示词，商品用如图中产品，识别可修改的品牌和文案' }, { label: '分镜脚本', text: '用简短分镜写清时间、动作、运镜和衔接，识别原对白与字幕供修改' }]
  : [{ label: '照着实拍', text: '侧重低成本实拍复刻，写清手机机位、运镜、布光和拍摄步骤' }, { label: '用 AI 重做', text: '侧重把实拍视频用 AI 重做，给出中文总提示词与简短分镜，识别可修改的品牌、对白与字幕' }])
const promptText = computed(() => result.value?.prompt || '')
const duration = computed(() => current.value?.durationMs ? current.value.durationMs / 1000 : previewDuration.value)
const canStart = computed(() => !isScanning.value && !loadingHistory.value && limits.value.configured
  && (inputMode.value === 'url' ? Boolean(videoUrl.value.trim()) : Boolean(selectedFile.value || uploadedAssetId.value)))
const progress = computed(() => uploading.value ? 5 : current.value?.progress || 0)
const statusLabel = computed(() => {
  if (uploading.value) return '正在上传视频'
  return { QUEUED: '已提交，等待分析', EXTRACTING: '正在校验视频并提取素材', ANALYZING_AUDIO: '正在分析口播、配乐与音效', ANALYZING: '正在分析画面并合成报告',
    SUCCEEDED: '分析完成', FAILED: '分析未完成' }[current.value?.status] || '等待视频载入'
})
const parameters = computed(() => {
  if (!result.value) return []
  const ratio = current.value?.width && current.value?.height ? `${current.value.width} × ${current.value.height}` : '未知'
  return [...result.value.parameters, { key: '画幅时长', value: `${ratio} · ${duration.value.toFixed(1)} 秒` },
    { key: '声音内容', value: analysisAudioSummary(result.value) }]
})
const audioReport = computed(() => result.value?.audioAnalyzed === true && result.value?.audio?.status === 'ANALYZED' ? result.value.audio : null)
const audioTranscript = computed(() => (audioReport.value?.transcript || []).map(segment =>
  `${analysisTime(segment.start)}–${analysisTime(segment.end)} ${segment.text}`).join('\n'))
const audioFactors = computed(() => audioReport.value ? [
  { key: '人声与表达', value: audioReport.value.speech }, { key: '配乐与节奏', value: audioReport.value.music },
  { key: '环境声音', value: audioReport.value.ambience },
] : [])
const keyframes = computed(() => (result.value?.keyframes || []).map(frame => ({
  ...frame, ...nearestAnalysisFrame(current.value?.frames, frame.seconds),
})))
const shots = computed(() => result.value?.shots || [])
const dimensions = computed(() => {
  const order = ['光影', '运镜', '主体', '场景', '色彩']
  return order.map(name => ({ name, score: result.value?.dimensions?.find(item => item.name === name)?.score || 0 }))
})
const radarPoints = computed(() => {
  const vertices = [[90, 14], [157, 63], [132, 138], [48, 138], [23, 63]]
  return vertices.map(([x, y], index) => {
    const weight = Math.max(0, Math.min(100, dimensions.value[index].score)) / 100
    return `${90 + (x - 90) * weight},${80 + (y - 80) * weight}`
  }).join(' ')
})
const realMetrics = computed(() => [
  { label: '视频时长', value: `${duration.value.toFixed(1)} 秒`, delta: '实际媒体信息' },
  { label: '拆解镜头', value: String(shots.value.length), delta: '模型估计分镜' },
  { label: '分析画面', value: String(current.value?.frames?.length || 0), delta: '实际抽取图片' },
  { label: '源视频尺寸', value: `${current.value?.width || 0} × ${current.value?.height || 0}`, delta: '实际媒体信息' },
])

function stopPolling() { window.clearTimeout(pollTimer); pollGeneration += 1 }

async function refreshHistory() {
  try {
    const page = await videoAnalysisApi.list()
    if (!disposed) reverseHistories.value = page.items || page.content || []
  } catch (e) { if (!disposed && historyOpen.value) error.value = e.message }
}

function poll(id) {
  stopPolling()
  const generation = pollGeneration
  pollingInterrupted.value = false
  let failures = 0
  const tick = async () => {
    try {
      const analysis = await videoAnalysisApi.get(id)
      if (disposed || generation !== pollGeneration) return
      current.value = analysis
      if (analysis.videoUrl && !objectUrl) previewUrl.value = analysis.videoUrl
      failures = 0
      if (analysisTerminal(analysis.status)) {
        error.value = analysis.status === 'FAILED' ? analysis.errorMessage || '分析未完成，请重试。' : ''
        refreshHistory()
        return
      }
    } catch (e) {
      if (disposed || generation !== pollGeneration) return
      failures += 1
      if (failures >= 5) {
        error.value = `连接中断：${e.message}。已提交的分析会继续处理。`
        pollingInterrupted.value = true
        return
      }
    }
    pollTimer = window.setTimeout(tick, failures ? 5000 : 2000)
  }
  tick()
}

async function openHistory(item) {
  if (uploading.value || (isScanning.value && current.value?.id !== item.id)) return
  historyOpen.value = false
  await router.push({ path: '/video/analyze', query: { history: String(item.id) } })
}

async function loadHistory(id) {
  if (!/^\d+$/.test(String(id))) return
  stopPolling()
  loadingHistory.value = true
  error.value = ''
  try {
    const analysis = await videoAnalysisApi.get(id)
    if (disposed || String(route.query.history) !== String(id)) return
    releasePreview()
    current.value = analysis
    selectedFile.value = null
    selectedName.value = analysis.name
    uploadedAssetId.value = analysis.assetId
    previewUrl.value = analysis.videoUrl || ''
    previewAspectRatio.value = analysis.width > 0 && analysis.height > 0 ? analysis.width / analysis.height : 16 / 9
    videoType.value = analysis.mode
    reverseNeed.value = analysis.reverseNeed || ''
    inputMode.value = 'upload'
    submissionKey = ''
    if (!analysisTerminal(analysis.status)) poll(analysis.id)
    else if (analysis.status === 'FAILED') error.value = analysis.errorMessage || '分析未完成，请重试。'
  } catch (e) { if (!disposed) error.value = e.message }
  finally { if (!disposed) loadingHistory.value = false }
}

async function renameHistory(item) {
  if (!item.name.trim()) { await refreshHistory(); return }
  try { await videoAnalysisApi.rename(item.id, item.name.trim()) }
  catch (e) { error.value = e.message; await refreshHistory() }
}

function releasePreview() { if (objectUrl) URL.revokeObjectURL(objectUrl); objectUrl = '' }

function selectFile(file) {
  if (isScanning.value) return
  const message = validateAnalysisFile(file, limits.value)
  if (message) { error.value = message; return }
  stopPolling()
  releasePreview()
  selectedFile.value = file
  selectedName.value = file.name
  uploadedAssetId.value = null
  current.value = null
  previewDuration.value = 0
  previewAspectRatio.value = 16 / 9
  objectUrl = URL.createObjectURL(file)
  previewUrl.value = objectUrl
  inputMode.value = 'upload'
  submissionKey = ''
  error.value = ''
  router.replace({ path: '/video/analyze', query: {} })
}

function onFile(event) {
  const file = event.target.files?.[0]
  if (file) selectFile(file)
  event.target.value = ''
}
function onDrop(event) { const file = event.dataTransfer?.files?.[0]; if (file) selectFile(file) }
function onMetadata(event) {
  if (event.target.videoWidth > 0 && event.target.videoHeight > 0)
    previewAspectRatio.value = event.target.videoWidth / event.target.videoHeight
  previewDuration.value = event.target.duration
  if (selectedFile.value && (!Number.isFinite(event.target.duration) || event.target.duration > limits.value.maxDurationSeconds)) {
    error.value = `视频最长支持 ${limits.value.maxDurationSeconds} 秒，请剪辑后上传。`
    selectedFile.value = null
  }
}

async function startScan() {
  if (!canStart.value) return
  uploading.value = true
  error.value = ''
  if (!submissionKey || current.value) submissionKey = crypto.randomUUID()
  try {
    const request = { requestKey: submissionKey, mode: videoType.value, reverseNeed: reverseNeed.value.trim() }
    let analysis
    if (inputMode.value === 'url') {
      const url = new URL(videoUrl.value.trim())
      if (url.protocol !== 'https:') throw new Error('请使用公开 HTTPS 视频文件直链。')
      analysis = await videoAnalysisApi.importUrl({ ...request, url: url.href })
    } else {
      if (!uploadedAssetId.value) uploadedAssetId.value = await uploadAnalysisVideo(selectedFile.value, limits.value)
      analysis = await videoAnalysisApi.create({ ...request, assetId: uploadedAssetId.value })
    }
    if (disposed) return
    current.value = analysis
    await router.replace({ path: '/video/analyze', query: { history: String(analysis.id) } })
    poll(analysis.id)
    refreshHistory()
  } catch (e) { if (!disposed) error.value = e.message || '分析提交失败，请重试。' }
  finally { if (!disposed) uploading.value = false }
}

async function copyText(text, label) {
  try {
    await navigator.clipboard.writeText(text)
    copied.value = label
    window.clearTimeout(copyTimer)
    copyTimer = window.setTimeout(() => { copied.value = '' }, 1800)
  } catch { error.value = '复制失败，请手动选择文本复制。' }
}
function generateSame() { router.push(analysisRecreationRoute(current.value, merchantEdits.value)) }
function seek(seconds) { if (player.value) player.value.currentTime = seconds }
function exportReport() {
  if (!result.value) return
  const url = URL.createObjectURL(new Blob([buildVideoAnalysisReport(current.value, merchantEdits.value)], { type: 'text/html;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${current.value.name.replace(/[\\/:*?"<>|]/g, '_')}-拆解报告.html`
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

watch(() => route.query.history, id => { if (id && String(current.value?.id) !== String(id)) loadHistory(id) })
watch(() => current.value?.id, () => { recreationRoute.value = 'filming'; merchantEdits.value = {} })
watch([videoType, reverseNeed, inputMode, videoUrl], () => { if (!uploading.value) submissionKey = '' })
onMounted(async () => {
  const settled = await Promise.allSettled([videoAnalysisApi.limits(), refreshHistory()])
  if (disposed) return
  if (settled[0].status === 'fulfilled') {
    limits.value = settled[0].value
    if (!limits.value.configured) error.value = '分析服务尚未配置，请联系管理员。'
  } else error.value = settled[0].reason.message
  if (route.query.history) loadHistory(route.query.history)
})
onBeforeUnmount(() => { disposed = true; stopPolling(); releasePreview(); window.clearTimeout(copyTimer) })
</script>

<template>
  <div class="analyze-page premium-analyze live-analysis">
    <main class="premium-grid">
      <aside class="premium-left">
        <section class="premium-panel input-panel">
          <div class="panel-head"><div><span class="step-index">01</span><strong>输入素材</strong></div><small>输入方式</small></div>
          <div class="compact-tabs">
            <button type="button" :disabled="isScanning" :class="{ active: inputMode === 'upload' }" @click="inputMode = 'upload'"><span class="material-symbols-outlined">upload_file</span>本地上传</button>
            <button type="button" :disabled="isScanning" :class="{ active: inputMode === 'url' }" @click="inputMode = 'url'"><span class="material-symbols-outlined">link</span>视频地址</button>
          </div>
          <div
            v-if="inputMode === 'upload'" class="compact-drop" role="button" :tabindex="isScanning ? -1 : 0" :aria-disabled="Boolean(isScanning)"
            @click="!isScanning && fileInput?.click()" @keydown.enter.prevent="!isScanning && fileInput?.click()"
            @keydown.space.prevent="!isScanning && fileInput?.click()" @dragover.prevent @drop.prevent="onDrop"
          >
            <input ref="fileInput" type="file" accept=".mp4,.mov,video/mp4,video/quicktime" hidden :disabled="isScanning" @change="onFile">
            <span class="material-symbols-outlined">movie</span><strong :title="selectedName || '拖拽或点击上传'">{{ selectedName || '拖拽或点击上传' }}</strong>
            <small>MP4 / MOV · 最长 {{ limits.maxDurationSeconds }} 秒 · 最大 {{ Math.floor(limits.maxBytes / 1024 / 1024) }} MB</small>
          </div>
          <div v-else class="compact-url"><span class="material-symbols-outlined">link</span><input v-model="videoUrl" :disabled="isScanning" aria-label="公开视频文件地址" placeholder="粘贴 HTTPS 视频直链"></div>
          <p v-if="inputMode === 'url'" class="mode-hint">支持公开的视频文件直链；平台分享页请先下载再上传。</p>
        </section>
        <section class="premium-panel type-panel">
          <div class="panel-head"><div><span class="step-index">02</span><strong>视频类型</strong></div><small>分析模式</small></div>
          <div class="pill-switch">
            <button type="button" :disabled="isScanning" :class="{ active: videoType === 'ai' }" @click="videoType = 'ai'"><span class="material-symbols-outlined">auto_awesome</span>AI 视频</button>
            <button type="button" :disabled="isScanning" :class="{ active: videoType === 'real' }" @click="videoType = 'real'"><span class="material-symbols-outlined">videocam</span>实拍视频</button>
          </div>
          <p class="mode-hint">{{ modeHint }}</p>
          <p v-if="result && reportMode !== videoType" class="mode-hint">当前报告为{{ reportMode === 'ai' ? 'AI 视频' : '实拍视频' }}拆解，点击“重新分析”生成所选类型的报告。</p>
          <div class="need-field">
            <label for="reverse-need">反推需求 <em>可选</em></label><textarea id="reverse-need" v-model="reverseNeed" :disabled="isScanning" maxlength="2000" :placeholder="needPlaceholder"></textarea>
            <div><button v-for="focus in quickFocus" :key="focus.label" type="button" :disabled="isScanning" @click="reverseNeed = focus.text">{{ focus.label }}</button></div>
          </div>
        </section>
        <section class="premium-panel preview-panel">
          <div class="panel-head"><div><span class="material-symbols-outlined panel-icon">play_circle</span><strong>视频预览</strong></div><small>{{ duration ? analysisTime(duration) : '等待输入' }}</small></div>
          <video v-if="previewUrl" ref="player" class="analysis-video" :src="previewUrl" :style="{ aspectRatio: previewAspectRatio }" controls playsinline preload="metadata" @loadedmetadata="onMetadata" />
          <div v-else class="skeleton-player" :class="{ scanning: isScanning }"><div v-if="isScanning" class="scan-beam" /><div class="skeleton-grid" /><div class="skeleton-center"><span class="material-symbols-outlined">movie</span><p>{{ statusLabel }}</p><small>选择视频后点击开始分析</small></div></div>
        </section>
        <button type="button" class="analyze-trigger" :disabled="!canStart" @click="startScan"><span class="material-symbols-outlined">auto_awesome</span>{{ isScanning ? statusLabel : current ? '重新分析' : '开始分析' }} <span>↗</span></button>
        <p class="privacy-note"><span class="material-symbols-outlined">lock</span>原视频保留供回看，临时文件自动清理</p>
        <div v-if="error" class="analysis-error" role="alert">{{ error }}<button v-if="pollingInterrupted" type="button" @click="error = ''; poll(current.id)">重新连接</button></div>
      </aside>

      <section class="premium-console" :aria-busy="Boolean(isScanning || loadingHistory)">
        <div class="console-head">
          <div><p class="premium-overline">分析工作区</p><h2>{{ reportMode === 'ai' ? 'AI 提示词与分镜复刻' : '实拍拆解与复刻方案' }}</h2></div>
          <div class="console-tools history-tools">
            <button type="button" class="history-trigger" :aria-expanded="historyOpen" @click="historyOpen = !historyOpen; historyOpen && refreshHistory()"><span class="material-symbols-outlined">history</span><span>反推历史</span><span class="material-symbols-outlined history-chevron">expand_more</span></button>
            <div v-if="historyOpen" class="history-menu">
              <div class="history-menu-head"><strong>反推历史</strong><small>最近 {{ reverseHistories.length }} 条记录</small></div><p v-if="!reverseHistories.length" class="analysis-history-empty">暂无分析记录</p>
              <div v-for="item in reverseHistories" :key="item.id" class="history-item"><input v-model="item.name" maxlength="200" aria-label="重命名历史记录" @change="renameHistory(item)"><time>{{ new Date(item.createdAt).toLocaleDateString('zh-CN') }}</time><button type="button" class="history-open" aria-label="打开此条反推历史" :disabled="Boolean(isScanning && current?.id !== item.id)" @click="openHistory(item)"><span class="material-symbols-outlined">arrow_forward</span></button></div>
            </div>
          </div>
        </div>
        <div v-if="loadingHistory || isScanning" class="analysis-state" role="status" aria-live="polite"><span class="material-symbols-outlined">frame_inspect</span><h3>{{ loadingHistory ? '正在读取分析记录' : statusLabel }}</h3><p>分析完成后，将在这里显示关键帧、镜头拆解、声音报告和复刻提示词。</p><progress v-if="!loadingHistory" :value="progress" max="100" :aria-label="statusLabel" /><small v-if="current?.frames?.length">已提取 {{ current.frames.length }} 张画面</small></div>
        <div v-else-if="!result" class="analysis-state"><span class="material-symbols-outlined">movie_filter</span><h3>{{ current?.status === 'FAILED' ? '本次分析未完成' : '从一条视频开始' }}</h3><p>{{ current?.status === 'FAILED' ? current.errorMessage || '请重新提交分析。' : '上传视频并选择分析模式，提炼画面风格与可复用的镜头语言。' }}</p></div>
        <div v-else class="console-body" :class="{ 'real-console': reportMode === 'real' }">
          <div v-if="reportMode === 'real'" class="recreation-tabs" role="group" aria-label="选择复刻方式">
            <button type="button" :aria-pressed="recreationRoute === 'filming'" :class="{ active: recreationRoute === 'filming' }" @click="recreationRoute = 'filming'"><span class="material-symbols-outlined">videocam</span>照着实拍</button>
            <button type="button" :aria-pressed="recreationRoute === 'ai'" :class="{ active: recreationRoute === 'ai' }" @click="recreationRoute = 'ai'"><span class="material-symbols-outlined">auto_awesome</span>用 AI 重做</button>
          </div>
          <p v-if="reportMode === 'real'" class="analysis-method">{{ showGeneration ? '上传自己的商品参考图，修改品牌与文案，用总提示词和简短分镜生成相似视频。' : '按下面的相机位置、灯光和动作步骤，准备拍摄并逐镜复刻。' }}</p>
          <section v-if="showGeneration" class="readable-prompt" aria-label="可直接复制的复刻提示词">
            <div class="reuse-script-head"><span>{{ reportMode === 'real' ? '用 AI 重做的复刻提示词' : '可直接复制的复刻提示词' }}</span><button type="button" @click="copyText(promptText, 'prompt')">{{ copied === 'prompt' ? '已复制' : '复制中文提示词' }}</button></div>
            <p class="product-reference-guide">{{ PRODUCT_REFERENCE_GUIDANCE }}</p>
            <p class="copyable-content" tabindex="0" aria-label="整体复刻提示词正文">{{ promptText }}</p><details><summary>生成时需要避免的问题</summary><p>{{ result.negativePrompt }}</p></details>
          </section>
          <section class="reuse-script" aria-label="可复制的分镜脚本"><div class="reuse-script-head"><span>{{ showGeneration ? '可直接复制的分镜脚本' : '按顺序执行的拍摄脚本' }}</span><button type="button" @click="copyText(showGeneration ? result.generationScript : result.reuseScript, 'script')">{{ copied === 'script' ? '已复制' : '复制脚本' }}</button></div><p class="copyable-content" tabindex="0" aria-label="复刻脚本正文">{{ showGeneration ? result.generationScript : result.reuseScript }}</p></section>
          <section v-if="showGeneration" class="merchant-editor" aria-label="修改品牌、对白与字幕">
            <div class="section-caption"><span>改成你的内容</span><button v-if="Object.keys(merchantEdits).length" type="button" @click="merchantEdits = {}">恢复默认</button></div>
            <p>对照原文修改，提示词和分镜脚本会自动更新。原品牌默认不使用，相关文案改称“本店”；对白与字幕清空即可移除。</p>
            <div v-if="result.editableContent.length" class="merchant-edit-table">
              <div class="merchant-edit-columns" aria-hidden="true"><span>原视频内容</span><span>商家修改内容</span></div>
              <article v-for="item in result.editableContent" :key="item.id" class="merchant-edit-row">
                <div class="merchant-original"><strong>{{ item.label }}</strong><small>{{ item.kindLabel }} · {{ item.source === 'audio' ? '声音原文' : '画面文字' }} · {{ item.timeLabel }}</small><p>{{ item.original }}</p></div>
                <label><span>{{ item.label }} · 修改内容</span><textarea :value="merchantEdits[item.id] ?? item.replacement" :aria-label="`${item.label}修改内容`" maxlength="1000" rows="2" :placeholder="item.kind === 'brand' ? '输入你的品牌，留空不使用原品牌' : '输入替换文案，清空则不使用此项'" @input="merchantEdits = { ...merchantEdits, [item.id]: $event.target.value }" /></label>
              </article>
            </div>
            <p v-else class="merchant-empty">本记录未识别到可修改的品牌、对白或字幕。重新分析可按新版要求识别。</p>
            <p v-if="!audioReport" class="merchant-empty">{{ analysisAudioSummary(result) }}，当前没有可核对的原对白。</p>
          </section>
          <details class="analysis-reference"><summary>查看画面拆解与声音参考</summary>
          <template v-if="!showGeneration"><div class="section-caption table-caption"><span>逐镜头拍摄指南</span><small>{{ shots.length }} 个估计分镜</small></div>
          <section class="shot-guide" aria-label="逐镜头复刻步骤">
            <article v-for="(shot, index) in shots" :key="index" class="shot-guide-item">
              <div class="shot-guide-head"><h3>镜头 {{ index + 1 }}</h3><button type="button" :aria-label="`跳转到镜头 ${index + 1}`" @click="seek(shot.start)">{{ analysisTime(shot.start) }}–{{ analysisTime(shot.end) }} · {{ (shot.end - shot.start).toFixed(1) }} 秒</button></div>
              <p class="shot-observation"><strong>原视频画面</strong>{{ shot.scene }}</p>
              <dl><div v-for="detail in analysisShotDetails(shot, reportMode, recreationRoute)" :key="detail.label"><dt>{{ detail.label }}</dt><dd>{{ detail.text }}</dd></div></dl>
              <p class="shot-mood">画面情绪：{{ shot.emotion }} · 节奏：{{ shot.pacing }}</p>
            </article>
          </section></template>
          <p class="analysis-summary">{{ result.summary }}</p><p class="analysis-method">基于 {{ current.frames.length }} 张抽样画面；运镜与分镜边界为推断。{{ audioReport ? '已结合音频报告；声音时间戳为模型估计。' : analysisAudioSummary(result) }}</p>
          <p v-if="!result.recreation" class="analysis-legacy">这份历史报告保留了原有拆解，重新分析可获得更具体的逐镜操作步骤。</p>
          <p v-if="result.promptsLocalized" class="analysis-legacy">中文提示词已根据这份记录的画面拆解整理，可直接复制使用；重新分析可获得更完整的分镜提示词。</p>
          <section v-if="recreationSteps.length" class="recreation-plan" aria-label="复刻制作方案">
            <div class="section-caption"><span>{{ reportMode === 'ai' ? 'AI 复刻制作方案' : showGeneration ? '用 AI 重做的制作方案' : '实拍前的准备与做法' }}</span><small>按步骤执行</small></div>
            <article v-for="(step, index) in recreationSteps" :key="step.title"><span class="plan-number">{{ String(index + 1).padStart(2, '0') }}</span><div><h3>{{ step.title }}</h3><p>{{ step.text }}</p></div></article>
          </section>
          <template v-if="reportMode === 'ai'">
            <div class="insight-grid">
              <article class="insight-card radar-card"><div class="section-caption"><span>画面观察充分度</span><small>模型主观估计</small></div><div class="radar-visual"><svg viewBox="-8 -12 196 174" role="img" aria-label="五个画面维度的模型主观估计"><polygon points="90,14 157,63 132,138 48,138 23,63" fill="none" stroke="#f1eae8" /><polygon points="90,34 137,68 120,120 60,120 43,68" fill="none" stroke="#f5f0ee" /><polygon :points="radarPoints" fill="rgba(185,138,126,.18)" stroke="#c58372" stroke-width="2" /><text x="90" y="8" text-anchor="middle">光影</text><text x="176" y="65" text-anchor="end">运镜</text><text x="132" y="147" text-anchor="middle">主体</text><text x="25" y="147" text-anchor="middle">场景</text><text x="1" y="65" text-anchor="start">色彩</text></svg></div></article>
              <article class="insight-card keyframe-card"><div class="section-caption"><span>关键画面</span><small>{{ keyframes.length }} 个时刻</small></div><div class="keyframe-row"><article v-for="frame in keyframes" :key="frame.seconds" class="keyframe"><button type="button" class="frame-art" :aria-label="`跳转到 ${analysisTime(frame.seconds)}`" @click="seek(frame.seconds)"><img :src="frame.imageUrl" :alt="frame.title" loading="lazy"><b>{{ analysisTime(frame.seconds) }}</b></button><strong>{{ frame.title }}</strong><p>{{ frame.description || frame.title }}</p></article></div></article>
            </div>
          </template>
          <template v-if="reportMode === 'real'">
            <div class="metric-row"><article v-for="metric in realMetrics" :key="metric.label" class="metric-card"><span>{{ metric.label }}</span><strong>{{ metric.value }}</strong><small>{{ metric.delta }}</small></article></div>
            <article class="insight-card keyframe-card"><div class="section-caption"><span>关键画面参考</span><small>{{ keyframes.length }} 个时刻</small></div><div class="keyframe-row"><article v-for="frame in keyframes" :key="frame.seconds" class="keyframe"><button type="button" class="frame-art" :aria-label="`跳转到 ${analysisTime(frame.seconds)}`" @click="seek(frame.seconds)"><img :src="frame.imageUrl" :alt="frame.title" loading="lazy"><b>{{ analysisTime(frame.seconds) }}</b></button><strong>{{ frame.title }}</strong><p>{{ frame.description || frame.title }}</p></article></div></article>
          </template>
          <div class="section-caption parameter-caption"><span>画面特点与复刻重点</span><small>{{ parameters.length }} 项</small></div><div class="parameter-grid"><div v-for="param in parameters" :key="param.key" class="parameter-row"><span>{{ param.key }}</span><strong>{{ param.value }}</strong></div></div>
          <section v-if="audioReport" class="audio-report" aria-label="声音分析">
            <div class="section-caption"><span>声音分析</span><small>口播、配乐与音效</small></div>
            <p class="analysis-summary">{{ audioReport.summary }}</p>
            <div class="parameter-grid"><div v-for="factor in audioFactors" :key="factor.key" class="parameter-row"><span>{{ factor.key }}</span><strong>{{ factor.value }}</strong></div></div>
            <div class="audio-transcript-head"><strong>口播转录</strong><button v-if="audioTranscript" type="button" @click="copyText(audioTranscript, 'audio')">{{ copied === 'audio' ? '已复制' : '复制口播' }}</button></div>
            <div v-for="(segment, index) in audioReport.transcript" :key="index" class="audio-segment"><button type="button" :aria-label="`播放 ${analysisTime(segment.start)} 的口播`" @click="seek(segment.start)">{{ analysisTime(segment.start) }}–{{ analysisTime(segment.end) }}</button><p>{{ segment.text }}</p></div>
            <p v-if="!audioReport.transcript.length" class="audio-empty">未识别到可转录的人声。</p>
            <template v-if="audioReport.effects.length"><strong class="audio-effects-label">音效与环境声事件</strong><div v-for="(effect, index) in audioReport.effects" :key="index" class="audio-segment"><button type="button" :aria-label="`播放 ${analysisTime(effect.start)} 的声音事件`" @click="seek(effect.start)">{{ analysisTime(effect.start) }}–{{ analysisTime(effect.end) }}</button><p>{{ effect.description }}</p></div></template>
            <div v-if="audioReport.limitations.length" class="audio-limitations"><p v-for="(item, index) in audioReport.limitations" :key="index">{{ item }}</p></div>
          </section>
          <div class="insights-grid"><article class="insight-good"><h3>可复用亮点</h3><p v-for="(item, index) in result.highlights" :key="index">{{ item }}</p></article><article class="insight-warn"><h3>复刻建议</h3><p v-for="(item, index) in result.suggestions" :key="index">{{ item }}</p></article></div>
          <div v-if="result.limitations.length" class="analysis-limitations"><strong>观察限制</strong><p v-for="(item, index) in result.limitations" :key="index">{{ item }}</p></div>
          </details>
          <p class="report-export-hint">导出为排版好的中文报告，双击即可阅读，也可打印或保存为 PDF。{{ reportMode === 'real' ? '实拍报告同时包含两种复刻方案。' : '' }}</p>
          <div class="console-footer"><button v-if="showGeneration" type="button" class="premium-primary" @click="generateSame"><span class="material-symbols-outlined">auto_awesome</span>一键生成同款 <span>↗</span></button><button type="button" class="premium-secondary" @click="exportReport"><span class="material-symbols-outlined">download</span>导出拆解报告</button></div>
          <p v-if="showGeneration" class="report-export-hint">已修改的总提示词、分镜脚本和画面设置将自动填入视频创作页，上传自己的商品参考图后即可生成。</p>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.console-footer { flex-wrap: wrap; }
@media (max-width: 720px) { .console-footer .premium-secondary { width: 100%; min-width: 0; } }
.analysis-video { display: block; width: 100%; height: auto; min-height: 220px; max-height: 420px; object-fit: contain; border-radius: 10px; background: #171b1d; }
.analysis-error { padding: 12px; border: 1px solid var(--color-border-subtle); border-radius: 10px; color: var(--color-accent-text); font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.analysis-error button { display: block; margin-top: 8px; color: var(--color-success); background: transparent; border: 0; cursor: pointer; }
.analysis-state { display: flex; min-height: 380px; padding: 50px 20px; flex-direction: column; align-items: center; justify-content: center; text-align: center; color: var(--color-primary); }
.analysis-state > .material-symbols-outlined { font-size: 44px; color: var(--an-cinnabar); }
.analysis-state h3 { margin: 16px 0 8px; font-size: 18px; }
.analysis-state p, .analysis-state small { max-width: 380px; font-size: 12px; line-height: 1.8; }
.analysis-state progress { width: min(300px, 90%); margin: 18px 0; accent-color: var(--an-cinnabar); }
.analysis-summary { margin: 0 0 8px; font-size: 13px; line-height: 1.8; color: var(--color-primary); }
.analysis-method { margin: 0 0 20px; font-size: 11px; line-height: 1.7; color: var(--an-muted); }
.analysis-legacy { padding: 12px 14px; margin: 0 0 18px; border-radius: 8px; background: var(--color-bg-subtle); color: var(--color-text-muted); font-size: 12px; line-height: 1.8; }
.recreation-tabs { display: flex; gap: 8px; margin: 0 0 12px; }
.recreation-tabs button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 8px 12px; border: 1px solid var(--color-border-subtle); border-radius: 8px; background: var(--color-bg-surface); color: var(--color-text-muted); font: inherit; font-size: 12px; cursor: pointer; }
.recreation-tabs button.active { border-color: var(--color-success); color: var(--color-success); background: color-mix(in srgb, var(--color-success) 6%, var(--color-bg-surface)); }
.recreation-tabs .material-symbols-outlined { font-size: 17px; }
.recreation-plan { margin-bottom: 20px; padding: 16px; border: 1px solid var(--color-border-subtle); border-radius: 12px; }
.recreation-plan article { display: flex; align-items: baseline; gap: 12px; padding-top: 14px; }
.plan-number { flex-shrink: 0; font-size: 11px; color: var(--an-cinnabar); font-variant-numeric: tabular-nums; }
.recreation-plan h3 { margin: 0 0 5px; font-size: 13px; color: var(--color-primary); }
.recreation-plan p { margin: 0; font-size: 12px; line-height: 1.9; color: var(--color-primary); white-space: pre-line; overflow-wrap: anywhere; }
.readable-prompt { margin: 20px 0; padding: 16px; border: 1px solid var(--color-border-subtle); border-radius: 12px; background: var(--color-bg-surface); }
.readable-prompt p, .reuse-script p { white-space: pre-line; overflow-wrap: anywhere; }
.readable-prompt p { margin: 12px 0 0; color: var(--color-primary); font-size: 13px; line-height: 1.9; }
.readable-prompt .product-reference-guide { margin-top: 10px; color: var(--color-text-muted); font-size: 12px; }
.copyable-content { max-height: 260px; overflow-y: auto; }
.copyable-content:focus-visible { outline: 2px solid var(--an-cinnabar); outline-offset: 4px; }
.merchant-editor { margin-top: 24px; padding: 16px; border: 1px solid var(--color-border-subtle); border-radius: 12px; }
.merchant-editor > p { margin: 12px 0; color: var(--color-text-muted); font-size: 12px; line-height: 1.8; }
.merchant-editor .section-caption button { border: 0; background: transparent; color: var(--an-cinnabar); font: inherit; font-size: 12px; cursor: pointer; }
.merchant-edit-columns, .merchant-edit-row { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
.merchant-edit-columns { padding: 8px 0; color: var(--color-text-muted); font-size: 11px; }
.merchant-edit-row { padding: 16px 0; border-top: 1px solid var(--color-border-subtle); }
.merchant-original strong, .merchant-edit-row label > span { display: block; margin-bottom: 6px; font-size: 12px; color: var(--color-primary); }
.merchant-original small { color: var(--color-text-muted); font-size: 10px; }
.merchant-original p { margin: 8px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; color: var(--color-primary); font-size: 12px; line-height: 1.8; }
.merchant-edit-row textarea { width: 100%; min-height: 82px; padding: 10px; border: 1px solid var(--color-border-subtle); border-radius: 6px; background: var(--color-bg-surface); color: var(--color-primary); font: inherit; font-size: 12px; line-height: 1.8; resize: vertical; }
.merchant-edit-row textarea:focus-visible { outline: 2px solid var(--an-cinnabar); outline-offset: 2px; }
@media (max-width: 720px) { .merchant-edit-columns { display: none; } .merchant-edit-row { grid-template-columns: 1fr; gap: 12px; } }
.analysis-reference { margin-top: 24px; }
.analysis-reference > summary { padding: 12px 0; color: var(--color-text-muted); font-size: 12px; cursor: pointer; }
.shot-guide + .analysis-summary { margin-top: 24px; }
.readable-prompt details { margin-top: 14px; }
.readable-prompt summary { color: var(--color-text-muted); font-size: 12px; cursor: pointer; }
.real-console .keyframe-card { margin-top: 18px; }
.shot-guide { display: grid; gap: 16px; }
.shot-guide-item { padding: 16px; border: 1px solid var(--color-border-subtle); border-radius: 12px; }
.shot-guide-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.shot-guide-head h3 { margin: 0; color: var(--color-primary); font-size: 14px; }
.shot-guide-head button { padding: 4px 0; border: 0; background: transparent; color: var(--an-cinnabar); font-size: 11px; cursor: pointer; font-variant-numeric: tabular-nums; }
.shot-observation { margin: 14px 0; color: var(--color-primary); font-size: 13px; line-height: 1.9; }
.shot-observation strong { display: block; margin-bottom: 4px; font-size: 11px; color: var(--color-text-muted); font-weight: 500; }
.shot-guide dl { margin: 0; }
.shot-guide dl > div { display: grid; grid-template-columns: 130px minmax(0, 1fr); gap: 12px; padding: 10px 0; border-top: 1px solid var(--color-border-subtle); font-size: 12px; line-height: 1.9; }
.shot-guide dt { color: var(--color-text-muted); }
.shot-guide dd { margin: 0; white-space: pre-line; overflow-wrap: anywhere; color: var(--color-primary); }
.shot-mood { margin: 10px 0 0; font-size: 11px; line-height: 1.8; color: var(--color-text-muted); }
.first-frame-prompt { margin-top: 14px; }
.report-export-hint { margin: 20px 0 0; color: var(--color-text-muted); font-size: 11px; line-height: 1.8; }
@media (max-width: 720px) { .shot-guide dl > div { grid-template-columns: 1fr; gap: 2px; } .shot-guide-head { flex-wrap: wrap; gap: 4px; } .recreation-tabs button { flex: 1; } }
.audio-report { margin-top: 20px; padding: 16px; border: 1px solid var(--color-border-subtle); border-radius: 12px; }
.audio-transcript-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 18px 0 8px; font-size: 12px; }
.audio-transcript-head button, .audio-segment button { padding: 4px 6px; border: 0; background: transparent; color: var(--an-cinnabar); font-size: 11px; cursor: pointer; }
.audio-segment { display: flex; align-items: baseline; gap: 12px; padding: 8px 0; border-bottom: 1px solid var(--color-border-subtle); }
.audio-segment button { flex-shrink: 0; font-variant-numeric: tabular-nums; }
.audio-segment p { margin: 0; font-size: 12px; line-height: 1.8; overflow-wrap: anywhere; }
.audio-empty, .audio-limitations { font-size: 11px; line-height: 1.7; color: var(--an-muted); }
.audio-effects-label { display: block; margin-top: 16px; font-size: 12px; }
@media (max-width: 720px) { .audio-segment { flex-direction: column; gap: 2px; } }
.analysis-limitations { margin-top: 18px; padding: 14px; border-radius: 10px; background: var(--color-bg-subtle); font-size: 12px; line-height: 1.7; color: var(--color-primary); }
.analysis-limitations p { margin: 7px 0 0; }
.analysis-history-empty { padding: 10px; font-size: 12px; color: var(--color-primary); }
.live-analysis .frame-art { width: 100%; padding: 0; border: 0; cursor: pointer; }
.live-analysis .frame-art img { width: 100%; height: 100%; object-fit: cover; }
.live-analysis .compact-drop strong { max-width: 100%; overflow-wrap: anywhere; text-align: center; }
.live-analysis .metric-card strong { font-size: clamp(15px, 1.4vw, 23px); overflow-wrap: anywhere; }
.live-analysis .table-row { align-items: start; overflow-wrap: anywhere; }
.live-analysis .parameter-row strong { overflow-wrap: anywhere; }
.live-analysis button:disabled { cursor: not-allowed; opacity: .5; }
.live-analysis :is(button, [role='button'], input, textarea, summary):focus-visible { outline: 2px solid var(--an-cinnabar); outline-offset: 3px; }
@media (min-width: 721px) {
  /* Scroll the controls on short screens so the video keeps a usable size. */
  .live-analysis .premium-left { position: sticky; top: 0; max-height: calc(100dvh - 112px); overflow-y: auto; scrollbar-width: thin; overscroll-behavior: contain; gap: 8px; }
  .live-analysis .premium-panel { flex-shrink: 0; padding: 12px; }
  .live-analysis .panel-head, .live-analysis .type-panel .panel-head { margin-bottom: 8px; }
  .live-analysis .compact-tabs button, .live-analysis .pill-switch button { height: 28px; }
  .live-analysis .compact-drop { min-height: 72px; margin-top: 8px; }
  .live-analysis .compact-drop strong { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .live-analysis .need-field { position: relative; margin-top: 8px; }
  .live-analysis .need-field label { margin-bottom: 4px; }
  .live-analysis .need-field > div { position: absolute; top: 0; right: 0; margin-top: 0; }
  .live-analysis .need-field button { padding: 2px 5px; font-size: 10px; line-height: 1.5; }
  .live-analysis .need-field textarea { display: block; height: 56px; min-height: 56px; resize: none; }
  .live-analysis .preview-panel { flex: 0 0 auto; }
  .live-analysis .analyze-trigger, .live-analysis .privacy-note, .live-analysis .analysis-error { flex-shrink: 0; }
  .live-analysis .privacy-note { font-size: 10px; line-height: 1.5; }
}
@media (min-width: 721px) and (max-height: 740px) {
  .live-analysis .premium-panel { padding: 10px; }
  .live-analysis .compact-drop { display: grid; grid-template-columns: 24px minmax(0, 1fr); gap: 2px 8px; min-height: 54px; padding: 8px 10px; }
  .live-analysis .compact-drop > .material-symbols-outlined { grid-row: span 2; }
  .live-analysis .compact-drop strong, .live-analysis .compact-drop small { margin-top: 0; text-align: left; }
  .live-analysis .need-field textarea { height: 48px; min-height: 48px; }
  .live-analysis .skeleton-center > .material-symbols-outlined { font-size: 24px; }
  .live-analysis .skeleton-center p { margin: 4px 0 0; }
  .live-analysis .skeleton-center small { display: none; }
}
@media (min-width: 721px) and (max-height: 660px) {
  .live-analysis .type-panel > .panel-head { display: none; }
}
@media (max-width: 700px) { .live-analysis .deep-table { overflow-x: auto; } .live-analysis .table-row, .live-analysis .table-head { min-width: 650px; } }
</style>
