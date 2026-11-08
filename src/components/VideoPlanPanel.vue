<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { Check, ChevronLeft, Film, RefreshCw, SlidersHorizontal, Sparkles } from 'lucide-vue-next'
import YanhuoIcon from './YanhuoIcon.vue'
import { refreshBilling, requireAccount } from '../stores/billing'
import { isVideoPlanDone, videoPlanApi, videoPlanForm, videoPlanLetters, videoPlanRecordImages, videoPlanSeconds, videoPlanStyles } from '../services/videoPlans'
import { videoHistoryTime } from '../services/videoConversations'
import { FREE_SCENE, videoSceneKey, videoSceneName } from '../services/videoScenes'
import { auth } from '../stores/auth'
import '../inspiration-panel.css'

// 视频工作台右侧「视频方案」：可选功能，出 3 个版本后「用这个」填进左侧表单，商家仍可自己写。
// 出过的方案存在服务端，「灵感记录」里可以再看、再用。
const props = defineProps({
  images: { type: Array, default: () => [] },
  video: { type: Object, default: null },
  upload: { type: Function, required: true },
  maxDuration: { type: Number, default: 30 },
  ratios: { type: Array, default: () => [] },
  locked: Boolean,
  // 工作台「场景」一栏选的场景；出方案时参考它，「换一批」由服务端沿用上一批的场景。
  scene: { type: String, default: FREE_SCENE },
  scenes: { type: Array, default: () => [] },
})
const emit = defineEmits(['apply', 'scene'])

const config = ref(null)
const style = ref('')
const customStyle = ref('')
const customOpen = ref(false)
const idea = ref('')
const working = ref(false)
const error = ref('')
const result = ref(null)
const showResults = ref(false)
const appliedIndex = ref(-1)
const resultImages = ref([])
// 正在看的是灵感记录里的哪一批；刚出的方案为 null。
const record = ref(null)
const records = ref([])
const recordsPage = ref(0)
const recordsHasMore = ref(false)
const recordsLoading = ref(false)
const recordsError = ref('')
const recordsSection = ref(null)
let requestKey = ''
let signature = ''
let disposed = false

const credits = computed(() => config.value?.credits ?? 1)
const styleText = computed(() => (customOpen.value ? customStyle.value : style.value).trim())
const unavailable = computed(() => config.value && !config.value.configured)
const planImages = computed(() => props.images.slice(0, config.value?.maxImages ?? 30))
const missingRecordImages = computed(() => record.value?.images.filter(image => !image.url).length || 0)
const sceneName = computed(() => videoSceneName(props.scenes, props.scene))
// 这批方案实际用的场景（「换一批」沿用上一批的），以服务端记录为准。
const resultSceneName = computed(() => videoSceneName(props.scenes, result.value?.sceneKey))
// 结果页标题用：这批方案当初的一句话、风格、参考图张数和时间。
const resultInfo = ref(null)
const resultMeta = computed(() => [resultSceneName.value, resultInfo.value?.style,
  resultInfo.value?.imageCount ? `${resultInfo.value.imageCount} 张参考图` : '',
  resultInfo.value?.createdAt ? videoHistoryTime(resultInfo.value.createdAt) : ''].filter(Boolean).join(' · '))

const fetchRecords = async page => {
  recordsLoading.value = true
  recordsError.value = ''
  try {
    const data = await videoPlanApi.history(page)
    if (disposed) return
    const items = data.items || []
    records.value = page === 0 ? items : [...records.value, ...items.filter(item => !records.value.some(known => known.id === item.id))]
    recordsPage.value = page
    recordsHasMore.value = page + 1 < data.totalPages
  } catch (e) {
    recordsError.value = e.message || '灵感记录暂时无法加载'
  } finally {
    recordsLoading.value = false
  }
}
// 排队读取：出完方案后的刷新排在进入页面时那次读取后面，才读得到刚出的这批。
let recordsQueue = Promise.resolve()
const loadRecords = (page = 0) => (recordsQueue = recordsQueue.then(() => fetchRecords(page)))

// 出方案时离开页面，回来接着等这一批，出完直接打开；只记在当前浏览器标签页里。
const pendingKey = `video-plan-pending:${auth.user?.id || 'session'}`
const rememberPending = id => {
  try { if (id) sessionStorage.setItem(pendingKey, String(id)); else sessionStorage.removeItem(pendingKey) }
  catch { /* 存不了只是回来后接不上，方案仍会出现在灵感记录里 */ }
}
const pendingPlanId = () => {
  try { return Number(sessionStorage.getItem(pendingKey)) || null }
  catch { return null }
}

