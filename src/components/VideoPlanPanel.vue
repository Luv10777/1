<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Film, RefreshCw, Sparkles } from 'lucide-vue-next'
import YanhuoIcon from './YanhuoIcon.vue'
import { billing, refreshBilling, requireMembership } from '../stores/billing'
import { isVideoPlanDone, videoPlanApi, videoPlanForm, videoPlanLetters, videoPlanSeconds, videoPlanStyles } from '../services/videoPlans'

// 视频工作台右侧「视频方案」：可选功能，出 3 个版本后「用这个」填进左侧表单，商家仍可自己写。
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
let requestKey = ''
let signature = ''
let disposed = false

const member = computed(() => billing.summary?.memberActive && billing.summary?.plan?.videoEnabled)
const credits = computed(() => config.value?.credits ?? 1)
const styleText = computed(() => (customOpen.value ? customStyle.value : style.value).trim())
const unavailable = computed(() => config.value && !config.value.configured)

onMounted(() => { videoPlanApi.config().then(data => { if (!disposed) config.value = data }).catch(() => {}) })
onBeforeUnmount(() => { disposed = true })

const pickStyle = value => { customOpen.value = false; style.value = style.value === value ? '' : value }
const openCustom = () => { customOpen.value = true; style.value = '' }
const wait = ms => new Promise(resolve => window.setTimeout(resolve, ms))

const run = async (previousPlanId = null) => {
  if (working.value || unavailable.value) return
  error.value = ''
  try { await requireMembership() }
  catch (e) { error.value = e.message; return }
  if (!props.images.length && !idea.value.trim()) { error.value = '请先在左侧添加参考图，或写一句想要的效果。'; return }
  working.value = true
  try {
    const imageAssetIds = await Promise.all(props.images.map(entry => props.upload(entry, 'IMAGE')))
    const body = { imageAssetIds, referenceVideo: Boolean(props.video), style: styleText.value, idea: idea.value.trim(), previousPlanId }
    // 网络中断后重试同一份请求沿用原编号，不会重复预占烟火。
    if (JSON.stringify(body) !== signature) { requestKey = `video-plan-${crypto.randomUUID()}`; signature = JSON.stringify(body) }
    const images = props.images.map(entry => entry.url)
    let plan = await videoPlanApi.create({ requestKey, ...body })
    refreshBilling()
    while (!isVideoPlanDone(plan)) {
      await wait(2000)
      if (disposed) return
      plan = await videoPlanApi.get(plan.id)
    }
    signature = ''
    refreshBilling()
    if (plan.status === 'FAILED') { error.value = plan.errorMessage || '出方案没有完成，烟火已退回，请重试。'; return }
    result.value = plan
    resultImages.value = images
    appliedIndex.value = -1
    showResults.value = true
  } catch (e) {
    error.value = e.message || '出方案暂时失败，请稍后重试。'
  } finally {
    working.value = false
  }
}

const use = (plan, index) => {
  if (props.locked) return
  appliedIndex.value = index
  emit('apply', videoPlanForm(plan, { maxDuration: props.maxDuration, ratios: props.ratios }), videoPlanLetters[index])
}
</script>

<template>
  <section class="vp-panel" aria-label="视频方案">
    <template v-if="!showResults || !result">
      <p class="vp-intro">按你的素材和想法出 3 个版本的提示词和分镜，选一个填进描述框。不用也可以直接在左侧自己写。</p>
      <section class="vp-section">
        <strong class="vp-heading">素材<small>AI 会判断每张图怎么用，写进提示词</small></strong>
        <div v-if="images.length || video" class="vp-materials">
          <figure v-for="(entry, index) in images" :key="entry.id"><img v-if="entry.url" :src="entry.url" :alt="entry.name"><figcaption>图{{ index + 1 }}</figcaption></figure>
          <figure v-if="video" class="is-video"><Film :size="18" :stroke-width="1.6" aria-hidden="true" /><figcaption>参考视频 · 只参考运镜</figcaption></figure>
        </div>
        <p v-else class="vp-muted">还没有参考图，也可以只按一句话出方案；在左侧添加商品图效果更好。</p>
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
    </template>

    <template v-else>
      <div class="vp-results-head"><p>选一个方案，会填进描述框，可以再改字。</p><button type="button" class="vp-link" :disabled="working" @click="showResults = false">改设置</button></div>
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

    <p v-if="working" class="vp-status" role="status">正在看图出方案，通常 20 秒左右，可以先回左侧继续编辑。</p>
    <p v-if="unavailable" class="vp-status" role="status">出方案暂未开放，可以直接在左侧写描述。</p>
    <p v-if="error" class="vp-status is-error" role="alert">{{ error }}</p>
    <RouterLink v-if="billing.summary && !member" to="/billing" class="generation-billing-link vp-billing">{{ billing.summary.memberActive ? '当前套餐未开放视频创作' : '请先开通会员' }}</RouterLink>
  </section>
</template>
