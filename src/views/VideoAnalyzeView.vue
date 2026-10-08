<script setup>
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { videoAnalysisApi, uploadAnalysisVideo } from '../services/videoAnalysis'
import { VIDEO_ANALYSIS_LIMITS, analysisTerminal, analysisTime, analysisAudioSummary, nearestAnalysisFrame, validateAnalysisFile } from '../domain/videoAnalysis'

const router = useRouter()
const route = useRoute()
const inputMode = ref('upload')
const videoType = ref('ai')
const videoUrl = ref('')
const reverseNeed = ref('')
const fileInput = ref(null)
const player = ref(null)
const selectedFile = shallowRef(null)
const selectedName = ref('')
const previewUrl = ref('')
const previewDuration = ref(0)
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
let objectUrl = ''
let pollTimer
let copyTimer
let pollGeneration = 0
let disposed = false
let submissionKey = ''

const isScanning = computed(() => uploading.value || (current.value && !analysisTerminal(current.value.status)))
const result = computed(() => current.value?.status === 'SUCCEEDED' ? current.value.result : null)
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
    { key: '负面提示词建议', value: result.value.negativePrompt }, { key: '声音内容', value: analysisAudioSummary(result.value) }]
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
  { label: '视频时长', value: `${duration.value.toFixed(1)}s`, delta: '实际媒体信息' },
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
function generateSame() { router.push({ path: '/video/workbench', query: { prompt: promptText.value } }) }
function seek(seconds) { if (player.value) player.value.currentTime = seconds }
function exportReport() {
  const report = { name: current.value.name, durationSeconds: duration.value, width: current.value.width,
    height: current.value.height, frameCount: current.value.frames.length, ...result.value }
  const url = URL.createObjectURL(new Blob([JSON.stringify(report, null, 2)], { type: 'application/json;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${current.value.name.replace(/[\\/:*?"<>|]/g, '_')}-拆解报告.json`
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

watch(() => route.query.history, id => { if (id && String(current.value?.id) !== String(id)) loadHistory(id) })
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
          <div class="need-field">
            <label for="reverse-need">反推需求 <em>可选</em></label><textarea id="reverse-need" v-model="reverseNeed" :disabled="isScanning" maxlength="2000" placeholder="例如：侧重于运镜、口播脚本或配乐节奏..."></textarea>
            <div><button type="button" :disabled="isScanning" @click="reverseNeed = '侧重于运镜和镜头节奏分析'">运镜节奏</button><button type="button" :disabled="isScanning" @click="reverseNeed = '侧重于可见字幕和复刻脚本建议'">字幕脚本</button></div>
          </div>
        </section>
        <section class="premium-panel preview-panel">
          <div class="panel-head"><div><span class="material-symbols-outlined panel-icon">play_circle</span><strong>视频预览</strong></div><small>{{ duration ? analysisTime(duration) : '等待输入' }}</small></div>
          <video v-if="previewUrl" ref="player" class="analysis-video" :src="previewUrl" controls playsinline preload="metadata" @loadedmetadata="onMetadata" />
          <div v-else class="skeleton-player" :class="{ scanning: isScanning }"><div v-if="isScanning" class="scan-beam" /><div class="skeleton-grid" /><div class="skeleton-center"><span class="material-symbols-outlined">movie</span><p>{{ statusLabel }}</p><small>选择视频后点击开始分析</small></div></div>
        </section>
        <button type="button" class="analyze-trigger" :disabled="!canStart" @click="startScan"><span class="material-symbols-outlined">auto_awesome</span>{{ isScanning ? statusLabel : current ? '重新分析' : '开始分析' }} <span>↗</span></button>
        <p class="privacy-note"><span class="material-symbols-outlined">lock</span>原视频保留供回看，临时文件自动清理</p>
        <div v-if="error" class="analysis-error" role="alert">{{ error }}<button v-if="pollingInterrupted" type="button" @click="error = ''; poll(current.id)">重新连接</button></div>
      </aside>

      <section class="premium-console" :aria-busy="Boolean(isScanning || loadingHistory)">
        <div class="console-head">
          <div><p class="premium-overline">分析工作区</p><h2>{{ videoType === 'ai' ? 'AI 视频提示词' : '实拍视频拆解' }}</h2></div>
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
        <div v-else class="console-body" :class="{ 'real-console': videoType === 'real' }">
          <p class="analysis-summary">{{ result.summary }}</p><p class="analysis-method">基于 {{ current.frames.length }} 张抽样画面；运镜与分镜边界为推断。{{ audioReport ? '已结合音频报告；声音时间戳为模型估计。' : analysisAudioSummary(result) }}</p>
          <template v-if="videoType === 'ai'">
            <div class="insight-grid">
              <article class="insight-card radar-card"><div class="section-caption"><span>画面观察充分度</span><small>模型主观估计</small></div><div class="radar-visual"><svg viewBox="-8 -12 196 174" role="img" aria-label="五个画面维度的模型主观估计"><polygon points="90,14 157,63 132,138 48,138 23,63" fill="none" stroke="#f1eae8" /><polygon points="90,34 137,68 120,120 60,120 43,68" fill="none" stroke="#f5f0ee" /><polygon :points="radarPoints" fill="rgba(185,138,126,.18)" stroke="#c58372" stroke-width="2" /><text x="90" y="8" text-anchor="middle">光影</text><text x="176" y="65" text-anchor="end">运镜</text><text x="132" y="147" text-anchor="middle">主体</text><text x="25" y="147" text-anchor="middle">场景</text><text x="1" y="65" text-anchor="start">色彩</text></svg></div></article>
              <article class="insight-card keyframe-card"><div class="section-caption"><span>关键画面</span><small>{{ keyframes.length }} 个时刻</small></div><div class="keyframe-row"><article v-for="frame in keyframes" :key="frame.seconds" class="keyframe"><button type="button" class="frame-art" :aria-label="`跳转到 ${analysisTime(frame.seconds)}`" @click="seek(frame.seconds)"><img :src="frame.imageUrl" :alt="frame.title" loading="lazy"><b>{{ analysisTime(frame.seconds) }}</b></button><strong>{{ frame.title }}</strong><p>{{ frame.prompt }}</p></article></div></article>
            </div>
            <div class="section-caption prompt-caption"><span>复刻提示词</span><small>用于生成类似画面</small></div><div class="code-editor typing-editor"><div class="editor-bar"><span><i /><i /><i /></span><small>reverse-engineering.prompt</small><button type="button" @click="copyText(promptText, 'prompt')">{{ copied === 'prompt' ? '已复制' : '复制提示词' }}</button></div><pre><code><span class="syntax-cinnabar">{{ promptText }}</span></code></pre></div>
            <div class="section-caption parameter-caption"><span>提取参数</span><small>{{ parameters.length }} 项因素</small></div><div class="parameter-grid"><div v-for="param in parameters" :key="param.key" class="parameter-row"><span>{{ param.key }}</span><strong class="tone-teal">{{ param.value }}</strong></div></div>
          </template>
          <template v-else>
            <section class="reuse-script"><div class="reuse-script-head"><span>复刻建议脚本</span><small>可交给编导参考</small><button type="button" @click="copyText(result.reuseScript, 'script')">{{ copied === 'script' ? '已复制' : '复制脚本' }}</button></div><p>{{ result.reuseScript }}</p></section>
            <div class="metric-row"><article v-for="metric in realMetrics" :key="metric.label" class="metric-card"><span>{{ metric.label }}</span><strong>{{ metric.value }}</strong><small>{{ metric.delta }}</small></article></div>
          </template>
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
          <div class="section-caption table-caption"><span>镜头拆解</span><small>{{ shots.length }} 个估计分镜</small></div><div class="deep-table"><div class="table-head"><span>时间</span><span>场景与动作</span><span>运镜推断</span><span>画面情绪</span><span>节奏</span></div><div v-for="(shot, index) in shots" :key="index" class="table-row"><span class="mono-cell">{{ analysisTime(shot.start) }}–{{ analysisTime(shot.end) }}</span><span>{{ shot.scene }}</span><span><b class="camera-badge">{{ shot.camera }}</b></span><span class="emotion-cell">{{ shot.emotion }}</span><span>{{ shot.pacing }}</span></div></div>
          <div class="insights-grid"><article class="insight-good"><h3>可复用亮点</h3><p v-for="(item, index) in result.highlights" :key="index">{{ item }}</p></article><article class="insight-warn"><h3>复刻建议</h3><p v-for="(item, index) in result.suggestions" :key="index">{{ item }}</p></article></div>
          <div v-if="result.limitations.length" class="analysis-limitations"><strong>观察限制</strong><p v-for="(item, index) in result.limitations" :key="index">{{ item }}</p></div>
          <div class="console-footer"><button v-if="videoType === 'ai'" type="button" class="premium-primary" @click="generateSame"><span class="material-symbols-outlined">auto_awesome</span>用此提示词创作视频 <span>↗</span></button><button type="button" class="premium-secondary" @click="exportReport"><span class="material-symbols-outlined">download</span>导出拆解报告</button></div>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.console-footer { flex-wrap: wrap; }
@media (max-width: 720px) { .console-footer .premium-secondary { width: 100%; min-width: 0; } }
.analysis-video { display: block; width: 100%; max-height: 260px; border-radius: 10px; background: #171b1d; }
.analysis-error { padding: 12px; border: 1px solid var(--color-border-subtle); border-radius: 10px; color: var(--color-accent-text); font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.analysis-error button { display: block; margin-top: 8px; color: var(--color-success); background: transparent; border: 0; cursor: pointer; }
.analysis-state { display: flex; min-height: 380px; padding: 50px 20px; flex-direction: column; align-items: center; justify-content: center; text-align: center; color: var(--color-primary); }
.analysis-state > .material-symbols-outlined { font-size: 44px; color: var(--an-cinnabar); }
.analysis-state h3 { margin: 16px 0 8px; font-size: 18px; }
.analysis-state p, .analysis-state small { max-width: 380px; font-size: 12px; line-height: 1.8; }
.analysis-state progress { width: min(300px, 90%); margin: 18px 0; accent-color: var(--an-cinnabar); }
.analysis-summary { margin: 0 0 8px; font-size: 13px; line-height: 1.8; color: var(--color-primary); }
.analysis-method { margin: 0 0 20px; font-size: 11px; line-height: 1.7; color: var(--an-muted); }
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
.live-analysis :is(button, [role='button'], input, textarea):focus-visible { outline: 2px solid var(--an-cinnabar); outline-offset: 3px; }
@media (min-width: 721px) {
  /* Fit between the 56px app bar and the shell's 28px top/bottom padding. */
  .live-analysis .premium-left { position: sticky; top: 0; height: calc(100dvh - 112px); gap: 8px; }
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
  .live-analysis .preview-panel { display: flex; flex: 1 1 0; flex-direction: column; min-height: 0; }
  .live-analysis .preview-panel .panel-head, .live-analysis .analyze-trigger { flex-shrink: 0; }
  .live-analysis .analysis-video, .live-analysis .skeleton-player { flex: 1; height: 0; min-height: 0; max-height: none; object-fit: contain; }
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
