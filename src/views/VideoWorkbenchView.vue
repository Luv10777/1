<script setup>
import AppSelect from '../components/AppSelect.vue'
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { AudioLines, ChevronRight, Film, History, ImagePlus, Plus, Sparkles, X } from 'lucide-vue-next'
import VideoPreviewPanel from '../components/VideoPreviewPanel.vue'
import VideoPlanPanel from '../components/VideoPlanPanel.vue'
import VideoScenePicker from '../components/VideoScenePicker.vue'
import '../video-studio.css'
import YanhuoIcon from '../components/YanhuoIcon.vue'
import { videoModelLabel, videoModelBadge, videoHistoryTime } from '../services/videoConversations'
import { billing, dateTime, number } from '../stores/billing'
import { FREE_SCENE, videoSceneCover, videoSceneName } from '../services/videoScenes'
import { useVideoWorkbench, videoRatioOptions as ratioOptions } from '../composables/useVideoWorkbench'

const defaultPlaceholder = '例如：为这份双人海鲜套餐制作一段诱人的展示视频，镜头从特写拉远，强调食材的新鲜与就餐的松弛感。'
const scenePicker = ref(null)
const imageInput = ref(null)
const videoInput = ref(null)
const audioInput = ref(null)
const historyOpen = ref(false)
const rightTab = ref('preview')
const canvas = ref(null)
const settingsOpen = ref(false)
const router = useRouter()
const isMorphing = ref(false)
const quoteDialog = ref(null)
let morphTimer

