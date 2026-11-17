<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ArrowUp, AudioLines, Check, Download, Film, ImagePlus, Minus, Paperclip, Plus, RefreshCw, SlidersHorizontal, Sparkles, X } from 'lucide-vue-next'
import YanhuoIcon from '../components/YanhuoIcon.vue'
import { useImageCreation } from '../composables/useImageCreation'
import { useVideoWorkbench, videoRatioOptions, videoStageLabel } from '../composables/useVideoWorkbench'
import { useImageInspiration } from '../composables/useImageInspiration'
import { useVideoPlan } from '../composables/useVideoPlan'
import { videoPlanLetters, videoPlanSeconds, videoPlanStyles } from '../services/videoPlans'
import { IMAGE_QUALITIES, IMAGE_RATIOS, IMAGE_STATUS, imageDimensions, imageStatusLabel, isImageActive } from '../domain/imageCreation'
import { POSTER_STYLES, SEASONAL_PREFIX, upcomingMoment } from '../domain/posterStyles'
import { FREE_SCENE, videoSceneCover, videoSceneName } from '../services/videoScenes'
import { isVideoInProgress, videoModelLabel } from '../services/videoConversations'
import { billing, dateTime, number } from '../stores/billing'
import { NOVICE_RECENTS_CHANGED } from '../stores/uiMode'
import { auth } from '../stores/auth'
import { stableUrl } from '../utils/stableUrl'
import '../novice-studio.css'

// 新手版对话页：灵感绘图和视频创作原样搬进一个输入框，功能、计费和接口都不变，只换外壳。
// 图片走 useImageCreation('POSTER')，视频走 useVideoWorkbench()，和进阶版共用同一套逻辑。
// 同一个对话里随时可以在图片和视频之间切换，每条消息下面只标这次的设置，不标「图片/视频」。
const MAX_IMAGES = 6
const route = useRoute()
const router = useRouter()
const mode = ref(route.query.mode === 'video' ? 'video' : 'image')
const panel = ref('')
const imageInput = ref(null)
const videoInputs = { image: ref(null), video: ref(null), audio: ref(null) }
const threadEnd = ref(null)
const dock = ref(null)
const composerInput = ref(null)

// 问候按时段轮换，每分钟看一次时间；名字取账号昵称，没有就只问候。
const GREETINGS = [
  { until: 5, title: '夜深了', lines: ['还在忙店里的事？做完这一张早点休息。', '安静的时候，最适合想想下周要发什么。'] },
  { until: 8, title: '早上好', lines: ['新的一天，先给店里做张开门图？', '早起的这一会儿，把今天要发的先备好。'] },
  { until: 11, title: '上午好', lines: ['趁上午人少，把这周要发的图先做出来。', '说一句想推什么，剩下的交给一方志。'] },
  { until: 13, title: '中午好', lines: ['午市正忙，一句话交给一方志就行。', '忙完这一阵，回来看看出好的图。'] },
  { until: 17, title: '下午好', lines: ['下午茶的时间，适合做一组新品图。', '把今天的好看瞬间，做成一条短视频。'] },
  { until: 19, title: '傍晚好', lines: ['晚市之前，发条短视频热热场？', '天色正好，给门店拍组氛围图吧。'] },
  { until: 23, title: '晚上好', lines: ['收工前，把明天要发的内容准备好。', '今天的店里故事，值得留一张图。'] },
  { until: 24, title: '夜深了', lines: ['还在忙店里的事？做完这一张早点休息。', '安静的时候，最适合想想下周要发什么。'] },
]
const now = ref(new Date())
const clock = setInterval(() => { now.value = new Date() }, 60000)
onBeforeUnmount(() => clearInterval(clock))
const greeting = computed(() => {
  const period = GREETINGS.find(entry => now.value.getHours() < entry.until)
  const name = auth.user?.name && !/^用户\d{4}$/.test(auth.user.name) ? auth.user.name : ''
  return { title: name ? `${period.title}，${name}` : period.title, line: period.lines[now.value.getDate() % period.lines.length] }
})

const announce = () => window.dispatchEvent(new Event(NOVICE_RECENTS_CHANGED))
const studio = useImageCreation('POSTER')
const video = reactive(useVideoWorkbench({
  // 确认报价、开始生成后清空输入框，下一句话就是新的一条。
  onSubmitted: () => {
    video.prompt = ''
    video.referenceImages = []
    video.referenceVideos = []
    video.referenceAudios = []
    announce()
    scrollToEnd()
  },
}))

// —— 灵感方案：和进阶版共用 useImageInspiration / useVideoPlan，只是在对话里一问一答 ——
const insp = reactive(useImageInspiration({ studio }))
const vplan = reactive(useVideoPlan(reactive({
  images: computed(() => video.referenceImages),
  video: computed(() => video.referenceVideos[0] || null),
  upload: (...args) => video.uploadAsset(...args),
  maxDuration: computed(() => video.durationMax),
  duration: computed(() => video.duration),
  ratio: computed(() => video.format),
  brief: computed(() => video.prompt),
  ratios: videoRatioOptions.map(option => option.value),
  locked: computed(() => video.isGenerating || Boolean(video.activeConversation?.loading)),
  scene: computed(() => video.sceneKey),
  scenes: computed(() => video.scenes),
}), (event, ...args) => {
  if (event === 'apply') video.applyPlan(...args)
  else if (event === 'scene') video.sceneKey = args[0]
}))

// —— 对话里的每一轮：图片按一组版本（第一版的 ID）记，视频按创作记录记，灵感方案一问一答记一轮 ——
const turns = ref([])
const dismissedImages = new Set()
watch(() => studio.thread, thread => {
  const rootId = thread?.[0]?.id
  if (!rootId || dismissedImages.has(rootId)) return
  const turn = turns.value.find(entry => entry.kind === 'image' && entry.rootId === rootId)
  if (turn) {
    // 出图、加了新版本时滚到底；轮询中状态没变就不动，免得打断往上翻看。
    const before = `${turn.versions.length}:${turn.versions.at(-1)?.status}`
    turn.versions = thread
    if (before !== `${thread.length}:${thread.at(-1)?.status}`) scrollToEnd()
    return
  }
  turns.value.push({ key: `image:${rootId}`, kind: 'image', rootId, versions: thread })
  announce()
  scrollToEnd()
})
watch(() => {
  const item = video.activeConversation
  return item && (item.submitting || item.workflow) ? item.id : null
}, id => {
  if (!id || turns.value.some(entry => entry.kind === 'video' && entry.convId === id)) return
  turns.value.push({ key: `video:${id}`, kind: 'video', convId: id })
  scrollToEnd()
})
// 报价出来、视频出片时滚到底，确认按钮和成片不会被输入框挡住。
watch(() => [Boolean(video.quoted), video.outputUrl], ([quoted, output]) => { if (quoted || output) scrollToEnd() })
const conversationOf = turn => video.conversations.find(item => item.id === turn.convId)
const started = computed(() => turns.value.length > 0 || studio.optimistic)

