<script setup>
import VideoMentionInput from '../components/VideoMentionInput.vue'
import { billingWalletLabel } from '../domain/billing'
import { nextTick, ref } from 'vue'
import { Check, ChevronLeft, Film, RefreshCw, SlidersHorizontal, Sparkles } from 'lucide-vue-next'
import YanhuoIcon from './YanhuoIcon.vue'
import { videoPlanLetters, videoPlanSeconds, videoPlanStyles } from '../services/videoPlans'
import { videoHistoryTime } from '../services/videoConversations'
import { FREE_SCENE, videoSceneCover, videoSceneKey, videoSceneName } from '../services/videoScenes'
import { useVideoPlan } from '../composables/useVideoPlan'
import '../inspiration-panel.css'

// 视频工作台右侧「视频方案」：可选功能，出 3 个版本后「用这个」填进左侧表单，商家仍可自己写。
// 逻辑在 useVideoPlan 里，新手版对话页也用它。
const props = defineProps({
  images: { type: Array, default: () => [] },
  video: { type: Object, default: null },
  upload: { type: Function, required: true },
  maxDuration: { type: Number, default: 30 },
  duration: { type: Number, default: 10 },
  ratio: { type: String, default: '' },
  brief: { type: String, default: '' },
  ratios: { type: Array, default: () => [] },
  locked: Boolean,
  // 工作台「场景」一栏选的场景；出方案时参考它，「换一批」由服务端沿用上一批的场景。
  scene: { type: String, default: FREE_SCENE },
  scenes: { type: Array, default: () => [] },
})
// pick-scene：打开工作台的「选择创作场景」弹窗，选中后由工作台把场景传回来。
const emit = defineEmits(['apply', 'scene', 'pick-scene'])
const {
  style, customStyle, customOpen, idea, working, error, clarification, answer, phaseLabel, result, showResults,
  appliedIndex, resultImages, resultInfo, record, records, recordsPage, recordsHasMore, recordsLoading, recordsError,
  credits, unavailable, planImages, missingRecordImages, currentScene, resultMeta,
  loadRecords, pickStyle, openCustom, run, onIdeaEnter, openRecord, use,
} = useVideoPlan(props, emit)
const recordsSection = ref(null)