const pickStyle = value => { customOpen.value = false; style.value = style.value === value ? '' : value }
const openCustom = () => { customOpen.value = true; style.value = '' }
const wait = ms => new Promise(resolve => window.setTimeout(resolve, ms))

// 轮询到出完为止；中途离开页面返回 null，记录留着等回来再接上。
const settle = async plan => {
  rememberPending(plan.id)
  while (!isVideoPlanDone(plan)) {
    await wait(2000)
    if (disposed) return null
    plan = await videoPlanApi.get(plan.id)
  }
  rememberPending(null)
  refreshBilling()
  return plan
}

const run = async (previousPlanId = null) => {
  if (working.value || unavailable.value) return
  error.value = ''
  try { await requireAccount() }
  catch (e) { error.value = e.message; return }
  if (!props.images.length && !idea.value.trim()) { error.value = '请先在左侧添加参考图，或写一句想要的效果。'; return }
  working.value = true
  try {
    const imageAssetIds = await Promise.all(planImages.value.map(entry => props.upload(entry, 'IMAGE')))
    const body = { imageAssetIds, referenceVideo: Boolean(props.video), style: styleText.value, idea: idea.value.trim(), previousPlanId,
      ...(videoSceneKey(props.scene) !== FREE_SCENE && { sceneKey: props.scene }) }
    // 网络中断后重试同一份请求沿用原编号，不会重复预占烟火。
    if (JSON.stringify(body) !== signature) { requestKey = `video-plan-${crypto.randomUUID()}`; signature = JSON.stringify(body) }
    const images = planImages.value.map(entry => entry.url)
    let plan = await videoPlanApi.create({ requestKey, ...body })
    refreshBilling()
    plan = await settle(plan)
    if (!plan) return
    signature = ''
    if (plan.status === 'FAILED') { error.value = plan.errorMessage || '出方案没有完成，烟火已退回，请重试。'; return }
    result.value = plan
    resultImages.value = images
    resultInfo.value = { idea: body.idea, style: body.style, imageCount: images.length, createdAt: plan.createdAt }
    record.value = null
    appliedIndex.value = -1
    showResults.value = true
    loadRecords()
  } catch (e) {
    error.value = e.message || '出方案暂时失败，请稍后重试。'
  } finally {
    working.value = false
  }
}

// 打开一批记录时把当时的风格和想法也带回来，「换一批」「改设置」都接着这批的要求。
const openRecord = item => {
  if (working.value) return
  result.value = item
  resultImages.value = item.images.map(image => image.url)
  resultInfo.value = { idea: item.idea, style: item.style, imageCount: item.images.length, createdAt: item.createdAt }
  record.value = item
  appliedIndex.value = -1
  customOpen.value = Boolean(item.style) && !videoPlanStyles.includes(item.style)
  style.value = customOpen.value ? '' : item.style || ''
  customStyle.value = customOpen.value ? item.style : ''
  idea.value = item.idea || ''
  // 工作台的场景一起切回这批当时的场景，之后的生成和这批方案用同一个场景。
  emit('scene', videoSceneKey(item.sceneKey))
  showResults.value = true
}
const resume = async id => {
  working.value = true
  let plan = null
  try { plan = await settle(await videoPlanApi.get(id)) }
  catch { rememberPending(null) } // 方案读不到（例如换了账号）就不再接，灵感记录里仍能找到
  finally { working.value = false }
  if (disposed || !plan) return
  if (plan.status === 'FAILED') { error.value = plan.errorMessage || '出方案没有完成，烟火已退回，请重试。'; return }
  await loadRecords()
  const item = records.value.find(entry => entry.id === plan.id)
  if (item && !disposed) openRecord(item)
}

onMounted(() => {
  videoPlanApi.config().then(data => { if (!disposed) config.value = data }).catch(() => {})
  loadRecords()
  const pending = pendingPlanId()
  if (pending) resume(pending)
})
onBeforeUnmount(() => { disposed = true })

