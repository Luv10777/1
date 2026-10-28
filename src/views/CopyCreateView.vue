<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ChevronLeft, Copy, History, Images, Lightbulb, Package, PenLine, Plus, RefreshCw, X } from 'lucide-vue-next'
import { copyApi, copyPlatforms, copyRewrites, copyStoreLevels, copyStyles, aiLevel, clockTime, copyFormFromBatch, copySegments, copyText,
  groupByDay, isCopyDone, isRewriting, missingFacts, platformLabel, validateCopyImage } from '../services/copywriting'
import { selectedStoreId, selectedStore } from '../stores/merchantContext'
import { auth } from '../stores/auth'
import { billing, refreshBilling, requireMembership } from '../stores/billing'
import '../copywriting.css'

const route = useRoute()
const router = useRouter()
const config = ref(null)
const voice = ref(null)
const form = ref(blankForm())
const batch = ref(null)
const selected = ref(0)
const submitting = ref(false)
const error = ref('')
const notice = ref('')
const whyOpen = ref(-1)
const rewriteOpen = ref(-1)
const copied = ref(-1)
const stamped = ref(-1)
const fileInput = ref(null)
const historyOpen = ref(false)
const historyFilter = ref('')
const records = ref([])
const historyLoading = ref(false)
const worksOpen = ref(false)
const works = ref([])
const worksLoading = ref(false)
const productsOpen = ref(false)
const storeProducts = ref([])
let pollTimer = null
let disposed = false

function blankForm() {
  return { platform: 'MOMENTS', style: '真诚日常', storeLevel: 'LIGHT', voiceOn: false, note: '', pictures: [], products: [] }
}

const styles = computed(() => config.value?.styles || copyStyles)
const maxImages = computed(() => config.value?.maxImages || 6)
const credits = computed(() => config.value?.credits ?? 1)
const store = computed(() => batch.value?.store || {})
const versions = computed(() => batch.value?.status === 'SUCCEEDED' ? batch.value.versions || [] : [])
const generating = computed(() => submitting.value || (batch.value && !isCopyDone(batch.value)))
const current = computed(() => versions.value[selected.value] || null)
const preview = computed(() => current.value ? {
  title: copySegments(current.value.title, store.value).map(s => s.text).join(''),
  body: copySegments(current.value.body, store.value).map(s => s.text).join(''),
  tags: (current.value.tags || []).join(' '),
} : null)
const previewImage = computed(() => (batch.value?.images || form.value.pictures).find(picture => picture.url)?.url || '')
const storeName = computed(() => store.value['店名'] || selectedStore.value?.name || '我的店')
const heading = computed(() => batch.value ? `${platformLabel(batch.value.platform)} · ${batch.value.style}` : '写好的文案')
const voiceReady = computed(() => voice.value?.status === 'READY' || Boolean(voice.value?.brandStyle))
const voiceSub = computed(() => {
  if (!voice.value) return ''
  if (voice.value.status === 'READY') return `已学会 · ${voice.value.samples.length} 条文案`
  if (voice.value.status === 'LEARNING') return '正在学，稍后可用'
  if (voice.value.brandStyle) return '按品牌库的语言风格'
  return '还没学，去首页喂几条'
})
const historyGroups = computed(() => groupByDay(records.value))
const rewritesLeft = computed(() => batch.value ? Math.max(0, batch.value.rewriteLimit - batch.value.rewriteCount) : 0)
const canGenerate = computed(() => config.value?.configured && selectedStoreId.value && !generating.value
  && (form.value.pictures.length || form.value.note.trim() || form.value.products.length))
const sessionKey = computed(() => `copy-batch-${selectedStoreId.value}`)

function segments(text) { return copySegments(text, store.value) }
function remember(id) { try { id ? sessionStorage.setItem(sessionKey.value, String(id)) : sessionStorage.removeItem(sessionKey.value) } catch { /* 无痕模式不记也能用 */ } }
function remembered() { try { return Number(sessionStorage.getItem(sessionKey.value)) || null } catch { return null } }

async function loadConfig() {
  if (!auth.isAuthenticated) return
  try { config.value = await copyApi.config() } catch (e) { error.value = e.message }
}

