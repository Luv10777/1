<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { Film, RefreshCw, Sparkles } from 'lucide-vue-next'
import YanhuoIcon from './YanhuoIcon.vue'
import { refreshBilling, requireAccount } from '../stores/billing'
import { isVideoPlanDone, videoPlanApi, videoPlanForm, videoPlanLetters, videoPlanRecordImages, videoPlanSeconds, videoPlanStyles } from '../services/videoPlans'
import { videoHistoryTime } from '../services/videoConversations'
import { auth } from '../stores/auth'

// 视频工作台右侧「视频方案」：可选功能，出 3 个版本后「用这个」填进左侧表单，商家仍可自己写。
// 出过的方案存在服务端，「灵感记录」里可以再看、再用。
const props = defineProps({
  images: { type: Array, default: () => [] },
  video: { type: Object, default: null },
  upload: { type: Function, required: true },
  maxDuration: { type: Number, default: 30 },
  ratios: { type: Array, default: () => [] },
  locked: Boolean,
})
const emit = defineEmits(['apply'])

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
const planImages = computed(() => props.images.slice(0, config.value?.maxImages ?? 6))
const missingRecordImages = computed(() => record.value?.images.filter(image => !image.url).length || 0)

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
    const body = { imageAssetIds, referenceVideo: Boolean(props.video), style: styleText.value, idea: idea.value.trim(), previousPlanId }
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
  record.value = item
  appliedIndex.value = -1
  customOpen.value = Boolean(item.style) && !videoPlanStyles.includes(item.style)
  style.value = customOpen.value ? '' : item.style || ''
  customStyle.value = customOpen.value ? item.style : ''
  idea.value = item.idea || ''
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
  emit('apply', videoPlanForm(plan, { maxDuration: props.maxDuration, ratios: props.ratios }), videoPlanLetters[index], images)
}
</script>