async function scrollToEnd() {
  await nextTick()
  const end = threadEnd.value
  if (!end?.scrollIntoView) return
  // 输入框吸在底部，滚动时让出它的高度，最后一条不会被挡住。
  end.style.scrollMarginBottom = `${dock.value?.offsetHeight || 0}px`
  end.scrollIntoView({ behavior: 'smooth', block: 'end' })
}

// —— 新对话、从侧栏「最近」打开 ——
function resetConversation() {
  for (const turn of turns.value) if (turn.kind === 'image') dismissedImages.add(turn.rootId)
  turns.value = []
  panel.value = ''
  ideaMode.value = false
  studio.resetDraft()
  studio.count = 1
  video.startNewConversation()
}
async function openFromQuery() {
  if (route.query.new) {
    resetConversation()
    router.replace({ name: 'studio' })
    return
  }
  const open = typeof route.query.open === 'string' ? route.query.open : ''
  const [kind, id] = open.split(':')
  if (!id) return
  resetConversation()
  dismissedImages.clear()
  if (kind === 'image') {
    mode.value = 'image'
    await studio.openCreation(id)
  } else if (kind === 'video') {
    mode.value = 'video'
    await video.openWorkflow(id)
    router.replace({ name: 'studio', query: { mode: 'video' } })
  }
}
watch(() => [route.query.open, route.query.new], openFromQuery)
onMounted(openFromQuery)

// —— 图片：风格、比例画质、张数、参考图 ——
const moment = upcomingMoment()
const seasonalStyle = SEASONAL_PREFIX + moment.name
const styleTiles = [{ name: '帮我搭配', image: '/images/brand/yifangzhi-mark.png', fit: '按画面需求自动匹配', mark: true }, ...POSTER_STYLES]
const currentStyle = computed(() => studio.style === seasonalStyle
  ? { name: `应时 · ${moment.name}`, image: '', fit: '' }
  : styleTiles.find(style => style.name === studio.style) || styleTiles[0])
const serviceNotice = computed(() => studio.requiresLogin ? '登录后开始创作'
  : studio.capabilityState === 'checking' ? '正在检查创作服务…'
  : studio.capabilityState === 'error' ? `创作服务检查失败：${studio.capabilityError}`
  : !studio.configured ? '图片创作服务暂未接通' : '')
function changeCount(step) { studio.count = Math.min(MAX_IMAGES, Math.max(1, Number(studio.count) + step)) }

// —— 视频：场景、输出设置、素材 ——
const videoModels = computed(() => (video.capabilities.length ? video.capabilities : video.fallbackCapabilities))
const sceneTiles = computed(() => video.scenes.map(scene => ({ ...scene, cover: videoSceneCover(scene.key) })))
const activeVideoDraft = computed(() => !turns.value.some(entry => entry.kind === 'video' && entry.convId === video.activeConversationId))
function pickVideoFiles(type) { panel.value = ''; videoInputs[type].value?.click() }

// —— 输入框 ——
const placeholder = computed(() => ideaMode.value
  ? (answering.value ? `回答：${vplan.clarification.question}` : mode.value === 'image' ? '一句话说说想要什么，比如：新品香水上新，发小红书，安静高级一点' : '一句话说说想要什么，比如：新品香水上新，发抖音，安静高级一点')
  : mode.value === 'image'
  ? '描述你想要的画面，比如光线、场景、氛围……'
  : (video.currentScene?.placeholder || '描述你的成片需求，比如拍什么、什么节奏、发在哪……'))
const ideas = computed(() => mode.value === 'image'
  ? ['新品手冲咖啡上市，做一张小红书封面，清晨窗边的光', '周年店庆海报，国风意境，红金配色，不要文字', '一杯威士忌放在深色石台上，一束侧光打下来']
  : ['新品香水上新，发抖音，安静高级一点', '带大家逛逛我们店，从门口走到吧台，暖色灯光', '周末活动预告，节奏轻快，结尾停在招牌上'])
// —— 灵感方案模式：输入框改成「一句话说说想要什么」，发出去先出 3 个方向，挑一个再生成 ——
const ideaMode = ref(false)
const planEngineOf = kind => (kind === 'image' ? insp : vplan)
const planEngine = computed(() => planEngineOf(mode.value))
// 视频方案有时会先问一句（比如活动时间），这时输入框里写的是回答。
const answering = computed(() => ideaMode.value && mode.value === 'video' && Boolean(vplan.clarification))
const planNote = ref('')
function toggleIdeas() {
  ideaMode.value = !ideaMode.value
  panel.value = ''
  planNote.value = ''
  nextTick(() => composerInput.value?.focus?.())
}
const draft = computed({
  get: () => (ideaMode.value ? (answering.value ? vplan.answer : planEngine.value.idea) : mode.value === 'image' ? studio.brief : video.prompt),
  set: value => {
    if (ideaMode.value) { if (answering.value) vplan.answer = value; else planEngine.value.idea = value }
    else if (mode.value === 'image') studio.brief = value
    else video.prompt = value
  },
})
const planContext = engine => ({ record: engine.record, references: [...engine.resultReferences], info: { ...engine.resultInfo }, resultId: engine.result?.id })
const planTurn = (kind, engine) => ({
  key: `plan:${kind}:${engine.result.id}:${Date.now()}`, kind: 'plan', mode: kind, status: 'done', applied: -1,
  idea: engine.resultInfo?.idea || '按参考图出方案', style: engine.resultInfo?.style || '',
  scene: kind === 'video' ? videoSceneName(video.scenes, engine.result.sceneKey) : '',
  refs: [...engine.resultImages], images: [...engine.resultImages], batch: engine.result, context: planContext(engine),
})
let ownPlanRun = false
async function sendIdea(previousId = null) {
  const kind = mode.value
  const engine = planEngineOf(kind)
  if (engine.working) return
  const isAnswer = answering.value
  const text = (isAnswer ? vplan.answer : engine.idea).trim()
  const refs = kind === 'image' ? studio.files.map(file => file.preview) : video.referenceImages.map(entry => entry.url)
  const brief = (kind === 'image' ? studio.brief : video.prompt)?.trim()
  planNote.value = ''
  if (!previousId && !text && !refs.length && !brief) { planNote.value = isAnswer ? '先回答上面的问题。' : '先说一句想要什么，或者加一张参考图。'; return }
  const index = turns.value.push({
    key: `plan:${kind}:${Date.now()}`, kind: 'plan', mode: kind, status: 'thinking', applied: -1, answer: isAnswer,
    idea: previousId ? '换一批' : text || brief || '按参考图出方案',
    style: kind === 'image' ? insp.styleName : vplan.styleText,
    scene: kind === 'video' ? videoSceneName(video.scenes, video.sceneKey) : '',
    refs: previousId ? [] : refs,
  }) - 1
  const turn = turns.value[index]
  scrollToEnd()
  const before = engine.result?.id
  ownPlanRun = true
  try { await engine.run(previousId) } finally { ownPlanRun = false }
  if (engine.result && engine.result.id !== before) {
    Object.assign(turn, { status: 'done', batch: engine.result, images: [...engine.resultImages], context: planContext(engine) })
    engine.idea = ''
    if (kind === 'video') vplan.answer = ''
  } else if (kind === 'video' && vplan.clarification) {
    Object.assign(turn, { status: 'question', question: vplan.clarification.question })
  } else {
    Object.assign(turn, { status: 'failed', error: engine.error || '这次没有出成方案，烟火已退回，可以再试一次。' })
  }
  scrollToEnd()
}
// 离开页面时还在出的方案、从「之前的方案」打开的记录，出现在对话里。
for (const kind of ['image', 'video']) {
  watch(() => planEngineOf(kind).result?.id, id => {
    if (!id || ownPlanRun || turns.value.some(entry => entry.kind === 'plan' && entry.batch?.id === id)) return
    turns.value.push(planTurn(kind, planEngineOf(kind)))
    scrollToEnd()
  })
}
function openPlanRecord(item) {
  planEngine.value.openRecord(item)
  ideaMode.value = true
}
function usePlan(turn, plan, index) {
  const engine = planEngineOf(turn.mode)
  if (mode.value !== turn.mode) mode.value = turn.mode
  if (!engine.use(plan, index, turn.context)) { turn.useError = engine.error; return }
  Object.assign(turn, { applied: index, useError: '' })
  ideaMode.value = false
  panel.value = ''
  nextTick(() => composerInput.value?.focus?.())
}
function morePlans(turn) {
  mode.value = turn.mode
  ideaMode.value = true
  planEngineOf(turn.mode).idea = turn.context?.info?.idea || ''
  sendIdea(turn.batch.id)
}
const planRecords = computed(() => planEngine.value.records.slice(0, 3))
const sendDisabled = computed(() => (ideaMode.value ? planEngine.value.working || Boolean(planEngine.value.unavailable)
  : mode.value === 'image'
    ? studio.busy || (!studio.configured && !studio.requiresLogin) || studio.active || !!studio.pending
    : video.isGenerating || Boolean(video.activeConversation?.loading)))
