<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { Check, ChevronLeft, RefreshCw, SlidersHorizontal, Sparkles } from 'lucide-vue-next'
import YanhuoIcon from './YanhuoIcon.vue'
import { refreshBilling, requireAccount } from '../stores/billing'
import { uploadImageReference } from '../services/imageCreation'
import { imageInspirationApi, inspirationRecordFiles, inspirationStyle, isImageInspirationDone } from '../services/imageInspirations'
import { videoPlanLetters } from '../services/videoPlans'
import { videoHistoryTime } from '../services/videoConversations'
import { auth } from '../stores/auth'
import '../inspiration-panel.css'

// 灵感绘图右侧「灵感方案」：按左侧参考图、绘图风格和一句话出 3 个画面描述，「用这个」填进画面描述。
// 出过的方案存在服务端，「灵感记录」里可以再看、再用。
const props = defineProps({ studio: { type: Object, required: true } })
const studio = computed(() => props.studio)

const config = ref(null)
const idea = ref('')
const working = ref(false)
const error = ref('')
const result = ref(null)
const showResults = ref(false)
const appliedIndex = ref(-1)
const resultImages = ref([])
// 结果页标题用：这批方案当初的一句话、风格、参考图张数和时间。
const resultInfo = ref(null)
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
const unavailable = computed(() => config.value && !config.value.configured)
const planFiles = computed(() => studio.value.files.slice(0, config.value?.maxImages ?? 6))
const styleName = computed(() => inspirationStyle(studio.value.style))
const missingRecordImages = computed(() => record.value?.images.filter(image => !image.url).length || 0)
const resultMeta = computed(() => [resultInfo.value?.style, resultInfo.value?.imageCount ? `${resultInfo.value.imageCount} 张参考图` : '',
  resultInfo.value?.createdAt ? videoHistoryTime(resultInfo.value.createdAt) : ''].filter(Boolean).join(' · '))