const showRecords = async () => {
  showResults.value = false
  await nextTick()
  recordsSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

const use = (plan, index) => {
  if (props.locked) return
  appliedIndex.value = index
  const images = record.value ? videoPlanRecordImages(record.value, props.images) : null
  emit('apply', videoPlanForm(plan, { maxDuration: props.maxDuration, ratios: props.ratios }), videoPlanLetters[index], images, result.value.id)
}
</script>

<template>
  <section class="vp-panel ip-panel" aria-label="视频方案">
    <template v-if="!showResults || !result">
      <div class="ip-hero">
        <h2>说一句想要的视频，一方志给你三种拍法。</h2>
        <p>按你的素材、场景和想法出 3 个版本的提示词和分镜，选一个填进左侧描述框。不用也可以直接自己写。</p>
      </div>
      <div class="ip-composer">
        <div class="ip-context">
          <span class="ip-context-label">素材</span>
          <span v-for="(entry, index) in planImages.slice(0, 6)" :key="entry.id" class="ip-chip has-thumb"><img v-if="entry.url" :src="entry.url" alt="">图{{ index + 1 }}</span>
          <span v-if="planImages.length > 6" class="ip-chip">+{{ planImages.length - 6 }} 张</span>
          <span v-if="video" class="ip-chip has-thumb"><span class="ip-chip-icon"><Film :size="15" :stroke-width="1.6" aria-hidden="true" /></span>参考视频 · 只参考运镜</span>
          <span v-if="!planImages.length && !video" class="ip-chip is-empty">还没有素材，在左侧添加商品图效果更好</span>
          <template v-if="sceneName">
            <span class="ip-context-divider" aria-hidden="true" />
            <span class="ip-context-label">场景</span>
            <span class="ip-chip is-accent" :title="'可在左侧「创作场景」更换'">{{ sceneName }}</span>
          </template>
        </div>
        <p v-if="images.length > planImages.length" class="ip-muted">方案仅参考前 {{ planImages.length }} 张图片，生成视频时仍使用全部素材。</p>
        <div class="ip-styles" role="group" aria-label="风格，可不选">
          <span class="ip-context-label">风格</span>
          <button v-for="item in videoPlanStyles" :key="item" type="button" :aria-pressed="style === item" :class="{ active: style === item }" @click="pickStyle(item)">{{ item }}</button>
          <button type="button" :aria-pressed="customOpen" :class="{ active: customOpen }" @click="openCustom">自己填</button>
          <input v-if="customOpen" v-model="customStyle" maxlength="40" aria-label="自己填写风格" placeholder="例如：复古港风、清冷极简">
        </div>
        <label for="vp-idea" class="sr-only">一句话说说想要什么</label>
        <textarea id="vp-idea" v-model="idea" class="ip-idea" rows="2" maxlength="500" placeholder="一句话说说想要什么，例如：新品香水上新，发抖音，安静高级一点" @keydown.enter.exact.prevent="run()" />
        <div class="ip-composer-foot">
          <small>每次出 3 个版本 · 换一批同价 · 失败不扣烟火</small>
          <button class="ip-primary" type="button" :disabled="working || unavailable" @click="run()"><Sparkles :size="16" :stroke-width="1.8" aria-hidden="true" />{{ working ? '正在出方案…' : '生成提示词' }}<span class="ip-cost"><YanhuoIcon :size="13" />{{ credits }} 烟火</span></button>
        </div>
      </div>
      <button v-if="result" type="button" class="ip-link" @click="showResults = true">回到上一批方案</button>
      <section ref="recordsSection" class="ip-section" aria-label="灵感记录">
        <div class="ip-section-head"><h3>灵感记录</h3><small>出过的方案都在这里，点开可以再看、再用</small></div>
        <div v-if="records.length" class="ip-records">
          <button v-for="item in records" :key="item.id" type="button" class="ip-record" :class="{ active: record?.id === item.id }" :disabled="working" @click="openRecord(item)">
            <span class="ip-record-thumbs" aria-hidden="true"><img v-for="image in item.images.filter(image => image.url).slice(0, 3)" :key="image.assetId" :src="image.url" alt=""><span v-if="!item.images.some(image => image.url)">没有参考图</span></span>
            <ol><li v-for="(plan, index) in item.plans" :key="index"><b>{{ videoPlanLetters[index] }}</b>{{ plan.title }}</li></ol>
            <small>{{ videoHistoryTime(item.createdAt) }}<template v-if="videoSceneName(scenes, item.sceneKey)"> · {{ videoSceneName(scenes, item.sceneKey) }}</template><template v-if="item.style || item.idea"> · {{ item.style || item.idea }}</template></small>
          </button>
        </div>
        <p v-if="recordsLoading" class="ip-muted" role="status">正在读取灵感记录…</p>
        <template v-else-if="recordsError">
          <p class="ip-status is-error" role="status">{{ recordsError }}</p>
          <button type="button" class="ip-link" @click="loadRecords(recordsPage)">重新加载</button>
        </template>
        <button v-else-if="recordsHasMore" type="button" class="ip-link" @click="loadRecords(recordsPage + 1)">加载更早的记录</button>
        <p v-else-if="!records.length && !working" class="ip-muted">还没有出过方案。</p>
      </section>
    </template>

    <template v-else>
      <header class="ip-results-head">
        <div class="ip-results-title">
          <button type="button" class="ip-back" :disabled="working" @click="showRecords"><ChevronLeft :size="14" :stroke-width="1.8" aria-hidden="true" />灵感记录</button>
          <h2>{{ resultInfo?.idea || '按素材出的视频方案' }}</h2>
          <small v-if="resultMeta">{{ resultMeta }}</small>
        </div>
        <div class="ip-results-actions">
          <button type="button" class="ip-outline" :disabled="working" @click="showResults = false"><SlidersHorizontal :size="15" :stroke-width="1.8" aria-hidden="true" />重选</button>
          <button type="button" class="ip-primary" :disabled="working" @click="run(result.id)"><RefreshCw :size="15" :stroke-width="1.8" aria-hidden="true" />{{ working ? '正在换一批…' : '换一批' }}<span class="ip-cost"><YanhuoIcon :size="13" />{{ credits }} 烟火</span></button>
        </div>
      </header>
      <p v-if="missingRecordImages" class="ip-status">这批方案用过的 {{ missingRecordImages }} 张参考图已删除，提示词里的图号可能对不上，用之前请核对。</p>
      <div class="ip-cards" :class="{ 'is-working': working }">
        <article v-for="(plan, index) in result.plans" :key="index" class="ip-card" :class="{ 'is-applied': appliedIndex === index }">
          <div class="ip-card-title"><span class="ip-letter">{{ videoPlanLetters[index] }}</span><h3>{{ plan.title }}</h3></div>
          <p v-if="plan.idea" class="ip-card-idea">{{ plan.idea }}</p>
          <p class="ip-card-meta">{{ plan.ratio === 'auto' ? '自适应' : plan.ratio }} · {{ plan.durationSeconds }} 秒 · {{ plan.shots.length }} 个镜头</p>
          <ul v-if="plan.materials.length" class="ip-uses">
            <li v-for="item in plan.materials" :key="item.index" :class="{ 'no-thumb': !resultImages[item.index - 1] }"><img v-if="resultImages[item.index - 1]" :src="resultImages[item.index - 1]" alt="">图{{ item.index }} {{ item.use }}</li>
          </ul>
          <ol class="ip-shots">
            <li v-for="(shot, shotIndex) in plan.shots" :key="shotIndex"><time>{{ videoPlanSeconds(shot.start) }}–{{ videoPlanSeconds(shot.end) }}s</time><span>{{ shot.description }}</span></li>
          </ol>
          <details class="ip-prompt"><summary>完整提示词</summary><p>{{ plan.prompt }}</p></details>
          <button type="button" class="ip-use" :class="{ active: appliedIndex === index }" :disabled="locked" @click="use(plan, index)"><template v-if="appliedIndex === index"><Check :size="15" :stroke-width="2" aria-hidden="true" />已填入描述框</template><template v-else>用这个</template></button>
        </article>
      </div>
      <p class="ip-foot-note">选中的提示词会填进左侧描述框，可以再改字；左侧素材和当初不一样时，会一起换回当初那几张。</p>
    </template>

    <p v-if="working" class="ip-status" role="status">正在看图出方案，通常 1 分钟左右，可以先回左侧继续编辑；离开页面再回来也会接着显示这一批。</p>
    <p v-if="unavailable" class="ip-status" role="status">出方案暂未开放，可以直接在左侧写描述。</p>
    <p v-if="error" class="ip-status is-error" role="alert">{{ error }}</p>
  </section>
</template>