const showRecords = async () => {
  showResults.value = false
  await nextTick()
  recordsSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
</script>

<template>
  <section class="vp-panel ip-panel" aria-label="视频方案">
    <template v-if="!showResults || !result">
      <div class="ip-composer">
        <div class="ip-context">
          <span class="ip-context-label">素材</span>
          <span v-for="(entry, index) in planImages.slice(0, 6)" :key="entry.id" class="ip-chip has-thumb"><img v-if="entry.url" :src="entry.url" alt="">图{{ index + 1 }}</span>
          <span v-if="planImages.length > 6" class="ip-chip">+{{ planImages.length - 6 }} 张</span>
          <span v-if="video" class="ip-chip has-thumb"><span class="ip-chip-icon"><Film :size="15" :stroke-width="1.6" aria-hidden="true" /></span>参考视频 · 生成时使用</span>
          <span v-if="!planImages.length && !video" class="ip-chip is-empty">还没有素材，在左侧添加商品图效果更好</span>
        </div>
        <p v-if="images.length > planImages.length" class="ip-muted">方案仅参考前 {{ planImages.length }} 张图片，生成视频时仍使用全部素材。</p>
        <div v-if="currentScene" class="ip-styles">
          <span class="ip-context-label">场景</span>
          <button type="button" class="has-thumb" :class="{ active: videoSceneKey(scene) !== FREE_SCENE }" aria-haspopup="dialog" @click="emit('pick-scene')"><img v-if="videoSceneCover(currentScene.key)" :src="videoSceneCover(currentScene.key)" alt="">{{ currentScene.name }}</button>
          <button type="button" class="is-green" aria-haspopup="dialog" @click="emit('pick-scene')">{{ videoSceneKey(scene) === FREE_SCENE ? '选择场景' : '更换场景' }}</button>
        </div>
        <div class="ip-styles" role="group" aria-label="风格，可不选">
          <span class="ip-context-label">风格</span>
          <button v-for="item in videoPlanStyles" :key="item" type="button" :aria-pressed="style === item" :class="{ active: style === item }" @click="pickStyle(item)">{{ item }}</button>
          <button type="button" class="is-green" :aria-pressed="customOpen" @click="openCustom">自己填</button>
          <input v-if="customOpen" v-model="customStyle" maxlength="40" aria-label="自己填写风格" placeholder="例如：复古港风、清冷极简">
        </div>
        <label for="vp-idea" class="sr-only">一句话说说想要什么</label>
        <VideoMentionInput id="vp-idea" v-model="idea" :images="planImages" class="ip-idea" rows="2" maxlength="500" placeholder="一句话说说想要什么，例如：新品香水上新，发抖音，安静高级一点" @keydown.enter.exact="onIdeaEnter" />
        <section v-if="clarification" aria-label="补充剧本信息">
          <p class="ip-status">为了把活动卖点写清楚，需要补充一项信息。本次尚未生成剧本，预占烟火已退回。</p>
          <label for="vp-answer">{{ clarification.question }}</label>
          <VideoMentionInput id="vp-answer" v-model="answer" :images="planImages" class="ip-idea" rows="3" maxlength="1000" :disabled="working" placeholder="填写实际活动信息；暂未确定的内容也可以说明" />
        </section>
        <div class="ip-composer-foot">
          <small>每次出 3 个版本 · 换一批同价 · 失败不扣烟火</small>
          <button class="ip-primary" type="button" :disabled="working || unavailable" @click="run()"><Sparkles :size="16" :stroke-width="1.8" aria-hidden="true" />{{ working ? '正在出方案…' : clarification ? '补充并生成剧本' : '生成剧本' }}<span class="ip-cost"><YanhuoIcon :size="13" />{{ credits }} 烟火</span></button>
        </div>
      </div>
      <button v-if="result" type="button" class="ip-link" @click="showResults = true">回到上一批方案</button>
      <section ref="recordsSection" class="ip-section" aria-label="灵感记录">
        <div class="ip-section-head"><h3>灵感记录</h3><small>出过的方案都在这里，点开可以再看、再用</small></div>
        <div v-if="records.length" class="ip-records">
          <button v-for="item in records" :key="item.id" type="button" class="ip-record" :class="{ active: record?.id === item.id }" :disabled="working" @click="openRecord(item)">
            <span class="ip-record-thumbs" aria-hidden="true"><img v-for="image in item.images.filter(image => image.url).slice(0, 3)" :key="image.assetId" :src="image.url" alt=""><span v-if="!item.images.some(image => image.url)">没有参考图</span></span>
            <ol><li v-for="(plan, index) in item.plans" :key="index"><b>{{ videoPlanLetters[index] }}</b>{{ plan.title }}</li></ol>
            <small>{{ videoHistoryTime(item.createdAt) }}<template v-if="item.billingWallet"> · {{ billingWalletLabel(item.billingWallet) }}</template><template v-if="videoSceneName(scenes, item.sceneKey)"> · {{ videoSceneName(scenes, item.sceneKey) }}</template><template v-if="item.style || item.idea"> · {{ item.style || item.idea }}</template></small>
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
          <small v-if="resultMeta || result.billingWallet">{{ [resultMeta, billingWalletLabel(result.billingWallet)].filter(Boolean).join(' · ') }}</small>
        </div>
        <div class="ip-results-actions">
          <button type="button" class="ip-outline" :disabled="working" @click="showResults = false"><SlidersHorizontal :size="15" :stroke-width="1.8" aria-hidden="true" />重选</button>
          <button type="button" class="ip-primary" :disabled="working" @click="run(result.id)"><RefreshCw :size="15" :stroke-width="1.8" aria-hidden="true" />{{ working ? '正在换一批…' : '换一批' }}<span class="ip-cost"><YanhuoIcon :size="13" />{{ credits }} 烟火</span></button>
        </div>
      </header>
      <p v-if="missingRecordImages" class="ip-status">这批方案用过的 {{ missingRecordImages }} 张参考图已删除，请重新添加素材并生成方案。</p>
      <div class="ip-cards" :class="{ 'is-working': working }">
        <article v-for="(plan, index) in result.plans" :key="index" class="ip-card" :class="{ 'is-applied': appliedIndex === index }">
          <div class="ip-card-title"><span class="ip-letter">{{ videoPlanLetters[index] }}</span><h3>{{ plan.title }}</h3></div>
          <p v-if="plan.idea" class="ip-card-idea">{{ plan.idea }}</p>
          <p class="ip-card-meta">{{ plan.ratio === 'auto' ? '比例自选' : plan.ratio }} · {{ plan.durationSeconds }} 秒 · {{ plan.shots.length }} 个镜头</p>
          <ul v-if="plan.materials.length" class="ip-uses">
            <li v-for="item in plan.materials" :key="item.index" :class="{ 'no-thumb': !resultImages[item.index - 1] }"><img v-if="resultImages[item.index - 1]" :src="resultImages[item.index - 1]" alt="">@图片{{ item.index }} {{ item.use }}</li>
          </ul>
          <ol class="ip-shots">
            <li v-for="(shot, shotIndex) in plan.shots" :key="shotIndex"><time>{{ videoPlanSeconds(shot.start) }}–{{ videoPlanSeconds(shot.end) }}s</time><span>{{ shot.description }}</span></li>
          </ol>
          <details class="ip-prompt"><summary>视频生成指令</summary><p>{{ plan.prompt }}</p></details>
          <button type="button" class="ip-use" :class="{ active: appliedIndex === index }" :disabled="locked" @click="use(plan, index)"><template v-if="appliedIndex === index"><Check :size="15" :stroke-width="2" aria-hidden="true" />已填入描述框</template><template v-else>用这个</template></button>
        </article>
      </div>
      <p class="ip-foot-note">选中的提示词会填进左侧描述框，可以再改字；左侧素材和当初不一样时，会一起换回当初那几张。</p>
    </template>

    <p v-if="working" class="ip-status" role="status">{{ phaseLabel }}。可以先回左侧继续编辑；离开页面再回来也会接着显示这一批。</p>
    <p v-if="unavailable" class="ip-status" role="status">出方案暂未开放，可以直接在左侧写描述。</p>
    <p v-if="error" class="ip-status is-error" role="alert">{{ error }}</p>
  </section>
</template>