const openRightTab = async (tab) => {
  rightTab.value = tab
  historyOpen.value = false
  await nextTick()
  // 手机上预览区在表单下方，点了入口直接滚过去。
  if (window.matchMedia('(max-width: 760px)').matches) canvas.value?.$el?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
const {
  prompt, format, duration, resolution, selectedModel, referenceImages, referenceVideos, referenceAudios, scenes, sceneKey,
  capabilities, fallbackCapabilities, conversations, activeConversationId, historyLoading, historyError, historyPage, historyHasMore,
  quoted, quoteError, quoteBusy, currentCapability, durationMax, availableResolutions, materialLimits, materialLimitLabel,
  activeConversation, workflow, isGenerating, notice, outputUrl, stageLabel, currentScene, historyRunning,
  conversationStatus, statusTone, loadHistory, selectModel, handleMaterialChange, handleMaterialDrop, removeMaterial, uploadAsset,
  generate, closeQuote, confirmQuote, retryVideo, cancelVideo, applyPlan, ...video
} = useVideoWorkbench({
  onQuote: () => quoteDialog.value?.showModal(),
  onQuoteClose: () => quoteDialog.value?.close(),
  // 确认开始生成后切到成片预览看进度（例如刚从视频方案填入后点了生成）。
  onSubmitted: () => openRightTab('preview'),
})
const selectConversation = (item) => { historyOpen.value = false; return video.selectConversation(item) }
const startNewConversation = () => { video.startNewConversation(); historyOpen.value = false }

const switchWorkspace = (path) => {
  if (path === router.currentRoute.value.path) return
  if (typeof document.startViewTransition === 'function') {
    document.startViewTransition(() => router.push(path))
    return
  }
  router.push(path)
}
watch(format, () => {
  isMorphing.value = true
  window.clearTimeout(morphTimer)
  morphTimer = window.setTimeout(() => { isMorphing.value = false }, 900)
})
onBeforeUnmount(() => window.clearTimeout(morphTimer))
</script>

<template>
  <div class="video-workbench-page flex h-[calc(100vh-64px)] w-full overflow-hidden border-t border-gray-200 bg-white">
    <div class="video-workbench-sidebar w-[420px] h-full flex flex-col bg-white border-r border-gray-100 shadow-[4px_0_24px_rgba(0,0,0,0.02)] z-20">
      <div class="video-editor-heading"><div><h1>视频创作</h1></div><button class="vp-entry" type="button" :aria-pressed="rightTab === 'plan'" @click="openRightTab('plan')"><Sparkles :size="15" :stroke-width="1.8" aria-hidden="true" />灵感方案</button><button class="video-history-trigger" type="button" :aria-expanded="historyOpen" @click="historyOpen = !historyOpen"><span class="material-symbols-outlined">history</span>创作记录</button></div>
      <p v-if="activeConversation?.loading" class="video-history-message" role="status">正在恢复提示词、参考素材与输出选项…</p>
      <fieldset class="video-workbench-scroll vs-form flex-1 overflow-y-auto" :disabled="isGenerating || activeConversation?.loading">
        <section v-if="currentScene" class="vs-group">
          <span class="vs-label">创作场景</span>
          <div class="vs-scene-field">
            <button type="button" class="vs-scene-pick" aria-haspopup="dialog" @click="scenePicker?.open()">
              <span class="vs-scene-cover is-small" :data-scene="currentScene.key"><img v-if="videoSceneCover(currentScene.key)" :src="videoSceneCover(currentScene.key)" alt=""><span v-else class="material-symbols-outlined" aria-hidden="true">{{ currentScene.icon }}</span></span>
              <span class="vs-scene-text"><strong>{{ currentScene.name }}</strong><small>{{ currentScene.summary }}</small></span>
              <span class="vs-scene-action">{{ sceneKey === FREE_SCENE ? '选择场景' : '更换' }}</span>
            </button>
            <button v-if="sceneKey !== FREE_SCENE" type="button" class="vs-scene-clear" aria-label="清除场景，回到自由创作" @click="sceneKey = FREE_SCENE"><X :size="14" :stroke-width="2.2" aria-hidden="true" /></button>
          </div>
          <small v-if="currentScene.materialHint" class="vs-scene-hint">{{ currentScene.materialHint }}</small>
        </section>

        <section class="vs-group">
          <span class="vs-label">素材</span>
          <div class="vs-tiles">
            <button class="vs-tile" type="button" @click="imageInput?.click()" @dragover.prevent @drop="handleMaterialDrop('image', $event)"><ImagePlus :size="18" :stroke-width="1.6" aria-hidden="true" /><span>参考图片</span><small>最多 {{ materialLimits.image }} 张</small></button>
            <button class="vs-tile" type="button" @click="videoInput?.click()" @dragover.prevent @drop="handleMaterialDrop('video', $event)"><Film :size="18" :stroke-width="1.6" aria-hidden="true" /><span>参考视频</span><small>最多 {{ materialLimits.video }} 个</small></button>
            <button class="vs-tile" type="button" @click="audioInput?.click()" @dragover.prevent @drop="handleMaterialDrop('audio', $event)"><AudioLines :size="18" :stroke-width="1.6" aria-hidden="true" /><span>音色</span><small>最多 {{ materialLimits.audio }} 个</small></button>
          </div>
          <div v-if="referenceImages.length || referenceVideos.length || referenceAudios.length" class="vs-files">
            <span v-for="asset in referenceImages" :key="asset.id" class="vs-file"><img v-if="asset.url" :src="asset.url" :alt="asset.name"><span>{{ asset.name }}</span><button type="button" aria-label="删除参考图" @click="removeMaterial('image', asset)"><X :size="12" :stroke-width="2.2" aria-hidden="true" /></button></span>
            <span v-for="asset in referenceVideos" :key="asset.id" class="vs-file"><Film :size="16" :stroke-width="1.6" aria-hidden="true" /><span>{{ asset.name }}</span><button type="button" aria-label="删除参考视频" @click="removeMaterial('video', asset)"><X :size="12" :stroke-width="2.2" aria-hidden="true" /></button></span>
            <span v-for="asset in referenceAudios" :key="asset.id" class="vs-file"><AudioLines :size="16" :stroke-width="1.6" aria-hidden="true" /><span>{{ asset.name }}</span><button type="button" aria-label="删除音色" @click="removeMaterial('audio', asset)"><X :size="12" :stroke-width="2.2" aria-hidden="true" /></button></span>
          </div>
          <input ref="imageInput" class="video-file-input" type="file" accept="image/*" multiple @change="handleMaterialChange('image', $event)">
          <input ref="videoInput" class="video-file-input" type="file" accept="video/*" multiple @change="handleMaterialChange('video', $event)">
          <input ref="audioInput" class="video-file-input" type="file" accept="audio/*" multiple @change="handleMaterialChange('audio', $event)">
        </section>

        <section class="vs-group">
          <label for="video-prompt" class="vs-label">描述你的成片需求</label>
          <textarea id="video-prompt" v-model="prompt" class="vs-textarea" rows="5" :placeholder="currentScene?.placeholder || defaultPlaceholder" />
        </section>

        <section class="vs-settings">
          <button type="button" class="vs-settings-toggle" :aria-expanded="settingsOpen" aria-controls="video-settings" @click="settingsOpen = !settingsOpen"><span>输出设置</span><small>{{ currentCapability.label }} · {{ ratioOptions.find(option => option.value === format)?.label }} · {{ duration }} 秒 · {{ resolution }}</small><ChevronRight :size="15" class="vs-chevron" :class="{ open: settingsOpen }" aria-hidden="true" /></button>
          <div v-show="settingsOpen" id="video-settings" class="vs-settings-body">
            <div class="vs-segmented vs-models" role="group" aria-label="视频模型"><button v-for="model in (capabilities.length ? capabilities : fallbackCapabilities)" :key="model.id" type="button" :aria-pressed="selectedModel === model.id" :class="{ active: selectedModel === model.id }" @click="selectModel(model.id)">{{ model.label }}<span v-if="videoModelBadge(model.id)" class="vs-model-badge">{{ videoModelBadge(model.id) }}</span></button></div>
            <label class="vs-setting-row"><span>画面比例</span><AppSelect v-model="format" class="vs-row-select" aria-label="选择画面比例"><option v-for="option in ratioOptions" :key="option.value" :value="option.value">{{ option.label }}</option></AppSelect></label>
            <div class="vs-setting-row vs-duration"><label for="video-duration">视频时长</label><strong>{{ duration }} 秒</strong><input id="video-duration" v-model.number="duration" class="vs-range" type="range" min="5" :max="durationMax" step="1"></div>
            <div class="vs-setting-row"><span>分辨率</span><div class="vs-segmented" role="group" aria-label="分辨率"><button v-for="item in availableResolutions" :key="item" type="button" :aria-pressed="resolution === item" :class="{ active: resolution === item }" @click="resolution = item">{{ item }}</button></div></div>
            <small class="vs-setting-note">{{ currentCapability.label }} 可用素材：{{ materialLimitLabel }}</small>
          </div>
        </section>
      </fieldset>

      <div class="video-workbench-action vs-footer">
        <p v-if="notice" class="vs-notice" :class="{ 'is-error': activeConversation?.error }" role="status">{{ notice }}</p>
        <button class="vs-generate" type="button" :disabled="isGenerating || activeConversation?.loading" @click="generate">{{ isGenerating ? '正在生成视频…' : '预估费用并生成' }}</button>
        <small>先预估烟火，确认后开始生成</small>
        <p v-if="workflow?.chargedCredits != null" class="vs-notice">本次交付消耗 {{ number(workflow.chargedCredits) }} 烟火。</p>
        <button v-if="workflow?.retryable" class="generation-billing-link" type="button" :disabled="activeConversation?.submitting" @click="retryVideo">预估恢复费用</button>
        <button v-if="isGenerating && workflow?.id" class="generation-billing-link" type="button" :disabled="activeConversation?.submitting" @click="cancelVideo">取消生成，退回未消耗烟火</button>
      </div>
    </div>

    <VideoPreviewPanel
      ref="canvas"
      :class="{ 'is-planning': rightTab === 'plan' }"
      :is-generating="isGenerating"
      :is-morphing="isMorphing"
      :output-url="outputUrl"
      :actual-width="workflow?.actualWidth"
      :actual-height="workflow?.actualHeight"
      :format="workflow?.ratio || format"
      :model-label="workflow?.model ? videoModelLabel(workflow.model) : currentCapability.label"
      :duration="workflow?.durationSeconds || duration"
      :resolution="workflow?.resolution || resolution"
      :stage-label="stageLabel"
      @switch-workspace="switchWorkspace"
    >
      <template #title>
        <div class="vp-tabs" role="tablist" aria-label="右侧内容">
          <button type="button" role="tab" :aria-selected="rightTab === 'preview'" :class="{ active: rightTab === 'preview' }" @click="rightTab = 'preview'">成片预览</button>
          <button type="button" role="tab" :aria-selected="rightTab === 'plan'" :class="{ active: rightTab === 'plan' }" @click="rightTab = 'plan'">视频方案</button>
        </div>
      </template>
      <template #actions>
        <button type="button" class="vs-drawer-toggle" :aria-expanded="historyOpen" aria-controls="video-history" @click="historyOpen = !historyOpen"><History :size="16" :stroke-width="1.7" aria-hidden="true" />创作记录<i v-if="historyRunning" aria-hidden="true" /><span v-if="historyRunning" class="sr-only">，有任务生成中</span></button>
      </template>
      <VideoPlanPanel
        v-show="rightTab === 'plan'"
        :images="referenceImages"
        :video="referenceVideos[0]"
        :upload="uploadAsset"
        :max-duration="durationMax"
        :duration="duration"
        :ratio="format"
        :brief="prompt"
        :ratios="ratioOptions.map(option => option.value)"
        :locked="isGenerating || Boolean(activeConversation?.loading)"
        :scene="sceneKey"
        :scenes="scenes"
        @apply="applyPlan"
        @scene="key => { sceneKey = key }"
        @pick-scene="scenePicker?.open()"
      />
      <div v-if="historyOpen" class="vs-drawer-backdrop" aria-hidden="true" @click="historyOpen = false" />
      <aside v-if="historyOpen" id="video-history" class="vs-drawer" aria-label="视频创作记录" @keydown.esc="historyOpen = false">
        <div class="vs-drawer-head">
          <span>创作记录<small v-if="conversations.length">{{ conversations.length }}</small></span>
          <button type="button" class="vs-drawer-icon" aria-label="新对话" title="新对话" @click="startNewConversation"><Plus :size="18" :stroke-width="1.8" aria-hidden="true" /></button>
          <button type="button" class="vs-drawer-icon" aria-label="关闭创作记录" @click="historyOpen = false"><X :size="18" :stroke-width="1.8" aria-hidden="true" /></button>
        </div>
        <div class="vs-drawer-list">
          <button
            v-for="item in conversations"
            :key="item.id"
            class="vs-drawer-item"
            :class="{ active: item.id === activeConversationId }"
            :aria-current="item.id === activeConversationId ? 'true' : undefined"
            :title="`${item.title} · ${videoModelLabel(item.selectedModel)} · ${item.resolution}`"
            type="button"
            @click="selectConversation(item)"
          >
            <span class="vs-drawer-thumb"><video v-if="item.workflow?.outputUrl" :src="`${item.workflow.outputUrl}#t=0.1`" muted playsinline preload="metadata" aria-hidden="true" /><Film v-else :size="18" :stroke-width="1.6" aria-hidden="true" /></span>
            <span class="vs-drawer-text"><strong>{{ item.title }}</strong><small><span :class="`is-${statusTone(item)}`">{{ conversationStatus(item) }}</span><template v-if="videoSceneName(scenes, item.sceneKey)"> · {{ videoSceneName(scenes, item.sceneKey) }}</template> · {{ item.duration }} 秒<template v-if="item.updatedAt"> · {{ videoHistoryTime(item.updatedAt) }}</template></small></span>
          </button>
          <p v-if="!conversations.length" class="vs-drawer-empty">从一段新的商家故事开始。</p>
          <p v-if="historyLoading" class="vs-drawer-empty" role="status">正在读取创作记录…</p>
          <template v-else-if="historyError">
            <p class="vs-drawer-empty is-error" role="status">{{ historyError }}</p>
            <button type="button" class="vs-drawer-more" @click="loadHistory(historyPage)">重新加载</button>
          </template>
          <button v-else-if="historyHasMore" type="button" class="vs-drawer-more" @click="loadHistory(historyPage + 1)">加载更早的创作</button>
        </div>
      </aside>
    </VideoPreviewPanel>
    <VideoScenePicker ref="scenePicker" v-model="sceneKey" :scenes="scenes" />
    <dialog ref="quoteDialog" class="yanhuo-payment video-price-dialog" aria-labelledby="video-price-title" @cancel.prevent="closeQuote">
      <template v-if="quoted"><header><img src="/images/brand/yifangzhi-mark.png" alt="" width="32" height="32"><h2 id="video-price-title">确认视频费用</h2><button class="yanhuo-icon" type="button" aria-label="关闭报价" :disabled="quoteBusy" @click="closeQuote">×</button></header><p class="yanhuo-payment-item">{{ videoModelLabel(quoted.request.model) }} · {{ quoted.request.resolution }} · {{ quoted.request.durationSeconds }} 秒</p><template v-if="quoted.quote.available"><p class="yanhuo-payment-amount"><YanhuoIcon :size="28" />{{ number(quoted.quote.credits) }}</p><p class="yanhuo-note">{{ quoted.quote.message }}</p><dl class="video-price-details"><div><dt>实际模型</dt><dd>{{ quoted.quote.providerModel }}</dd></div><div><dt>预估用量</dt><dd>{{ number(quoted.quote.estimatedTokens) }} 视频 Token</dd></div><div><dt>费率核验</dt><dd>{{ dateTime(quoted.quote.verifiedAt) }}</dd></div><div><dt>可用烟火</dt><dd>{{ billing.error ? '暂不可读' : number(billing.summary?.available || 0) }}</dd></div></dl><p v-if="billing.summary && billing.summary.available < quoted.quote.credits" class="yanhuo-alert">烟火不足，请先充值。</p><button class="yanhuo-primary" type="button" :disabled="quoteBusy || !!billing.error || (billing.summary?.available || 0) < quoted.quote.credits" @click="confirmQuote">{{ quoteBusy ? '正在提交...' : '确认费用，开始生成' }}</button></template><p v-else class="yanhuo-alert">{{ quoted.quote.message }}</p><RouterLink v-if="(billing.summary?.available || 0) < quoted.quote.credits" class="generation-billing-link" to="/billing" @click="closeQuote">前往套餐与充值</RouterLink><p v-if="quoteError" class="yanhuo-alert" role="alert">{{ quoteError }}</p></template>
    </dialog>
  </div>
</template>