const sendLabel = computed(() => (ideaMode.value ? (planEngine.value.working ? '正在出方案' : answering.value ? '回答并出方案' : '出方案')
  : mode.value === 'image'
    ? (studio.busy ? '正在提交' : studio.saving ? '正在保存原图' : studio.active ? '正在创作' : '生成图片')
    : (video.isGenerating ? '正在生成视频' : '预估费用')))
const sendBusy = computed(() => (ideaMode.value ? planEngine.value.working : mode.value === 'image' ? studio.busy || studio.active : video.isGenerating))
// 图片按张计费、方案按次计费，按钮旁直接写这次要花多少烟火；视频要先报价。
const imageCost = computed(() => (ideaMode.value ? planEngine.value.credits : mode.value === 'image' ? studio.estimatedCredits : null))
const sendTitle = computed(() => (ideaMode.value ? '每次出 3 个方案 · 换一批同价 · 失败不扣烟火'
  : mode.value === 'image'
    ? `${studio.imageCredits != null ? `每张 ${studio.imageCredits} 烟火，` : ''}成功交付后扣费`
    : '先预估烟火，确认后才开始生成'))
function togglePanel(name) { panel.value = panel.value === name ? '' : name }
function switchMode(next) {
  if (mode.value === next) return
  mode.value = next
  panel.value = ''
}
function send() {
  panel.value = ''
  if (ideaMode.value) { sendIdea(); return }
  if (mode.value === 'image') studio.generate()
  else { if (!video.format) panel.value = 'output'; video.generate() }
  scrollToEnd()
}
function onKeydown(event) {
  if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) { event.preventDefault(); if (!sendDisabled.value) send() }
}

// —— 对话里的消息：素材、设置标签、长文折叠 ——
const ROLE_LABELS = { SUBJECT: '主体', STYLE: '风格', LAYOUT: '构图', BACKGROUND: '背景' }
const expanded = ref(new Set())
const isLong = text => (text || '').length > 120 || (text || '').split('\n').length > 3
function toggleExpanded(key) {
  const next = new Set(expanded.value)
  next[next.has(key) ? 'delete' : 'add'](key)
  expanded.value = next
}
// 对话里显示小图（长边 640），点开看原图；更早的图没有小图，加载失败就退回原图。
// 所有签名链接都经过 stableUrl，同一个文件在有效期内用同一个地址，浏览器才能缓存。
const missingThumbs = ref(new Set())
const imageSrc = item => stableUrl(item.thumbUrl && !missingThumbs.value.has(item.id) ? item.thumbUrl : item.url)
function onImageError(item) {
  if (item.thumbUrl && !missingThumbs.value.has(item.id)) { missingThumbs.value = new Set(missingThumbs.value).add(item.id); return }
  studio.imageLoadFailed(item)
}
const revisingRoot = ref(null)
const retryable = item => ['FAILED', 'INTERRUPTED', 'UPSTREAM_UNKNOWN'].includes(item.status)
const isLiveImage = turn => turn.versions.at(-1)?.id === studio.current?.id
const turnPrompt = version => (!version.parentId ? version.brief || '这次绘图创作' : version.brief?.split('\n本次修改：').at(-1) || '继续修改图片')
const imageTags = (version, index) => [version.ratio, version.quality, version.count > 1 ? `${version.count} 张` : '', index ? `第 ${index + 1} 版` : ''].filter(Boolean)
// 修改出的新版本沿用第一版的参考图，只在第一条消息上展示。
const imageRefs = (version, index) => (index ? [] : version.references || [])
const imageGridClass = count => (count > 1 ? (count > 4 ? 'is-many' : 'is-few') : 'is-single')
function startRevision(turn) { revisingRoot.value = turn.rootId; studio.revision = '' }
function submitRevision() {
  if (!studio.revision.trim()) return
  studio.revise()
  revisingRoot.value = null
  scrollToEnd()
}
function videoTags(item) {
  const value = item.workflow?.ratio || item.format
  const ratio = value === 'auto' ? '自适应' : value
  return [
    videoSceneName(video.scenes, item.sceneKey),
    videoModelLabel(item.workflow?.model || item.selectedModel),
    ratio,
    `${item.workflow?.durationSeconds || item.duration} 秒`,
    item.workflow?.resolution || item.resolution,
  ].filter(Boolean)
}
</script>