<template>
  <section class="vp-panel" aria-label="视频方案">
    <template v-if="!showResults || !result">
      <p class="vp-intro">按你的素材和想法出 3 个版本的提示词和分镜，选一个填进描述框。不用也可以直接在左侧自己写。</p>
      <section class="vp-section">
        <strong class="vp-heading">素材<small>AI 会判断每张图怎么用，写进提示词</small></strong>
        <div v-if="images.length || video" class="vp-materials">
          <figure v-for="(entry, index) in planImages" :key="entry.id"><img v-if="entry.url" :src="entry.url" :alt="entry.name"><figcaption>图{{ index + 1 }}</figcaption></figure>
          <figure v-if="video" class="is-video"><Film :size="18" :stroke-width="1.6" aria-hidden="true" /><figcaption>参考视频 · 只参考运镜</figcaption></figure>
        </div>
        <p v-else class="vp-muted">还没有参考图，也可以只按一句话出方案；在左侧添加商品图效果更好。</p>
        <p v-if="images.length > planImages.length" class="vp-muted">方案仅参考前 {{ planImages.length }} 张图片，生成视频时仍使用全部素材。</p>
      </section>
      <section class="vp-section">
        <strong class="vp-heading">风格<small>可不选</small></strong>
        <div class="vp-chips">
          <button v-for="item in videoPlanStyles" :key="item" type="button" :aria-pressed="style === item" :class="{ active: style === item }" @click="pickStyle(item)">{{ item }}</button>
          <button type="button" class="is-custom" :aria-pressed="customOpen" :class="{ active: customOpen }" @click="openCustom">自己填</button>
        </div>
        <input v-if="customOpen" v-model="customStyle" class="vp-input" maxlength="40" aria-label="自己填写风格" placeholder="例如：复古港风、清冷极简">
      </section>
      <section class="vp-section">
        <label for="vp-idea" class="vp-heading">一句话说说想要什么</label>
        <input id="vp-idea" v-model="idea" class="vp-input" maxlength="500" placeholder="例如：新品香水上新，发抖音，安静高级一点" @keydown.enter.prevent="run()">
      </section>
      <div class="vp-footer">
        <button class="vp-primary" type="button" :disabled="working || unavailable" @click="run()"><Sparkles :size="16" :stroke-width="1.8" aria-hidden="true" />{{ working ? '正在出方案…' : '生成提示词' }}<span class="vp-cost"><YanhuoIcon :size="13" />{{ credits }}</span></button>
        <small>每次出 3 个版本 · 换一批同样 {{ credits }} 烟火 · 失败不扣</small>
      </div>
      <button v-if="result" type="button" class="vp-link" @click="showResults = true">回到上一批方案</button>
      <section ref="recordsSection" class="vp-section" aria-label="灵感记录">
        <strong class="vp-heading">灵感记录<small>出过的方案都会留在这里，可以再看或直接用</small></strong>
        <button v-for="item in records" :key="item.id" type="button" class="vp-record" :class="{ active: record?.id === item.id }" :disabled="working" @click="openRecord(item)">
          <span class="vp-record-thumbs"><img v-for="image in item.images.filter(image => image.url).slice(0, 3)" :key="image.assetId" :src="image.url" alt=""><Sparkles v-if="!item.images.some(image => image.url)" :size="16" :stroke-width="1.6" aria-hidden="true" /></span>
          <span class="vp-record-text"><strong>{{ item.plans.map(plan => plan.title).join(' / ') }}</strong><small>{{ videoHistoryTime(item.createdAt) }}<template v-if="item.style"> · {{ item.style }}</template><template v-if="item.idea"> · {{ item.idea }}</template></small></span>
        </button>
        <p v-if="recordsLoading" class="vp-muted" role="status">正在读取灵感记录…</p>
        <template v-else-if="recordsError">
          <p class="vp-status is-error" role="status">{{ recordsError }}</p>
          <button type="button" class="vp-link" @click="loadRecords(recordsPage)">重新加载</button>
        </template>
        <button v-else-if="recordsHasMore" type="button" class="vp-link" @click="loadRecords(recordsPage + 1)">加载更早的记录</button>
        <p v-else-if="!records.length && !working" class="vp-muted">还没有出过方案。</p>
      </section>
    </template>

    <template v-else>
      <div class="vp-results-head">
        <p><template v-if="record">{{ videoHistoryTime(record.createdAt) }} 的方案。</template>选一个方案，会填进描述框，可以再改字。</p>
        <button type="button" class="vp-link" :disabled="working" @click="showRecords">灵感记录</button>
        <button type="button" class="vp-link" :disabled="working" @click="showResults = false">改设置</button>
      </div>
      <p v-if="missingRecordImages" class="vp-status">这批方案用过的 {{ missingRecordImages }} 张参考图已删除，提示词里的图号可能对不上，用之前请核对。</p>
      <div class="vp-cards" :class="{ 'is-working': working }">
        <article v-for="(plan, index) in result.plans" :key="index" class="vp-card">
          <span class="vp-letter">方案 {{ videoPlanLetters[index] }}<small v-if="appliedIndex === index">已填入描述框</small></span>
          <h3>{{ plan.title }}</h3>
          <p class="vp-idea">{{ plan.idea }}</p>
          <p class="vp-meta">{{ plan.ratio === 'auto' ? '自适应' : plan.ratio }} · {{ plan.durationSeconds }} 秒 · {{ plan.shots.length }} 个镜头</p>
          <ul v-if="plan.materials.length" class="vp-uses">
            <li v-for="item in plan.materials" :key="item.index"><img v-if="resultImages[item.index - 1]" :src="resultImages[item.index - 1]" alt=""><span><b>图{{ item.index }}</b>{{ item.use }}</span></li>
          </ul>
          <ol class="vp-shots">
            <li v-for="(shot, shotIndex) in plan.shots" :key="shotIndex"><time>{{ videoPlanSeconds(shot.start) }}–{{ videoPlanSeconds(shot.end) }}s</time><span>{{ shot.description }}</span></li>
          </ol>
          <details class="vp-prompt"><summary>完整提示词</summary><p>{{ plan.prompt }}</p></details>
          <button type="button" class="vp-use" :class="{ active: appliedIndex === index }" :disabled="locked" @click="use(plan, index)">{{ appliedIndex === index ? '已填入' : '用这个' }}</button>
        </article>
      </div>
      <div class="vp-footer is-center">
        <button type="button" class="vp-secondary" :disabled="working" @click="run(result.id)"><RefreshCw :size="15" :stroke-width="1.8" aria-hidden="true" />{{ working ? '正在换一批…' : '换一批' }}<span class="vp-cost is-plain"><YanhuoIcon :size="13" />{{ credits }}</span></button>
      </div>
    </template>

    <p v-if="working" class="vp-status" role="status">正在看图出方案，通常 20 秒左右，可以先回左侧继续编辑；离开页面再回来也会接着显示这一批。</p>
    <p v-if="unavailable" class="vp-status" role="status">出方案暂未开放，可以直接在左侧写描述。</p>
    <p v-if="error" class="vp-status is-error" role="alert">{{ error }}</p>
  </section>
</template>