async function loadVoice() {
  if (!selectedStoreId.value || !auth.isAuthenticated) { voice.value = null; return }
  try {
    voice.value = await copyApi.voice(selectedStoreId.value)
    if (!voiceReady.value) form.value.voiceOn = false
  } catch { voice.value = null }
}

function stopPolling() { if (pollTimer) { clearTimeout(pollTimer); pollTimer = null } }

async function poll(id) {
  stopPolling()
  try {
    const view = await copyApi.get(id)
    if (disposed || batch.value?.id !== id) return
    batch.value = view
    if (!isCopyDone(view) || isRewriting(view)) { pollTimer = setTimeout(() => poll(id), 2000); return }
    if (view.status === 'SUCCEEDED') remember(id)
    refreshBilling()
  } catch (e) {
    if (!disposed) { error.value = e.message; pollTimer = setTimeout(() => poll(id), 4000) }
  }
}

async function openBatch(id, { restore = true } = {}) {
  stopPolling()
  error.value = ''
  try {
    const view = await copyApi.get(id)
    batch.value = view
    selected.value = 0
    whyOpen.value = rewriteOpen.value = copied.value = stamped.value = -1
    if (restore) form.value = copyFormFromBatch(view)
    remember(id)
    if (!isCopyDone(view) || isRewriting(view)) poll(id)
  } catch (e) {
    error.value = e.message
    remember(null)
  }
}

function addFiles(event) {
  const files = [...(event.target.files || [])]
  event.target.value = ''
  for (const file of files) {
    if (form.value.pictures.length >= maxImages.value) { error.value = `图片最多 ${maxImages.value} 张`; break }
    const problem = validateCopyImage(file)
    if (problem) { error.value = problem; continue }
    form.value.pictures.push({ key: `file-${crypto.randomUUID()}`, kind: 'ASSET', id: null, name: file.name, url: URL.createObjectURL(file), file })
  }
}

function removePicture(key) { form.value.pictures = form.value.pictures.filter(picture => picture.key !== key) }

async function openWorks() {
  if (!auth.requireLogin()) return
  worksOpen.value = true
  worksLoading.value = true
  try { works.value = (await copyApi.works()).items || [] } catch (e) { error.value = e.message } finally { worksLoading.value = false }
}

function pickWork(work) {
  const key = `WORK-${work.id}`
  if (form.value.pictures.some(picture => picture.key === key)) { removePicture(key); return }
  if (form.value.pictures.length >= maxImages.value) return
  form.value.pictures.push({ key, kind: 'WORK', id: work.id, name: work.title, url: work.url, file: null })
}

async function openProducts() {
  if (!auth.requireLogin() || !selectedStoreId.value) return
  productsOpen.value = true
  try { storeProducts.value = (await copyApi.products(selectedStoreId.value)).items || [] } catch (e) { error.value = e.message }
}

function toggleProduct(product) {
  if (form.value.products.some(item => item.id === product.id)) form.value.products = form.value.products.filter(item => item.id !== product.id)
  else if (form.value.products.length < (config.value?.maxProducts || 3)) form.value.products.push({ id: product.id, name: product.name })
}

async function generate(again = false) {
  if (!canGenerate.value && !again) return
  error.value = ''
  notice.value = ''
  submitting.value = true
  try {
    await requireMembership()
    const imageAssetIds = []
    for (const picture of form.value.pictures.filter(p => p.kind === 'ASSET')) {
      if (!picture.id) picture.id = await copyApi.uploadImage(picture.file)
      imageAssetIds.push(picture.id)
    }
    const view = await copyApi.create({
      requestKey: `copy-${crypto.randomUUID()}`,
      storeId: selectedStoreId.value,
      platform: form.value.platform,
      style: form.value.style,
      storeLevel: form.value.storeLevel,
      voiceOn: form.value.voiceOn,
      imageAssetIds,
      workItemIds: form.value.pictures.filter(p => p.kind === 'WORK').map(p => p.id),
      productIds: form.value.products.map(p => p.id),
      note: form.value.note.trim() || null,
      previousBatchId: again && batch.value?.status === 'SUCCEEDED' ? batch.value.id : null,
    })
    batch.value = view
    selected.value = 0
    whyOpen.value = rewriteOpen.value = copied.value = stamped.value = -1
    remember(view.id)
    poll(view.id)
  } catch (e) {
    error.value = e.message
  } finally {
    submitting.value = false
  }
}