<template>
  <div class="ns-page" :class="{ 'is-started': started }">
    <section v-if="!started" class="ns-hero">
      <Transition name="ns-greet" mode="out-in" appear>
        <div :key="greeting.title" class="ns-greeting">
          <h1>{{ greeting.title }}</h1>
          <p>{{ greeting.line }}</p>
        </div>
      </Transition>
    </section>

    <div v-else class="ns-thread" aria-live="polite">
      <template v-for="turn in turns" :key="turn.key">
        <template v-if="turn.kind === 'image'">
          <template v-for="(version, index) in turn.versions" :key="version.id">
            <div class="ns-user">
              <div v-if="imageRefs(version, index).length" class="ns-user-media">
                <figure v-for="reference in imageRefs(version, index)" :key="reference.assetId" class="ns-media-thumb">
                  <img v-if="reference.url" :src="stableUrl(reference.url)" :alt="reference.name" loading="lazy" />
                  <span v-else class="ns-media-missing">已删除</span>
                  <figcaption>{{ ROLE_LABELS[reference.role] || '参考' }}</figcaption>
                </figure>
              </div>
              <div class="ns-bubble" :class="{ 'is-clamped': isLong(turnPrompt(version)) && !expanded.has(version.id) }">
                <p>{{ turnPrompt(version) }}</p>
                <button v-if="isLong(turnPrompt(version))" type="button" class="ns-more" @click="toggleExpanded(version.id)">{{ expanded.has(version.id) ? '收起' : '展开全部' }}</button>
              </div>
              <ul class="ns-tags" aria-label="这次的设置"><li v-for="tag in imageTags(version, index)" :key="tag">{{ tag }}</li></ul>
            </div>
            <div class="ns-reply">
              <div class="ns-reply-head"><img src="/images/brand/yifangzhi-mark.png" alt="" /><strong>一方志</strong></div>
              <p v-if="version.status !== 'SUCCEEDED'" class="ns-status" role="status"><i :class="{ live: isImageActive(version.status) }" />{{ imageStatusLabel(version) }}<template v-if="version.count > 1 && version.completed"> · {{ version.completed }}/{{ version.count }}</template></p>
              <div class="ns-images" :class="imageGridClass(version.count)" :style="{ '--ns-ratio': imageDimensions(version.quality, version.ratio).width + '/' + imageDimensions(version.quality, version.ratio).height }">
                <template v-if="!version.items.length && (isImageActive(version.status) || version.status === 'NEEDS_INPUT')">
                  <div v-for="n in version.count" :key="n" class="ns-image is-placeholder" :class="{ 'is-working': version.status !== 'NEEDS_INPUT' }"><template v-if="version.status !== 'NEEDS_INPUT'"><span class="ns-brew" aria-hidden="true"><img src="/images/brand/yifangzhi-mark.png" alt="" /></span></template><small>{{ version.status === 'NEEDS_INPUT' ? '等你补充信息' : '一方志正在画' }}</small></div>
                </template>
                <figure v-for="item in version.items" :key="item.id" class="ns-image-item">
                  <a v-if="item.url" :href="stableUrl(item.url)" target="_blank" rel="noopener" class="ns-image" :style="{ aspectRatio: item.width + '/' + item.height }" aria-label="在新窗口查看原图">
                    <img :src="imageSrc(item)" alt="生成的图片" decoding="async" @load="studio.reportImageVisible(item)" @error="onImageError(item)" />
                  </a>
                  <div v-else class="ns-image is-placeholder" :class="{ 'is-working': isImageActive(item.status) }" :style="{ aspectRatio: item.width + '/' + item.height }"><template v-if="isImageActive(item.status)"><span class="ns-brew" aria-hidden="true"><img src="/images/brand/yifangzhi-mark.png" alt="" /></span></template><small>{{ IMAGE_STATUS[item.status] }}</small></div>
                  <figcaption v-if="item.url || item.error || retryable(item)">
                    <button v-if="item.url" type="button" class="ns-chip is-icon" title="下载" aria-label="下载这张" :disabled="studio.busy || !!studio.pending || !studio.canUseAsset(item)" @click="studio.download(item)"><Download :size="14" aria-hidden="true" /></button>
                    <button v-if="item.url && isLiveImage(turn)" type="button" class="ns-chip" :disabled="studio.locked" @click="studio.regenerate(item)"><RefreshCw :size="13" aria-hidden="true" />再画一张</button>
                    <span v-if="item.error" class="ns-error">{{ item.error }}</span>
                    <button v-if="retryable(item) && isLiveImage(turn)" type="button" class="ns-chip" :disabled="studio.busy || !!studio.pending" @click="studio.retry(item)">{{ item.status === 'FAILED' ? '重新生成这张' : item.status === 'UPSTREAM_UNKNOWN' ? '核对后重新生成' : '恢复这张' }}</button>
                  </figcaption>
                </figure>
              </div>
              <p v-if="version.question" class="ns-note">{{ version.question }}</p>
              <p v-if="version.error" class="ns-error">
                <span>{{ version.error }}</span>
                <button v-if="version.id === studio.current?.id && version.status === 'INTERRUPTED'" type="button" class="ns-link" :disabled="studio.busy || !!studio.pending" @click="studio.retry(null)">恢复任务</button>
              </p>
              <div v-if="index === turn.versions.length - 1 && isLiveImage(turn)" class="ns-actions">
                <button v-if="isImageActive(version.status)" type="button" class="ns-chip" :disabled="studio.busy" @click="studio.cancel()">取消生成</button>
                <button v-if="version.status === 'NEEDS_INPUT' || version.items.some(item => item.url)" type="button" class="ns-chip" :disabled="studio.locked" @click="startRevision(turn)">{{ version.status === 'NEEDS_INPUT' ? '补充一句' : '改一改' }}</button>
              </div>
              <form v-if="revisingRoot === turn.rootId && index === turn.versions.length - 1" class="ns-revise" @submit.prevent="submitRevision">
                <label :for="`revise-${turn.rootId}`" class="sr-only">{{ version.status === 'NEEDS_INPUT' ? '补充信息' : '想怎么改' }}</label>
                <input :id="`revise-${turn.rootId}`" v-model="studio.revision" :placeholder="version.status === 'NEEDS_INPUT' ? '补充一句，比如店名、价格或想突出的东西' : '想怎么改？比如：换成白天的光、背景再简单些'" maxlength="500" />
                <button type="submit" class="ns-chip is-dark" :disabled="studio.locked || !studio.revision.trim()">发送</button>
                <button type="button" class="ns-link" @click="revisingRoot = null">取消</button>
              </form>
            </div>
          </template>
        </template>

        <template v-else-if="turn.kind === 'plan'">
          <div class="ns-user">
            <div v-if="turn.refs.length" class="ns-user-media">
              <figure v-for="(url, refIndex) in turn.refs" :key="refIndex" class="ns-media-thumb">
                <img :src="stableUrl(url)" alt="" loading="lazy" />
                <figcaption>图{{ refIndex + 1 }}</figcaption>
              </figure>
            </div>
            <div class="ns-bubble is-idea">
              <span class="ns-bubble-tag"><Sparkles :size="12" :stroke-width="2" aria-hidden="true" />{{ turn.answer ? '补充信息' : '灵感方案' }}</span>
              <p>{{ turn.idea }}</p>
            </div>
            <ul v-if="turn.style || turn.scene" class="ns-tags" aria-label="这次的设置"><li v-if="turn.scene">{{ turn.scene }}</li><li v-if="turn.style">{{ turn.style }}</li></ul>
          </div>
          <div class="ns-reply">
            <div class="ns-reply-head"><img src="/images/brand/yifangzhi-mark.png" alt="" /><strong>一方志</strong></div>
            <div v-if="turn.status === 'thinking'" class="ns-thinking" role="status">
              <span class="ns-typing" aria-hidden="true"><i /><i /><i /></span>
              <span>{{ turn.mode === 'video' ? vplan.phaseLabel : '正在看图想方案' }}</span>
            </div>
            <p v-else-if="turn.status === 'failed'" class="ns-say is-error">{{ turn.error }}</p>
            <template v-else-if="turn.status === 'question'">
              <p class="ns-say">{{ turn.question }}</p>
              <p class="ns-note">在下面的输入框里回答，我再接着写。</p>
            </template>
            <template v-else>
              <p class="ns-say">出了三个方向，挑一个「用这个」，描述会填进输入框，你还能再改。</p>
              <div class="ns-plans">
                <article v-for="(plan, planIndex) in turn.batch.plans" :key="planIndex" class="ns-plan-card" :class="{ applied: turn.applied === planIndex }">
                  <header><span class="ns-plan-letter">{{ videoPlanLetters[planIndex] }}</span><h3>{{ plan.title }}</h3></header>
                  <p v-if="plan.idea" class="ns-plan-idea">{{ plan.idea }}</p>
                  <p v-if="turn.mode === 'video'" class="ns-plan-meta">{{ plan.ratio === 'auto' ? '比例自选' : plan.ratio }} · {{ plan.durationSeconds }} 秒 · {{ plan.shots?.length || 0 }} 个镜头</p>
                  <ul v-if="plan.materials?.length" class="ns-plan-uses">
                    <li v-for="material in plan.materials" :key="material.index"><img v-if="turn.images?.[material.index - 1]" :src="stableUrl(turn.images[material.index - 1])" alt="" />图{{ material.index }} {{ material.use }}</li>
                  </ul>
                  <details class="ns-plan-detail">
                    <summary>{{ turn.mode === 'video' ? '分镜和生成指令' : '完整画面描述' }}</summary>
                    <ol v-if="turn.mode === 'video' && plan.shots?.length" class="ns-plan-shots"><li v-for="(shot, shotIndex) in plan.shots" :key="shotIndex"><time>{{ videoPlanSeconds(shot.start) }}–{{ videoPlanSeconds(shot.end) }}s</time><span>{{ shot.description }}</span></li></ol>
                    <p>{{ plan.prompt }}</p>
                  </details>
                  <button type="button" class="ns-plan-use" :class="{ active: turn.applied === planIndex }" @click="usePlan(turn, plan, planIndex)"><Check v-if="turn.applied === planIndex" :size="14" :stroke-width="2.2" aria-hidden="true" />{{ turn.applied === planIndex ? '已填入输入框' : '用这个' }}</button>
                </article>
              </div>
              <p v-if="turn.useError" class="ns-error">{{ turn.useError }}</p>
              <div class="ns-actions">
                <button type="button" class="ns-chip" :disabled="planEngineOf(turn.mode).working" @click="morePlans(turn)"><RefreshCw :size="13" aria-hidden="true" />换一批<span class="ns-chip-cost"><YanhuoIcon :size="12" />{{ planEngineOf(turn.mode).credits }}</span></button>
              </div>
            </template>
          </div>
        </template>

        <template v-else-if="conversationOf(turn)">
          <div class="ns-user">
            <div v-if="conversationOf(turn).referenceImages?.length || conversationOf(turn).referenceVideos?.length || conversationOf(turn).referenceAudios?.length" class="ns-user-media">
              <figure v-for="asset in conversationOf(turn).referenceImages || []" :key="asset.id" class="ns-media-thumb">
                <img v-if="asset.url" :src="stableUrl(asset.url)" :alt="asset.name" loading="lazy" />
                <span v-else class="ns-media-missing">已删除</span>
                <figcaption>图片</figcaption>
              </figure>
              <figure v-for="asset in conversationOf(turn).referenceVideos || []" :key="asset.id" class="ns-media-thumb is-file" :title="asset.name">
                <Film :size="18" :stroke-width="1.6" aria-hidden="true" /><figcaption>视频</figcaption>
              </figure>
              <figure v-for="asset in conversationOf(turn).referenceAudios || []" :key="asset.id" class="ns-media-thumb is-file" :title="asset.name">
                <AudioLines :size="18" :stroke-width="1.6" aria-hidden="true" /><figcaption>音色</figcaption>
              </figure>
            </div>
            <div class="ns-bubble" :class="{ 'is-clamped': isLong(conversationOf(turn).workflow?.prompt || conversationOf(turn).prompt) && !expanded.has(turn.key) }">
              <p>{{ conversationOf(turn).workflow?.prompt || conversationOf(turn).prompt }}</p>
              <button v-if="isLong(conversationOf(turn).workflow?.prompt || conversationOf(turn).prompt)" type="button" class="ns-more" @click="toggleExpanded(turn.key)">{{ expanded.has(turn.key) ? '收起' : '展开全部' }}</button>
            </div>
            <ul class="ns-tags" aria-label="这次的设置"><li v-for="tag in videoTags(conversationOf(turn))" :key="tag">{{ tag }}</li></ul>
          </div>
          <div class="ns-reply">
            <div class="ns-reply-head"><img src="/images/brand/yifangzhi-mark.png" alt="" /><strong>一方志</strong></div>
            <video v-if="conversationOf(turn).workflow?.outputUrl" class="ns-video" :src="`${stableUrl(conversationOf(turn).workflow.outputUrl)}#t=0.1`" controls playsinline preload="metadata" />
            <div v-else-if="conversationOf(turn).submitting || isVideoInProgress(conversationOf(turn).workflow)" class="ns-card ns-progress" role="status">
              <div class="ns-progress-head"><span class="ns-brew is-small" aria-hidden="true"><img src="/images/brand/yifangzhi-mark.png" alt="" /></span><div><strong>{{ videoStageLabel(conversationOf(turn)) }}</strong><small>通常要几分钟，可以先离开，回来会接着显示</small></div></div>
              <span class="ns-bar" aria-hidden="true"><i /></span>
              <button v-if="turn.convId === video.activeConversationId && conversationOf(turn).workflow?.id && !conversationOf(turn).submitting" type="button" class="ns-link is-danger" @click="video.cancelVideo">取消生成，退回未消耗烟火</button>
            </div>
            <p v-if="conversationOf(turn).notice" class="ns-note" :class="{ 'ns-error': conversationOf(turn).error }">{{ conversationOf(turn).notice }}</p>
            <p v-if="conversationOf(turn).workflow?.chargedCredits != null" class="ns-spent"><YanhuoIcon :size="14" />本次消耗 {{ number(conversationOf(turn).workflow.chargedCredits) }}</p>
            <button v-if="turn.convId === video.activeConversationId && conversationOf(turn).workflow?.retryable" type="button" class="ns-chip" :disabled="conversationOf(turn).submitting" @click="video.retryVideo">预估恢复费用</button>

            <section v-if="turn.convId === video.activeConversationId && video.quoted" class="ns-card ns-quote" aria-labelledby="ns-quote-title">
              <h3 id="ns-quote-title">确认视频费用</h3>
              <p class="ns-quote-item">{{ videoModelLabel(video.quoted.request.model) }} · {{ video.quoted.request.resolution }} · {{ video.quoted.request.durationSeconds }} 秒</p>
              <template v-if="video.quoted.quote.available">
                <p class="ns-quote-amount"><YanhuoIcon :size="24" />{{ number(video.quoted.quote.credits) }}</p>
                <p class="ns-note">{{ video.quoted.quote.message }}</p>
                <dl>
                  <div><dt>实际模型</dt><dd>{{ video.quoted.quote.providerModel }}</dd></div>
                  <div><dt>预估用量</dt><dd>{{ number(video.quoted.quote.estimatedTokens) }} 视频 Token</dd></div>
                  <div><dt>费率核验</dt><dd>{{ dateTime(video.quoted.quote.verifiedAt) }}</dd></div>
                  <div><dt>可用烟火</dt><dd>{{ billing.error ? '暂不可读' : number(billing.summary?.available || 0) }}</dd></div>
                </dl>
                <p v-if="billing.summary && billing.summary.available < video.quoted.quote.credits" class="ns-error">烟火不足，请先充值。</p>
                <div class="ns-quote-actions">
                  <button type="button" class="ns-primary" :disabled="video.quoteBusy || !!billing.error || (billing.summary?.available || 0) < video.quoted.quote.credits" @click="video.confirmQuote">{{ video.quoteBusy ? '正在提交…' : '确认费用，开始生成' }}</button>
                  <button type="button" class="ns-link" :disabled="video.quoteBusy" @click="video.closeQuote">先不做</button>
                </div>
              </template>
              <template v-else>
                <p class="ns-error">{{ video.quoted.quote.message }}</p>
                <button type="button" class="ns-link" @click="video.closeQuote">知道了</button>
              </template>
              <RouterLink v-if="(billing.summary?.available || 0) < video.quoted.quote.credits" class="ns-link" to="/billing" @click="video.closeQuote">前往套餐与充值</RouterLink>
              <p v-if="video.quoteError" class="ns-error" role="alert">{{ video.quoteError }}</p>
            </section>
          </div>
        </template>
      </template>

      <template v-if="studio.optimistic">
        <div class="ns-user"><div class="ns-bubble"><p>{{ studio.optimisticBrief || '参考图已收到' }}</p></div></div>
        <div class="ns-reply"><div class="ns-reply-head"><img src="/images/brand/yifangzhi-mark.png" alt="" /><strong>一方志</strong></div><div class="ns-images is-single" :style="{ '--ns-ratio': studio.dimensions.width + '/' + studio.dimensions.height }"><div class="ns-image is-placeholder is-working" role="status"><span class="ns-brew" aria-hidden="true"><img src="/images/brand/yifangzhi-mark.png" alt="" /></span><small>一方志正在编排画面</small></div></div></div>
      </template>
      <span ref="threadEnd" />
    </div>

    <div ref="dock" class="ns-dock">
      <form class="ns-composer" :class="{ 'is-ideas': ideaMode }" @submit.prevent="send">
        <div class="ns-composer-top">
          <div class="ns-modes" :class="`is-${mode}`" role="group" aria-label="做图片还是视频">
            <span class="ns-modes-thumb" aria-hidden="true" />
            <button type="button" :aria-pressed="mode === 'image'" :class="{ active: mode === 'image' }" @click="switchMode('image')"><ImagePlus :size="16" :stroke-width="1.6" aria-hidden="true" />图片</button>
            <button type="button" :aria-pressed="mode === 'video'" :class="{ active: mode === 'video' }" @click="switchMode('video')"><Film :size="16" :stroke-width="1.6" aria-hidden="true" />视频</button>
          </div>
          <button type="button" class="ns-idea-button" :class="{ active: ideaMode }" :aria-pressed="ideaMode" @click="toggleIdeas"><Sparkles :size="15" :stroke-width="1.8" aria-hidden="true" />灵感方案<X v-if="ideaMode" :size="13" :stroke-width="2" aria-hidden="true" /></button>
        </div>
        <Transition name="ns-swap">
          <p v-if="ideaMode" class="ns-idea-hint">{{ answering ? '一方志想先确认一件事，回答后接着写方案。' : '说一句想要什么，一方志先出 3 个方向给你挑，挑中的再去生成。' }}</p>
        </Transition>

        <div v-if="mode === 'image' && studio.files.length" class="ns-attachments" aria-label="参考图">
          <div v-for="(file, index) in studio.files" :key="file.preview" class="ns-attachment">
            <img :src="file.preview" :alt="file.name" />
            <button type="button" class="ns-role" :aria-label="`${file.name}：用作${file.role === 'STYLE' ? '风格' : '主体'}，点击切换`" :disabled="studio.locked" @click="studio.files[index].role = file.role === 'STYLE' ? 'SUBJECT' : 'STYLE'">{{ file.role === 'STYLE' ? '风格' : '主体' }}</button>
            <button type="button" class="ns-remove" :aria-label="`移除 ${file.name}`" :disabled="studio.locked" @click="studio.removeFile(index)"><X :size="11" :stroke-width="2.6" aria-hidden="true" /></button>
          </div>
        </div>
        <div v-if="mode === 'video' && (video.referenceImages.length || video.referenceVideos.length || video.referenceAudios.length)" class="ns-attachments" aria-label="参考素材">
          <div v-for="asset in video.referenceImages" :key="asset.id" class="ns-attachment" :title="asset.name">
            <img v-if="asset.url" :src="asset.url" :alt="asset.name" /><span class="ns-role is-static">图片</span>
            <button type="button" class="ns-remove" aria-label="删除参考图" @click="video.removeMaterial('image', asset)"><X :size="11" :stroke-width="2.6" aria-hidden="true" /></button>
          </div>
          <div v-for="asset in video.referenceVideos" :key="asset.id" class="ns-attachment is-file" :title="asset.name">
            <Film :size="18" :stroke-width="1.6" aria-hidden="true" /><span class="ns-role is-static">视频</span>
            <button type="button" class="ns-remove" aria-label="删除参考视频" @click="video.removeMaterial('video', asset)"><X :size="11" :stroke-width="2.6" aria-hidden="true" /></button>
          </div>
          <div v-for="asset in video.referenceAudios" :key="asset.id" class="ns-attachment is-file" :title="asset.name">
            <AudioLines :size="18" :stroke-width="1.6" aria-hidden="true" /><span class="ns-role is-static">音色</span>
            <button type="button" class="ns-remove" aria-label="删除音色" @click="video.removeMaterial('audio', asset)"><X :size="11" :stroke-width="2.6" aria-hidden="true" /></button>
          </div>
        </div>

        <label for="ns-input" class="sr-only">{{ ideaMode ? '想要什么' : mode === 'image' ? '画面描述' : '描述你的成片需求' }}</label>
        <textarea id="ns-input" ref="composerInput" v-model="draft" class="ns-input" :rows="started ? 1 : 3" maxlength="2000" :placeholder="placeholder" :disabled="ideaMode ? planEngine.working : mode === 'image' ? studio.locked : video.isGenerating || video.activeConversation?.loading" @keydown="onKeydown" />

        <div class="ns-toolbar">
          <button v-if="mode === 'image'" type="button" class="ns-icon" :disabled="studio.locked || studio.files.length >= 6" aria-label="添加参考图，最多 6 张" title="添加参考图，最多 6 张" @click="imageInput?.click()"><Paperclip :size="18" :stroke-width="1.6" aria-hidden="true" /></button>
          <button v-else type="button" class="ns-icon" :aria-expanded="panel === 'attach'" aria-label="添加参考图片、参考视频或音色" title="添加参考图片、参考视频或音色" @click="togglePanel('attach')"><Paperclip :size="18" :stroke-width="1.6" aria-hidden="true" /></button>
          <span class="ns-divider" aria-hidden="true" />
          <Transition name="ns-swap" mode="out-in">
            <div :key="`${mode}-${ideaMode}`" class="ns-tools">
              <template v-if="mode === 'image'">
                <button type="button" class="ns-tool" :class="{ open: panel === 'style' }" :aria-expanded="panel === 'style'" @click="togglePanel('style')"><img v-if="currentStyle.image" :src="currentStyle.image" alt="" :class="{ mark: currentStyle.mark }" />{{ currentStyle.name }}</button>
                <button v-if="!ideaMode" type="button" class="ns-tool" :class="{ open: panel === 'output' }" :aria-expanded="panel === 'output'" @click="togglePanel('output')"><SlidersHorizontal :size="15" aria-hidden="true" />{{ studio.ratio }} · {{ studio.quality }}</button>
                <div v-if="!ideaMode" class="ns-count" role="group" aria-label="一次生成几张">
                  <button type="button" aria-label="少一张" :disabled="studio.locked || studio.count <= 1" @click="changeCount(-1)"><Minus :size="13" :stroke-width="2" aria-hidden="true" /></button>
                  <span aria-live="polite"><strong>{{ studio.count }}</strong> 张</span>
                  <button type="button" aria-label="多一张" :disabled="studio.locked || studio.count >= MAX_IMAGES" @click="changeCount(1)"><Plus :size="13" :stroke-width="2" aria-hidden="true" /></button>
                </div>
              </template>
              <template v-else>
                <button v-if="video.currentScene" type="button" class="ns-tool" :class="{ open: panel === 'scene' }" :aria-expanded="panel === 'scene'" @click="togglePanel('scene')"><img v-if="videoSceneCover(video.sceneKey)" :src="videoSceneCover(video.sceneKey)" alt="" />{{ video.currentScene.name }}</button>
                <button v-if="ideaMode" type="button" class="ns-tool" :class="{ open: panel === 'planStyle' }" :aria-expanded="panel === 'planStyle'" @click="togglePanel('planStyle')"><Sparkles :size="15" aria-hidden="true" />{{ vplan.styleText || '风格不限' }}</button>
                <button v-else type="button" class="ns-tool" :class="{ open: panel === 'output', 'needs-pick': !video.format }" :aria-expanded="panel === 'output'" @click="togglePanel('output')"><SlidersHorizontal :size="15" aria-hidden="true" /><template v-if="video.format">{{ video.currentCapability.label }} · {{ video.format }} · {{ video.duration }} 秒 · {{ video.resolution }}</template><template v-else>选择画面比例</template></button>
              </template>
            </div>
          </Transition>
          <span class="ns-cost" :title="sendTitle">
            <YanhuoIcon :size="16" />
            <strong>{{ imageCost != null ? number(imageCost) : mode === 'video' ? '报价后扣' : '—' }}</strong><small v-if="ideaMode">/ 次</small>
          </span>
          <button type="submit" class="ns-send" :class="{ busy: sendBusy }" :disabled="sendDisabled" :title="sendTitle" :aria-label="sendLabel">
            <span v-if="sendBusy" class="ns-spinner" aria-hidden="true" />
            <ArrowUp v-else :size="18" :stroke-width="2.2" aria-hidden="true" />
          </button>
        </div>
        <input ref="imageInput" class="sr-only" type="file" accept="image/jpeg,image/png" multiple :disabled="studio.locked" @change="studio.addFiles" />
        <input :ref="el => (videoInputs.image.value = el)" class="sr-only" type="file" accept="image/*" multiple @change="video.handleMaterialChange('image', $event)" />
        <input :ref="el => (videoInputs.video.value = el)" class="sr-only" type="file" accept="video/*" multiple @change="video.handleMaterialChange('video', $event)" />
        <input :ref="el => (videoInputs.audio.value = el)" class="sr-only" type="file" accept="audio/*" multiple @change="video.handleMaterialChange('audio', $event)" />
      </form>

      <p v-if="mode === 'image' && (studio.error || serviceNotice)" class="ns-composer-note" :class="{ 'ns-error': studio.error }" role="status">
        {{ studio.error || serviceNotice }}
        <button v-if="studio.error" type="button" class="ns-link" @click="studio.error = ''">收起</button>
        <button v-else-if="studio.capabilityState === 'error'" type="button" class="ns-link" @click="studio.loadCapabilities">重试</button>
      </p>
      <p v-if="mode === 'image' && studio.pending" class="ns-composer-note" role="status">上次提交尚未确认，可以恢复原请求。<button type="button" class="ns-link" :disabled="studio.busy" @click="studio.generate">恢复提交</button></p>
      <p v-if="ideaMode && (planNote || planEngine.unavailable)" class="ns-composer-note ns-error" role="status">{{ planNote || '灵感方案暂未开放，可以直接写描述生成。' }}</p>
      <div v-if="ideaMode && planRecords.length && !panel" class="ns-plan-records" aria-label="之前的方案">
        <small>之前的方案</small>
        <button v-for="item in planRecords" :key="item.id" type="button" :disabled="planEngine.working" @click="openPlanRecord(item)">{{ item.idea || item.plans?.[0]?.title || '按参考图出的方案' }}</button>
      </div>
      <p v-if="mode === 'video' && activeVideoDraft && video.notice" class="ns-composer-note" :class="{ 'ns-error': video.activeConversation?.error }" role="status">{{ video.notice }}</p>

      <section v-if="panel === 'style'" class="ns-panel" aria-label="风格">
        <header><h2>风格</h2><small>不选就是「帮我搭配」，按画面需求自动匹配</small></header>
        <div class="ns-tiles is-styles">
          <button v-for="style in styleTiles" :key="style.name" type="button" class="ns-tile" :class="{ selected: studio.style === style.name }" :aria-pressed="studio.style === style.name" :title="style.fit" :disabled="studio.locked" @click="studio.style = style.name">
            <img :src="style.image" alt="" :class="{ mark: style.mark }" loading="lazy" /><span>{{ style.name }}</span>
          </button>
          <button type="button" class="ns-tile is-seasonal" :class="{ selected: studio.style === seasonalStyle }" :aria-pressed="studio.style === seasonalStyle" :disabled="studio.locked" @click="studio.style = seasonalStyle">
            <span class="ns-seasonal-art"><small>应时</small><strong>{{ moment.name }}</strong><small>{{ moment.days ? `${moment.days} 天后` : '就是今天' }}</small></span><span>应时 · {{ moment.name }}</span>
          </button>
        </div>
      </section>

      <section v-else-if="panel === 'scene'" class="ns-panel" aria-label="场景">
        <header><h2>场景</h2><small>场景只补你没写到的拍法，你写的内容优先</small></header>
        <div class="ns-tiles is-scenes">
          <button v-for="scene in sceneTiles" :key="scene.key" type="button" class="ns-tile" :class="{ selected: video.sceneKey === scene.key }" :aria-pressed="video.sceneKey === scene.key" @click="video.sceneKey = scene.key">
            <img v-if="scene.cover" :src="scene.cover" alt="" loading="lazy" /><span v-else class="ns-tile-blank material-symbols-outlined" aria-hidden="true">{{ scene.icon }}</span>
            <span>{{ scene.name }}</span><small>{{ scene.summary }}</small>
          </button>
        </div>
        <p v-if="video.currentScene?.materialHint && video.sceneKey !== FREE_SCENE" class="ns-note">{{ video.currentScene.materialHint }}</p>
      </section>

      <section v-else-if="panel === 'output'" class="ns-panel" aria-label="输出设置">
        <header><h2>输出设置</h2><small>{{ sendTitle }}</small></header>
        <template v-if="mode === 'image'">
          <div class="ns-setting"><span>比例</span><div class="ns-segmented" role="group" aria-label="图片比例"><button v-for="ratio in IMAGE_RATIOS" :key="ratio" type="button" :aria-pressed="studio.ratio === ratio" :class="{ active: studio.ratio === ratio }" :disabled="studio.locked || !studio.supportsRatio(ratio)" @click="studio.ratio = ratio">{{ ratio }}</button></div></div>
          <div class="ns-setting"><span>画质</span><div class="ns-segmented" role="group" aria-label="输出画质"><button v-for="quality in IMAGE_QUALITIES" :key="quality" type="button" :aria-pressed="studio.quality === quality" :class="{ active: studio.quality === quality }" :disabled="studio.locked || !studio.supportsQuality(quality)" :title="studio.supportsQuality(quality) ? undefined : '当前比例不支持该画质'" @click="studio.quality = quality">{{ quality }}</button></div></div>
          <div class="ns-setting"><span>张数</span><div class="ns-segmented" role="group" aria-label="一次生成几张"><button v-for="n in MAX_IMAGES" :key="n" type="button" :aria-pressed="studio.count === n" :class="{ active: studio.count === n }" :disabled="studio.locked" @click="studio.count = n">{{ n }} 张</button></div></div>
        </template>
        <template v-else>
          <div class="ns-setting"><span>模型</span><div class="ns-segmented" role="group" aria-label="视频模型"><button v-for="model in videoModels" :key="model.id" type="button" :aria-pressed="video.selectedModel === model.id" :class="{ active: video.selectedModel === model.id }" @click="video.selectModel(model.id)">{{ model.label }}</button></div></div>
          <div class="ns-setting" :class="{ 'needs-pick': !video.format }"><span>画面比例{{ video.format ? '' : ' · 请选一个' }}</span><div class="ns-segmented" role="group" aria-label="画面比例"><button v-for="option in videoRatioOptions" :key="option.value" type="button" :aria-pressed="video.format === option.value" :class="{ active: video.format === option.value }" @click="video.format = option.value">{{ option.label }}</button></div></div>
          <div class="ns-setting"><label for="ns-duration">视频时长</label><input id="ns-duration" v-model.number="video.duration" type="range" min="5" :max="video.durationMax" step="1" /><strong>{{ video.duration }} 秒</strong></div>
          <div class="ns-setting"><span>分辨率</span><div class="ns-segmented" role="group" aria-label="分辨率"><button v-for="item in video.availableResolutions" :key="item" type="button" :aria-pressed="video.resolution === item" :class="{ active: video.resolution === item }" @click="video.resolution = item">{{ item }}</button></div></div>
          <p class="ns-note">{{ video.currentCapability.label }} 可用素材：{{ video.materialLimitLabel }}</p>
        </template>
      </section>

      <section v-else-if="panel === 'attach'" class="ns-panel" aria-label="添加素材">
        <header><h2>添加素材</h2><small>能放几个跟着所选模型走</small></header>
        <div class="ns-attach-options">
          <button type="button" class="ns-attach-option" @click="pickVideoFiles('image')"><ImagePlus :size="18" :stroke-width="1.6" aria-hidden="true" /><span>参考图片</span><small>最多 {{ video.materialLimits.image }} 张</small></button>
          <button type="button" class="ns-attach-option" @click="pickVideoFiles('video')"><Film :size="18" :stroke-width="1.6" aria-hidden="true" /><span>参考视频</span><small>最多 {{ video.materialLimits.video }} 个</small></button>
          <button type="button" class="ns-attach-option" @click="pickVideoFiles('audio')"><AudioLines :size="18" :stroke-width="1.6" aria-hidden="true" /><span>音色</span><small>最多 {{ video.materialLimits.audio }} 个</small></button>
        </div>
      </section>

      <section v-else-if="panel === 'planStyle'" class="ns-panel" aria-label="方案风格">
        <header><h2>方案风格</h2><small>可以不选，三个方案各自搭配</small></header>
        <div class="ns-segmented" role="group" aria-label="方案风格">
          <button v-for="option in videoPlanStyles" :key="option" type="button" :aria-pressed="!vplan.customOpen && vplan.style === option" :class="{ active: !vplan.customOpen && vplan.style === option }" @click="vplan.pickStyle(option)">{{ option }}</button>
          <button type="button" :aria-pressed="vplan.customOpen" :class="{ active: vplan.customOpen }" @click="vplan.openCustom()">自己填</button>
        </div>
        <label v-if="vplan.customOpen" class="ns-field"><span class="sr-only">自己填写风格</span><input v-model="vplan.customStyle" maxlength="20" placeholder="例如：复古港风、清冷极简" /></label>
      </section>

      <section v-if="!started && !panel" class="ns-ideas" aria-label="试试这样说">
        <small>试试这样说</small>
        <button v-for="idea in ideas" :key="idea" type="button" @click="draft = idea"><ArrowUp :size="14" :stroke-width="1.8" class="ns-idea-arrow" aria-hidden="true" />{{ idea }}</button>
      </section>
    </div>
  </div>
</template>