const fetchRecords = async page => {
  recordsLoading.value = true
  recordsError.value = ''
  try {
    const data = await imageInspirationApi.history(page)
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
const pendingKey = `image-inspiration-pending:${auth.user?.id || 'session'}`
const rememberPending = id => {
  try { if (id) sessionStorage.setItem(pendingKey, String(id)); else sessionStorage.removeItem(pendingKey) }
  catch { /* 存不了只是回来后接不上，方案仍会出现在灵感记录里 */ }
}
const pendingId = () => {
  try { return Number(sessionStorage.getItem(pendingKey)) || null }
  catch { return null }
}

const wait = ms => new Promise(resolve => window.setTimeout(resolve, ms))

// 轮询到出完为止；中途离开页面返回 null，记录留着等回来再接上。
const settle = async batch => {
  rememberPending(batch.id)
  while (!isImageInspirationDone(batch)) {
    await wait(2000)
    if (disposed) return null
    batch = await imageInspirationApi.get(batch.id)
  }
  rememberPending(null)
  refreshBilling()
  return batch
}

const run = async (previousId = null) => {
  if (working.value || unavailable.value) return
  error.value = ''
  try { await requireAccount() }
  catch (e) { error.value = e.message; return }
  if (!studio.value.files.length && !idea.value.trim()) { error.value = '请先在左侧添加参考图，或写一句想要的画面。'; return }
  working.value = true
  try {
    // 和生成图片一样：没上传过的参考图先上传，记下素材编号，之后生成图片时不再重复上传。
    for (const file of planFiles.value) if (!file.assetId) file.assetId = await uploadImageReference(file.file)
    const body = { imageAssetIds: planFiles.value.map(file => file.assetId), style: styleName.value, idea: idea.value.trim(), previousId }
    // 网络中断后重试同一份请求沿用原编号，不会重复预占烟火。
    if (JSON.stringify(body) !== signature) { requestKey = `image-inspiration-${crypto.randomUUID()}`; signature = JSON.stringify(body) }
    const images = planFiles.value.map(file => file.preview)
    let batch = await imageInspirationApi.create({ requestKey, ...body })
    refreshBilling()
    batch = await settle(batch)
    if (!batch) return
    signature = ''
    if (batch.status === 'FAILED') { error.value = batch.errorMessage || '灵感方案没有完成，烟火已退回，请重试。'; return }
    result.value = batch
    resultImages.value = images
    resultInfo.value = { idea: body.idea, style: body.style, imageCount: images.length, createdAt: batch.createdAt }
    record.value = null
    appliedIndex.value = -1
    showResults.value = true
    loadRecords()
  } catch (e) {
    error.value = e.message || '灵感方案暂时失败，请稍后重试。'
  } finally {
    working.value = false
  }
}

// 回车出方案（要扣烟火）；输入法还在选字时的回车只是确认候选，不能当成提交。
const onIdeaEnter = event => {
  if (event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  run()
}

const openRecord = item => {
  if (working.value) return
  result.value = item
  resultImages.value = item.images.map(image => image.url)
  resultInfo.value = { idea: item.idea, style: item.style, imageCount: item.images.length, createdAt: item.createdAt }
  record.value = item
  appliedIndex.value = -1
  idea.value = item.idea || ''
  showResults.value = true
}
const resume = async id => {
  working.value = true
  let batch = null
  try { batch = await settle(await imageInspirationApi.get(id)) }
  catch { rememberPending(null) } // 读不到（例如换了账号）就不再接，灵感记录里仍能找到
  finally { working.value = false }
  if (disposed || !batch) return
  if (batch.status === 'FAILED') { error.value = batch.errorMessage || '灵感方案没有完成，烟火已退回，请重试。'; return }
  await loadRecords()
  const item = records.value.find(entry => entry.id === batch.id)
  if (item && !disposed) openRecord(item)
}

onMounted(() => {
  imageInspirationApi.config().then(data => { if (!disposed) config.value = data }).catch(() => {})
  loadRecords()
  const pending = pendingId()
  if (pending) resume(pending)
})
onBeforeUnmount(() => { disposed = true })

const showRecords = async () => {
  showResults.value = false
  await nextTick()
  recordsSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

// 「用这个」只填画面描述；记录里的方案按当初的参考图写，左侧图片不同就一起换回来。
const use = (plan, index) => {
  if (studio.value.locked) return
  const files = record.value ? inspirationRecordFiles(record.value, studio.value.files) : null
  if (files) {
    studio.value.files.forEach(file => { if (file.file) URL.revokeObjectURL(file.preview) })
    studio.value.files = files
  }
  studio.value.brief = plan.prompt
  appliedIndex.value = index
}
</script>

<template>
  <section class="poster-idea ip-panel" aria-label="灵感方案">
    <template v-if="!showResults || !result">
      <div class="ip-hero">
        <h2>说一句想要的画面，一方志给你三种画法。</h2>
        <p>按左侧的参考图和绘图风格出 3 个画面描述，选一个填进「画面描述」。不用也可以直接自己写。</p>
      </div>
      <div class="ip-composer">
        <div class="ip-context">
          <span class="ip-context-label">参考</span>
          <span v-for="(file, index) in planFiles" :key="file.preview" class="ip-chip has-thumb"><img :src="file.preview" alt="">图{{ index + 1 }}</span>
          <span v-if="!planFiles.length" class="ip-chip is-empty">还没有参考图，在左侧添加效果更好</span>
          <span class="ip-context-divider" aria-hidden="true" />
          <span class="ip-context-label">风格</span>
          <span class="ip-chip" :class="styleName ? 'is-accent' : 'is-empty'">{{ styleName || '还没选风格，三个方案各自搭配' }}</span>
          <button type="button" class="ip-text-button" @click="studio.workspaceTab = 'inspiration'">{{ styleName ? '更换' : '去挑选' }}</button>
        </div>
        <label for="poster-idea-input" class="sr-only">一句话说说想要什么</label>
        <textarea id="poster-idea-input" v-model="idea" class="ip-idea" rows="2" maxlength="500" placeholder="一句话说说想要什么，例如：新品香水上新，发小红书，安静高级一点" @keydown.enter.exact="onIdeaEnter" />
        <div class="ip-composer-foot">
          <small>每次出 3 个画面 · 换一批同价 · 失败不扣烟火</small>
          <button class="ip-primary" type="button" :disabled="working || unavailable" @click="run()"><Sparkles :size="16" :stroke-width="1.8" aria-hidden="true" />{{ working ? '正在出方案…' : '生成灵感方案' }}<span class="ip-cost"><YanhuoIcon :size="13" />{{ credits }} 烟火</span></button>
        </div>
      </div>
      <button v-if="result" type="button" class="ip-link" @click="showResults = true">回到上一批方案</button>
      <section ref="recordsSection" class="ip-section" aria-label="灵感记录">
        <div class="ip-section-head"><h3>灵感记录</h3><small>出过的方案都在这里，点开可以再看、再用</small></div>
        <div v-if="records.length" class="ip-records">
          <button v-for="item in records" :key="item.id" type="button" class="ip-record" :class="{ active: record?.id === item.id }" :disabled="working" @click="openRecord(item)">
            <span class="ip-record-thumbs" aria-hidden="true"><img v-for="image in item.images.filter(image => image.url).slice(0, 3)" :key="image.assetId" :src="image.url" alt=""><span v-if="!item.images.some(image => image.url)">没有参考图</span></span>
            <ol><li v-for="(plan, index) in item.plans" :key="index"><b>{{ videoPlanLetters[index] }}</b>{{ plan.title }}</li></ol>
            <small>{{ videoHistoryTime(item.createdAt) }}<template v-if="item.style || item.idea"> · {{ item.style || item.idea }}</template></small>
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
          <h2>{{ resultInfo?.idea || '按参考图出的灵感方案' }}</h2>
          <small v-if="resultMeta">{{ resultMeta }}</small>
        </div>
        <div class="ip-results-actions">
          <button type="button" class="ip-outline" :disabled="working" @click="showResults = false"><SlidersHorizontal :size="15" :stroke-width="1.8" aria-hidden="true" />重选</button>
          <button type="button" class="ip-primary" :disabled="working" @click="run(result.id)"><RefreshCw :size="15" :stroke-width="1.8" aria-hidden="true" />{{ working ? '正在换一批…' : '换一批' }}<span class="ip-cost"><YanhuoIcon :size="13" />{{ credits }} 烟火</span></button>
        </div>
      </header>
      <p v-if="missingRecordImages" class="ip-status">这批方案用过的 {{ missingRecordImages }} 张参考图已删除，描述里的图号可能对不上，用之前请核对。</p>
      <div class="ip-cards" :class="{ 'is-working': working }">
        <article v-for="(plan, index) in result.plans" :key="index" class="ip-card" :class="{ 'is-applied': appliedIndex === index }">
          <div class="ip-card-title"><span class="ip-letter">{{ videoPlanLetters[index] }}</span><h3>{{ plan.title }}</h3></div>
          <p v-if="plan.idea" class="ip-card-idea">{{ plan.idea }}</p>
          <ul v-if="plan.materials.length" class="ip-uses">
            <li v-for="item in plan.materials" :key="item.index" :class="{ 'no-thumb': !resultImages[item.index - 1] }"><img v-if="resultImages[item.index - 1]" :src="resultImages[item.index - 1]" alt="">图{{ item.index }} {{ item.use }}</li>
          </ul>
          <p class="ip-card-body">{{ plan.prompt }}</p>
          <button type="button" class="ip-use" :class="{ active: appliedIndex === index }" :disabled="studio.locked" @click="use(plan, index)"><template v-if="appliedIndex === index"><Check :size="15" :stroke-width="2" aria-hidden="true" />已填入画面描述</template><template v-else>用这个</template></button>
        </article>
      </div>
      <p class="ip-foot-note">选中的描述会填进左侧「画面描述」，可以再改字；左侧参考图和当初不一样时，会一起换回当初那几张。</p>
    </template>

    <p v-if="working" class="ip-status" role="status">正在看图出方案，通常 1 分钟左右，可以先回左侧继续编辑；离开页面再回来也会接着显示这一批。</p>
    <p v-if="unavailable" class="ip-status" role="status">灵感方案暂未开放，可以直接在左侧写画面描述。</p>
    <p v-if="error" class="ip-status is-error" role="alert">{{ error }}</p>
  </section>
</template>