/** 「换一批」沿用上一批的设置；左栏改过就按新设置重新生成。 */
function regenerate() { if (batch.value) generate(true) }

async function rewrite(index, action) {
  rewriteOpen.value = -1
  error.value = ''
  try {
    batch.value = await copyApi.rewrite(batch.value.id, index, action)
    poll(batch.value.id)
  } catch (e) { error.value = e.message }
}

/** 复制成功返回 true；浏览器不让写剪贴板时返回 false，由调用方决定怎么提示。 */
async function writeClipboard(version) {
  try { await navigator.clipboard.writeText(copyText(version, store.value)) } catch { return false }
  return true
}

function missingHint(version) {
  const missing = missingFacts(version, store.value)
  return missing.length ? `还有 ${missing.join('、')} 要你补上再发。` : '可以去发了。'
}

async function copyVersion(index) {
  selected.value = index
  error.value = ''
  if (await writeClipboard(versions.value[index])) {
    copied.value = index
    notice.value = `已复制。${missingHint(versions.value[index])}`
  } else error.value = '浏览器没有允许复制，请手动选中文字复制'
}

async function finalize(index) {
  selected.value = index
  error.value = ''
  try {
    await copyApi.finalize(batch.value.id, index)
    batch.value = { ...batch.value, finalized: [...new Set([...(batch.value.finalized || []), index])] }
    stamped.value = index
    if (await writeClipboard(versions.value[index])) {
      copied.value = index
      notice.value = `已收进门店志，也复制好了。${missingHint(versions.value[index])}`
    } else notice.value = '已收进门店志。浏览器没有允许自动复制，请手动选中文字复制。'
  } catch (e) { error.value = e.message }
}

async function loadHistory() {
  if (!selectedStoreId.value || !auth.isAuthenticated) return
  historyLoading.value = true
  try { records.value = (await copyApi.history(selectedStoreId.value, { platform: historyFilter.value })).items || [] }
  catch (e) { error.value = e.message }
  finally { historyLoading.value = false }
}

function toggleHistory() {
  if (!historyOpen.value && !auth.requireLogin()) return
  historyOpen.value = !historyOpen.value
  if (historyOpen.value) loadHistory()
}

function openRecord(record) {
  historyOpen.value = false
  openBatch(record.id)
}

function onKey(event) { if (event.key === 'Escape') { historyOpen.value = worksOpen.value = productsOpen.value = false; rewriteOpen.value = -1 } }

watch(historyFilter, loadHistory)
watch(selectedStoreId, (id, previous) => {
  if (previous && id !== previous) { stopPolling(); batch.value = null; form.value = blankForm(); records.value = [] }
  loadVoice()
  if (!batch.value && remembered()) openBatch(remembered())
})

onMounted(async () => {
  window.addEventListener('keydown', onKey)
  if (copyPlatforms.some(p => p.id === route.query.platform)) form.value.platform = route.query.platform
  if (typeof route.query.note === 'string') form.value.note = route.query.note.slice(0, 500)
  await loadConfig()
  await loadVoice()
  const requested = Number(route.query.batch) || remembered()
  if (requested && auth.isAuthenticated) openBatch(requested)
  if (route.query.history) { historyOpen.value = true; loadHistory(); router.replace({ query: { ...route.query, history: undefined } }) }
})
onBeforeUnmount(() => { disposed = true; stopPolling(); window.removeEventListener('keydown', onKey) })
</script>

<template>
  <div class="cw-page">
    <main class="cw-work">
      <aside class="cw-card cw-form" aria-label="文案设置">
        <div class="cw-form-head">
          <RouterLink to="/copy" class="cw-back"><ChevronLeft :size="14" :stroke-width="2" aria-hidden="true" />文案创作</RouterLink>
          <h1>写文案</h1>
        </div>

        <section class="cw-field">
          <span class="cw-label">发到哪</span>
          <div class="cw-segmented" role="group" aria-label="发布平台">
            <button
              v-for="platform in copyPlatforms" :key="platform.id" type="button" :aria-pressed="form.platform === platform.id"
              :class="{ active: form.platform === platform.id }" @click="form.platform = platform.id"
            >
              {{ platform.label }}
            </button>
          </div>
        </section>

        <section class="cw-field">
          <span class="cw-label">发什么</span>
          <div class="cw-pictures">
            <div v-for="picture in form.pictures" :key="picture.key" class="cw-picture">
              <img v-if="picture.url" :src="picture.url" :alt="picture.name">
              <span v-else class="cw-picture-gone">已删除</span>
              <button type="button" class="cw-picture-remove" :aria-label="`移除 ${picture.name}`" @click="removePicture(picture.key)"><X :size="12" :stroke-width="2.4" aria-hidden="true" /></button>
            </div>
            <button v-if="form.pictures.length < maxImages" type="button" class="cw-picture-add" aria-label="上传图片" @click="fileInput?.click()"><Plus :size="20" :stroke-width="1.6" aria-hidden="true" /></button>
            <input ref="fileInput" type="file" accept="image/jpeg,image/png,image/webp" multiple hidden @change="addFiles">
          </div>
          <label for="copy-note" class="cw-sr-only">想说的话</label>
          <textarea id="copy-note" v-model="form.note" class="cw-textarea" rows="3" maxlength="500" placeholder="想说点什么？可以不填"></textarea>
          <div class="cw-attach">
            <button type="button" @click="openWorks"><Images :size="15" :stroke-width="1.7" aria-hidden="true" />从作品库选</button>
            <button type="button" @click="openProducts"><Package :size="15" :stroke-width="1.7" aria-hidden="true" />关联商品</button>
          </div>
          <div v-if="form.products.length" class="cw-chips">
            <span v-for="product in form.products" :key="product.id" class="cw-chip">{{ product.name }}
              <button type="button" :aria-label="`取消关联 ${product.name}`" @click="toggleProduct(product)"><X :size="12" :stroke-width="2" aria-hidden="true" /></button>
            </span>
          </div>
        </section>

        <section class="cw-field">
          <span class="cw-label">风格</span>
          <div class="cw-styles" role="group" aria-label="文案风格">
            <button
              v-for="style in styles" :key="style.name" type="button" :title="style.description" :aria-pressed="form.style === style.name"
              :class="{ active: form.style === style.name }" @click="form.style = style.name"
            >
              {{ style.name }}
            </button>
          </div>
        </section>

        <section class="cw-field">
          <span class="cw-label">带上店铺</span>
          <div class="cw-segmented" role="group" aria-label="带店程度">
            <button
              v-for="level in copyStoreLevels" :key="level.id" type="button" :aria-pressed="form.storeLevel === level.id"
              :class="{ active: form.storeLevel === level.id }" @click="form.storeLevel = level.id"
            >
              {{ level.label }}
            </button>
          </div>
        </section>

        <button
          type="button" role="switch" class="cw-voice" :class="{ on: form.voiceOn }" :aria-checked="form.voiceOn" :disabled="!voiceReady"
          @click="form.voiceOn = !form.voiceOn"
        >
          <span><strong>学我口吻</strong><small>{{ voiceSub }}</small></span>
          <i aria-hidden="true"><b></b></i>
        </button>
        <RouterLink v-if="voice && !voiceReady" to="/copy#voice" class="cw-voice-link">去喂几条我发过的文案</RouterLink>

        <div v-if="error" class="cw-error" role="alert">{{ error }}</div>
        <p v-if="config && !config.configured" class="cw-hint">文案模型尚未配置，暂时不能生成。</p>
        <p v-else-if="!selectedStoreId && auth.isAuthenticated" class="cw-hint">先选一家门店，文案会用到门店的名字和地址。</p>
        <RouterLink v-if="billing.summary && !billing.summary.memberActive" to="/billing" class="cw-hint cw-hint-link">请先开通会员</RouterLink>
        <button type="button" class="cw-generate" :disabled="!canGenerate" @click="generate(false)">
          {{ generating ? '正在写…' : `生成文案 · ${credits} 烟火` }}
        </button>
      </aside>

      <section class="cw-results" aria-label="生成结果" :aria-busy="Boolean(generating)">
        <div class="cw-results-head">
          <h2>{{ heading }}</h2>
          <div>
            <button v-if="batch?.status === 'SUCCEEDED'" type="button" class="cw-ghost" :disabled="generating" @click="regenerate">
              <RefreshCw :size="15" :stroke-width="1.7" aria-hidden="true" />换一批
            </button>
            <button type="button" class="cw-soft" :aria-expanded="historyOpen" aria-controls="cw-history" @click="toggleHistory">
              <History :size="15" :stroke-width="1.7" aria-hidden="true" />创作记录
            </button>
          </div>
        </div>

        <p v-if="notice" class="cw-notice" role="status">{{ notice }}</p>

        <div v-if="!batch" class="cw-card cw-state">
          <img src="/images/brand/yifangzhi-mark.png" alt="" class="cw-mark">
          <h3>从一张图或一句话开始</h3>
          <p>选好平台和风格，一次写 3 条不同写法的文案，每条都告诉你为什么这么写。</p>
        </div>
        <div v-else-if="batch.status === 'FAILED'" class="cw-card cw-state">
          <h3>这次没写成</h3>
          <p>{{ batch.errorMessage || '请重新生成。' }}</p>
        </div>
        <div v-else-if="!isCopyDone(batch)" class="cw-card cw-state" role="status" aria-live="polite">
          <span class="cw-spinner" aria-hidden="true"></span>
          <h3>正在写 3 条文案</h3>
          <p>看图、写稿、逐条检查有没有编造的数字和优惠，一般十几秒。离开页面也不会丢。</p>
        </div>

        <article v-for="(version, index) in versions" :key="index" class="cw-card cw-version" :class="{ selected: selected === index }">
          <header>
            <button type="button" class="cw-version-pick" :aria-pressed="selected === index" @click="selected = index">
              <span class="cw-no">0{{ index + 1 }}</span><span>{{ version.angle }}</span>
            </button>
            <span class="cw-ai" :class="{ high: aiLevel(version.aiScore).high }" :title="version.aiReason">
              AI 味
              <span aria-hidden="true"><i v-for="n in 5" :key="n" :class="{ on: n <= aiLevel(version.aiScore).value }"></i></span>
              <strong>{{ aiLevel(version.aiScore).label }}</strong>
            </span>
          </header>

          <div class="cw-copy" :class="{ busy: version.rewriting }">
            <p v-if="version.title" class="cw-copy-title"><template v-for="(s, i) in segments(version.title)" :key="i"><span :class="`seg-${s.kind}`" :title="s.kind === 'store' ? `取自门店资料：${s.label}` : s.kind === 'missing' ? '资料里没有，需要你补上' : null">{{ s.text }}</span></template></p>
            <p class="cw-copy-body"><template v-for="(s, i) in segments(version.body)" :key="i"><span :class="`seg-${s.kind}`" :title="s.kind === 'store' ? `取自门店资料：${s.label}` : s.kind === 'missing' ? '资料里没有，需要你补上' : null">{{ s.text }}</span></template></p>
            <p v-if="version.tags?.length" class="cw-copy-tags">{{ version.tags.join('  ') }}</p>
            <span v-if="version.rewriting" class="cw-rewriting" role="status">正在改写…</span>
          </div>
          <p v-if="version.rewriteError" class="cw-rewrite-error">{{ version.rewriteError }}</p>
          <p v-if="whyOpen === index" class="cw-why">{{ version.why || '这一条换了一种写法，可以和另外两条对比着选。' }}</p>

          <div v-if="rewriteOpen === index" class="cw-rewrite-menu" role="group" aria-label="改写方式">
            <button v-for="option in copyRewrites" :key="option.id" type="button" :disabled="!rewritesLeft" @click="rewrite(index, option.id)">{{ option.label }}</button>
            <small>还能改 {{ rewritesLeft }} 次</small>
          </div>

          <footer>
            <div>
              <button type="button" class="cw-text-button" :aria-expanded="whyOpen === index" @click="whyOpen = whyOpen === index ? -1 : index">
                <Lightbulb :size="15" :stroke-width="1.7" aria-hidden="true" />{{ whyOpen === index ? '收起思路' : '为什么这么写' }}
              </button>
              <button v-if="aiLevel(version.aiScore).value >= 3 && rewritesLeft" type="button" class="cw-text-button accent" :disabled="isRewriting(batch)" @click="rewrite(index, 'DE_AI')">去 AI 味</button>
            </div>
            <div>
              <button
                type="button" class="cw-icon-button" aria-label="改写这条" :aria-expanded="rewriteOpen === index" :disabled="isRewriting(batch)"
                @click="rewriteOpen = rewriteOpen === index ? -1 : index"
              >
                <PenLine :size="16" :stroke-width="1.7" aria-hidden="true" />
              </button>
              <button type="button" class="cw-outline" :disabled="Boolean(version.rewriting)" @click="copyVersion(index)">
                <Copy :size="14" :stroke-width="1.8" aria-hidden="true" />{{ copied === index ? '已复制' : '复制' }}
              </button>
              <button type="button" class="cw-dark" :disabled="Boolean(version.rewriting)" @click="finalize(index)">{{ batch.finalized?.includes(index) ? '已定稿' : '定稿' }}</button>
            </div>
          </footer>
          <span v-if="batch.finalized?.includes(index)" class="cw-seal" :class="{ fresh: stamped === index }" aria-label="已定稿">定<br>稿</span>
        </article>
      </section>

      <aside class="cw-phone-col" aria-label="发出去的样子">
        <div class="cw-phone-head"><h2>发出去的样子</h2><span v-if="current">{{ platformLabel(batch.platform) }} · 第 {{ selected + 1 }} 条</span></div>
        <div class="cw-phone">
          <div v-if="!preview" class="cw-phone-empty">选中一条文案，<br>这里显示发出去的样子</div>
          <div v-else-if="batch.platform === 'MOMENTS'" class="cw-moments">
            <div class="cw-moments-cover"><img v-if="previewImage" :src="previewImage" alt=""><span>{{ storeName.slice(0, 1) }}</span></div>
            <div class="cw-moments-post">
              <span class="cw-avatar">{{ storeName.slice(0, 1) }}</span>
              <div>
                <strong>{{ storeName }}</strong>
                <p>{{ preview.body }}</p>
                <img v-if="previewImage" :src="previewImage" alt="">
                <small>刚刚</small>
              </div>
            </div>
          </div>
          <div v-else-if="batch.platform === 'DOUYIN'" class="cw-douyin">
            <img v-if="previewImage" :src="previewImage" alt="">
            <div class="cw-douyin-actions" aria-hidden="true"><span>{{ storeName.slice(0, 1) }}</span><i></i><i></i><i></i></div>
            <div class="cw-douyin-text">
              <strong>@{{ storeName }}</strong>
              <span class="title">{{ preview.title }}</span>
              <span class="body">{{ preview.body }}</span>
              <span class="tags">{{ preview.tags }}</span>
            </div>
          </div>
          <div v-else class="cw-xhs">
            <div>
              <div class="cw-xhs-card">
                <img v-if="previewImage" :src="previewImage" alt="">
                <div v-else class="cw-xhs-text-cover">{{ preview.title }}</div>
                <strong>{{ preview.title }}</strong>
                <small><i></i>{{ storeName }}</small>
              </div>
              <i class="cw-xhs-ghost tall"></i>
            </div>
            <div><i class="cw-xhs-ghost"></i><i class="cw-xhs-ghost tall"></i><i class="cw-xhs-ghost"></i></div>
          </div>
        </div>
      </aside>
    </main>

    <Teleport v-if="historyOpen" to=".app-frame">
      <div class="cw-drawer-backdrop" aria-hidden="true" @click="historyOpen = false"></div>
      <aside id="cw-history" class="cw-drawer" aria-label="创作记录">
        <div class="cw-drawer-head">
          <h2>创作记录</h2>
          <button type="button" class="cw-icon-button" aria-label="关闭创作记录" @click="historyOpen = false"><X :size="18" :stroke-width="1.8" aria-hidden="true" /></button>
        </div>
        <div class="cw-segmented small" role="group" aria-label="按平台筛选">
          <button type="button" :aria-pressed="!historyFilter" :class="{ active: !historyFilter }" @click="historyFilter = ''">全部</button>
          <button
            v-for="platform in copyPlatforms" :key="platform.id" type="button" :aria-pressed="historyFilter === platform.id"
            :class="{ active: historyFilter === platform.id }" @click="historyFilter = platform.id"
          >
            {{ platform.label }}
          </button>
        </div>
        <div class="cw-drawer-list">
          <p v-if="historyLoading && !records.length" class="cw-drawer-empty">正在读取…</p>
          <p v-else-if="!records.length" class="cw-drawer-empty">{{ historyFilter ? '这个平台还没有创作记录' : '还没有创作记录' }}</p>
          <div v-for="group in historyGroups" :key="group.key" class="cw-drawer-group">
            <span>{{ group.label }}</span>
            <button v-for="record in group.items" :key="record.id" type="button" class="cw-record" :class="{ current: batch?.id === record.id }" @click="openRecord(record)">
              <img v-if="record.thumbnail" :src="record.thumbnail" alt="">
              <span v-else class="cw-record-text">{{ record.hasImage ? '图' : '文' }}</span>
              <span class="cw-record-main">
                <span :class="{ failed: record.status === 'FAILED' }">{{ record.status === 'FAILED' ? `没写成，${credits} 烟火已退回` : !isCopyDone(record) ? '正在写…' : record.firstLine || '（空）' }}</span>
                <small>{{ platformLabel(record.platform) }} · {{ record.style }} · {{ clockTime(record.createdAt) }}<template v-if="record.creatorName"> · {{ record.creatorName }}</template></small>
              </span>
              <i v-if="record.finalized" class="cw-record-seal" aria-label="有定稿">定</i>
              <em v-if="batch?.id === record.id">当前</em>
            </button>
          </div>
        </div>
      </aside>
    </Teleport>

    <Teleport v-if="worksOpen" to=".app-frame">
      <div class="cw-modal-backdrop" @click.self="worksOpen = false">
        <div class="cw-modal" role="dialog" aria-modal="true" aria-label="从作品库选图片">
          <div class="cw-drawer-head"><h2>从作品库选</h2><button type="button" class="cw-icon-button" aria-label="关闭" @click="worksOpen = false"><X :size="18" :stroke-width="1.8" aria-hidden="true" /></button></div>
          <p v-if="worksLoading" class="cw-drawer-empty">正在读取…</p>
          <p v-else-if="!works.length" class="cw-drawer-empty">作品库里还没有图片，先去图片创作做几张。</p>
          <div v-else class="cw-works">
            <button
              v-for="work in works" :key="work.id" type="button" :aria-pressed="form.pictures.some(p => p.key === `WORK-${work.id}`)"
              :class="{ active: form.pictures.some(p => p.key === `WORK-${work.id}`) }" @click="pickWork(work)"
            >
              <img :src="work.url" :alt="work.title"><span>{{ work.title }}</span>
            </button>
          </div>
          <button type="button" class="cw-generate small" @click="worksOpen = false">好了</button>
        </div>
      </div>
    </Teleport>

    <Teleport v-if="productsOpen" to=".app-frame">
      <div class="cw-modal-backdrop" @click.self="productsOpen = false">
        <div class="cw-modal" role="dialog" aria-modal="true" aria-label="关联商品">
          <div class="cw-drawer-head"><h2>关联商品</h2><button type="button" class="cw-icon-button" aria-label="关闭" @click="productsOpen = false"><X :size="18" :stroke-width="1.8" aria-hidden="true" /></button></div>
          <p class="cw-hint">最多 3 个。价格、优惠和卖点会作为文案可以引用的资料。</p>
          <p v-if="!storeProducts.length" class="cw-drawer-empty">这家门店还没有商品。</p>
          <div class="cw-product-list">
            <button
              v-for="product in storeProducts" :key="product.id" type="button" :aria-pressed="form.products.some(p => p.id === product.id)"
              :class="{ active: form.products.some(p => p.id === product.id) }" @click="toggleProduct(product)"
            >
              <span>{{ product.name }}</span><small v-if="product.price != null">¥{{ product.price }}</small>
            </button>
          </div>
          <button type="button" class="cw-generate small" @click="productsOpen = false">好了</button>
        </div>
      </div>
    </Teleport>
  </div>
</template>
