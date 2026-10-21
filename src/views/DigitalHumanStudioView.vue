<script setup>
import AppSelect from '../components/AppSelect.vue'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { AudioLines, ChevronRight, Film, Plus, X } from 'lucide-vue-next'
import VideoPreviewPanel from '../components/VideoPreviewPanel.vue'
import '../video-studio.css'
import YanhuoIcon from '../components/YanhuoIcon.vue'
import { useDigitalHumanVideo } from '../composables/useDigitalHumanVideo'
import { digitalHumanVideoInput } from '../domain/digitalHumanVideo'
import { videoModels, videoModelLabel } from '../services/videoConversations'
import { billing, number, refreshBilling, requireMembership } from '../stores/billing'
import { auth } from '../stores/auth'

const avatarInput = ref(null)
const assetsInput = ref(null)
const referenceVideoInput = ref(null)
const voiceInput = ref(null)
const avatarFile = ref(null)
const productFile = ref(null)
const packageFile = ref(null)
const extraImages = ref([])
const referenceVideos = ref([])
const voiceFile = ref(null)
const extraVoices = ref([])
const script = ref('')
const duration = ref(10)
const ratio = ref('auto')
const selectedModel = ref('SEEDANCE_2_5')
const resolution = ref('720p')
const { workflow, isGenerating, previewStarted, submittedForm, outputUrl, stageLabel, notice: videoNotice, error: videoError, quoted, quoting, estimate, confirmQuote, closeQuote } = useDigitalHumanVideo()
const priceDialog = ref(null)
watch(quoted, async value => { await nextTick(); if (value) priceDialog.value?.showModal(); else priceDialog.value?.close() })
const notice = ref('')
const showAllAvatars = ref(false)
const settingsOpen = ref(false)
const router = useRouter()

const inspirations = [
  { title: '护肤精华', type: '美妆口播', image: 'https://images.unsplash.com/photo-1556228578-8c89e6adf883?w=320&q=80' },
  { title: '智能剃须刀', type: '数码测评', image: 'https://images.unsplash.com/photo-1621607512214-68297480165e?w=320&q=80' },
  { title: '城市轻食', type: '门店推荐', image: 'https://images.unsplash.com/photo-1547592180-85f173990554?w=320&q=80' },
  { title: '春日香氛', type: '生活方式', image: 'https://images.unsplash.com/photo-1547887538-e3a2f32cb1cc?w=320&q=80' },
]
const mockProduct = '/images/digital-human/product-showcase.jpg'
const digitalHumanAvatars = [
  { id: 'avatar-1', label: '1号数字人', name: 'Ethan', image: '/images/digital-human/avatar-1.png' },
  { id: 'avatar-2', label: '2号数字人', name: 'Mason', image: '/images/digital-human/avatar-2.png' },
  { id: 'avatar-3', label: '3号数字人', name: 'Leo', image: '/images/digital-human/avatar-3.png' },
  { id: 'avatar-4', label: '4号数字人', name: 'Sophie', image: '/images/digital-human/avatar-4.png' },
  { id: 'avatar-5', label: '5号数字人', name: 'Ava', image: '/images/digital-human/avatar-5.png' },
  { id: 'avatar-6', label: '6号数字人', name: 'Mia', image: '/images/digital-human/avatar-6.png' },
  { id: 'avatar-7', label: '7号数字人', name: 'Olivia', image: '/images/digital-human/avatar-7.png' },
  { id: 'avatar-8', label: '8号数字人', name: 'Grace', image: '/images/digital-human/avatar-8.png' },
  { id: 'avatar-9', label: '9号数字人', name: 'Emma', image: '/images/digital-human/avatar-9.png' },
]
const ratioOptions = [
  { value: 'auto', label: '自适应' },
  { value: '16:9', label: '16:9' },
  { value: '4:3', label: '4:3' },
  { value: '1:1', label: '1:1' },
  { value: '3:4', label: '3:4' },
  { value: '9:16', label: '9:16' },
  { value: '21:9', label: '21:9' },
]
const currentModel = computed(() => videoModels.find(item => item.id === selectedModel.value) || videoModels[0])
const availableResolutions = computed(() => currentModel.value.resolutions)
const materialLimits = computed(() => currentModel.value.id === 'SEEDANCE_2_5' ? { image: 30, video: 10, audio: 10 } : { image: 9, video: 3, audio: 3 })
const materialLimitLabel = computed(() => `图片 ${materialLimits.value.image} · 视频 ${materialLimits.value.video} · 音频 ${materialLimits.value.audio}`)

const imageCount = computed(() => [avatarFile.value, productFile.value, packageFile.value, ...extraImages.value].filter(Boolean).length)
const imageSlotsRemaining = computed(() => Math.max(0, materialLimits.value.image - imageCount.value))
const videoSlotsRemaining = computed(() => Math.max(0, materialLimits.value.video - referenceVideos.value.length))
const audioCount = computed(() => [voiceFile.value, ...extraVoices.value].filter(Boolean).length)
const audioSlotsRemaining = computed(() => Math.max(0, materialLimits.value.audio - audioCount.value))

const enforceMaterialLimits = () => {
  let trimmed = false
  while (imageCount.value > materialLimits.value.image && extraImages.value.length) {
    revokeBlob(extraImages.value.pop())
    trimmed = true
  }
  while (referenceVideos.value.length > materialLimits.value.video) {
    revokeBlob(referenceVideos.value.pop())
    trimmed = true
  }
  while (audioCount.value > materialLimits.value.audio && extraVoices.value.length) {
    revokeBlob(extraVoices.value.pop())
    trimmed = true
  }
  if (trimmed) notice.value = `已按 ${currentModel.value.label} 的素材上限保留已选内容。`
}

const revokeBlob = item => {
  if (item?.url?.startsWith('blob:')) URL.revokeObjectURL(item.url)
}

const setUpload = (type, event) => {
  const files = [...(event.target.files || [])].filter(file => type === 'voice' ? file.type.startsWith('audio/') : type === 'video' ? file.type.startsWith('video/') : file.type.startsWith('image/'))
  if (!files.length) return
  if (type === 'assets') {
    const accepted = files.slice(0, imageSlotsRemaining.value)
    if (accepted.length < files.length) notice.value = `当前模型最多支持 ${materialLimits.value.image} 张图片。`
    const next = accepted.map(file => ({ name: file.name, url: URL.createObjectURL(file), file, type: 'IMAGE' }))
    next.forEach(asset => {
      if (!productFile.value) productFile.value = asset
      else if (!packageFile.value) packageFile.value = asset
      else extraImages.value.push(asset)
    })
  } else if (type === 'video') {
    if (files.length > videoSlotsRemaining.value) notice.value = `当前模型最多支持 ${materialLimits.value.video} 个参考视频。`
    referenceVideos.value = [...referenceVideos.value, ...files.slice(0, videoSlotsRemaining.value).map(file => ({ name: file.name, url: URL.createObjectURL(file), file, type: 'VIDEO' }))]
  } else if (type === 'voice') {
    if (files.length > audioSlotsRemaining.value) notice.value = `当前模型最多支持 ${materialLimits.value.audio} 个音色。`
    files.slice(0, audioSlotsRemaining.value).forEach(file => {
      const asset = { name: file.name, url: URL.createObjectURL(file), file, type: 'AUDIO' }
      if (!voiceFile.value) voiceFile.value = asset
      else extraVoices.value.push(asset)
    })
  } else {
    if (type === 'avatar' && !avatarFile.value && imageSlotsRemaining.value <= 0) return
    const target = { avatar: avatarFile }[type]
    if (target.value?.url?.startsWith('blob:')) URL.revokeObjectURL(target.value.url)
    target.value = { name: files[0].name, url: URL.createObjectURL(files[0]), file: files[0], type: 'IMAGE' }
  }
  event.target.value = ''
}
const selectPresetAvatar = asset => {
  if (!avatarFile.value && imageSlotsRemaining.value <= 0) return
  if (avatarFile.value?.url?.startsWith('blob:')) URL.revokeObjectURL(avatarFile.value.url)
  avatarFile.value = { name: asset.name, url: asset.url || asset.image, assetId: asset.assetId, type: 'IMAGE' }
}
const clearUpload = (type) => {
  const target = { avatar: avatarFile, product: productFile, package: packageFile, voice: voiceFile }[type]
  revokeBlob(target.value)
  target.value = null
}
const removeExtraImage = index => { revokeBlob(extraImages.value[index]); extraImages.value.splice(index, 1) }
const removeReferenceVideo = index => { revokeBlob(referenceVideos.value[index]); referenceVideos.value.splice(index, 1) }
const removeExtraVoice = index => { revokeBlob(extraVoices.value[index]); extraVoices.value.splice(index, 1) }
const generate = async () => {
  if (isGenerating.value) return
  notice.value = ''
  try {
    await requireMembership()
    if (isGenerating.value || quoting.value) return
    const input = digitalHumanVideoInput({ script: script.value, avatar: avatarFile.value,
      images: [productFile.value, packageFile.value, ...extraImages.value].filter(Boolean),
      videos: referenceVideos.value, voices: [voiceFile.value, ...extraVoices.value].filter(Boolean),
      model: selectedModel.value, ratio: ratio.value, durationSeconds: duration.value, resolution: resolution.value })
    estimate(input)
    refreshBilling()
  } catch (error) {
    notice.value = error.message
  }
}
const switchWorkspace = (path) => {
  if (path === router.currentRoute.value.path) return
  if (typeof document.startViewTransition === 'function') {
    document.startViewTransition(() => router.push(path))
    return
  }
  router.push(path)
}
const clampSettings = () => {
  duration.value = Math.min(currentModel.value.maxDurationSeconds, Math.max(5, duration.value))
  if (!availableResolutions.value.includes(resolution.value)) resolution.value = availableResolutions.value[0]
}
const selectModel = model => { selectedModel.value = model; clampSettings(); enforceMaterialLimits() }
watch(selectedModel, () => { clampSettings(); enforceMaterialLimits() })
onBeforeUnmount(() => [avatarFile.value, productFile.value, packageFile.value, ...extraImages.value, ...referenceVideos.value, voiceFile.value, ...extraVoices.value].forEach(revokeBlob))
</script>

<template>
  <div class="studio-page digital-human-page">
    <main class="dh-workspace">
      <aside class="dh-form-panel" aria-label="数字人视频配置">
        <fieldset class="dh-form-scroll vs-form" :disabled="isGenerating">
          <h1 class="vs-title">数字人摄影棚</h1>
          <section class="vs-group">
            <span class="vs-label">素材<small>均可选</small></span>
            <div class="vs-list">
              <div class="vs-item">
                <button type="button" class="vs-row" @click="avatarInput?.click()"><span>数字人形象</span><small>{{ avatarFile ? '已选择 1 张' : '从本地上传' }}</small><Plus :size="17" :stroke-width="1.7" aria-hidden="true" /></button>
                <div v-if="avatarFile" class="vs-files"><span class="vs-file"><img :src="avatarFile.url" alt="已选数字人形象"><span>{{ avatarFile.name }}</span><button type="button" aria-label="移除数字人形象" @click="clearUpload('avatar')"><X :size="12" :stroke-width="2.2" aria-hidden="true" /></button></span></div>
                <input ref="avatarInput" class="video-file-input" type="file" accept="image/*" @change="setUpload('avatar', $event)">
              </div>
              <div class="vs-item">
                <button type="button" class="vs-row" @click="assetsInput?.click()"><span>商品图片</span><small>最多 {{ materialLimits.image }} 张</small><Plus :size="17" :stroke-width="1.7" aria-hidden="true" /></button>
                <div v-if="productFile || packageFile || extraImages.length" class="vs-files">
                  <span v-for="asset in [productFile, packageFile].filter(Boolean)" :key="asset.name" class="vs-file"><img :src="asset.url" :alt="asset.name"><span>{{ asset.name }}</span><button type="button" aria-label="移除参考图片" @click="clearUpload(asset === productFile ? 'product' : 'package')"><X :size="12" :stroke-width="2.2" aria-hidden="true" /></button></span>
                  <span v-for="(asset, index) in extraImages" :key="asset.name + index" class="vs-file"><img :src="asset.url" :alt="asset.name"><span>{{ asset.name }}</span><button type="button" aria-label="移除参考图片" @click="removeExtraImage(index)"><X :size="12" :stroke-width="2.2" aria-hidden="true" /></button></span>
                </div>
                <input ref="assetsInput" class="video-file-input" type="file" accept="image/*" multiple @change="setUpload('assets', $event)">
              </div>
              <div class="vs-item">
                <button type="button" class="vs-row" @click="referenceVideoInput?.click()"><span>参考视频</span><small>最多 {{ materialLimits.video }} 个</small><Plus :size="17" :stroke-width="1.7" aria-hidden="true" /></button>
                <div v-if="referenceVideos.length" class="vs-files">
                  <span v-for="(asset, index) in referenceVideos" :key="asset.name + index" class="vs-file"><Film :size="16" :stroke-width="1.6" aria-hidden="true" /><span>{{ asset.name }}</span><button type="button" aria-label="移除参考视频" @click="removeReferenceVideo(index)"><X :size="12" :stroke-width="2.2" aria-hidden="true" /></button></span>
                </div>
                <input ref="referenceVideoInput" class="video-file-input" type="file" accept="video/*" multiple @change="setUpload('video', $event)">
              </div>
              <div class="vs-item">
                <button type="button" class="vs-row" @click="voiceInput?.click()"><span>音色</span><small>最多 {{ materialLimits.audio }} 个</small><Plus :size="17" :stroke-width="1.7" aria-hidden="true" /></button>
                <div v-if="voiceFile || extraVoices.length" class="vs-files">
                  <span v-if="voiceFile" class="vs-file"><AudioLines :size="16" :stroke-width="1.6" aria-hidden="true" /><span>{{ voiceFile.name }}</span><button type="button" aria-label="移除音色" @click="clearUpload('voice')"><X :size="12" :stroke-width="2.2" aria-hidden="true" /></button></span>
                  <span v-for="(asset, index) in extraVoices" :key="asset.name + index" class="vs-file"><AudioLines :size="16" :stroke-width="1.6" aria-hidden="true" /><span>{{ asset.name }}</span><button type="button" aria-label="移除音色" @click="removeExtraVoice(index)"><X :size="12" :stroke-width="2.2" aria-hidden="true" /></button></span>
                </div>
                <input ref="voiceInput" class="video-file-input" type="file" accept="audio/*" multiple @change="setUpload('voice', $event)">
              </div>
            </div>
          </section>
          <section class="vs-group">
            <label for="dh-script" class="vs-label">描述内容</label>
            <textarea id="dh-script" v-model="script" class="vs-textarea" rows="4" maxlength="500" placeholder="输入视频文案或商品描述，例如：今天给大家分享一款适合敏感肌的春日精华，轻薄好吸收，换季也能保持水润光泽。" />
          </section>
          <section class="vs-settings">
            <button type="button" class="vs-settings-toggle" :aria-expanded="settingsOpen" aria-controls="dh-settings" @click="settingsOpen = !settingsOpen"><span>输出设置</span><small>{{ currentModel.label }} · {{ ratioOptions.find(option => option.value === ratio)?.label }} · {{ duration }} 秒 · {{ resolution }}</small><ChevronRight :size="15" class="vs-chevron" :class="{ open: settingsOpen }" aria-hidden="true" /></button>
            <div v-show="settingsOpen" id="dh-settings" class="vs-settings-body">
              <div class="vs-segmented vs-models" role="group" aria-label="视频模型"><button v-for="model in videoModels" :key="model.id" type="button" :aria-pressed="selectedModel === model.id" :class="{ active: selectedModel === model.id }" @click="selectModel(model.id)">{{ model.label }}</button></div>
              <label class="vs-setting-row"><span>画面比例</span><AppSelect v-model="ratio" class="vs-row-select" aria-label="选择画面比例"><option v-for="option in ratioOptions" :key="option.value" :value="option.value">{{ option.label }}</option></AppSelect></label>
              <div class="vs-setting-row vs-duration"><label for="dh-duration">视频时长</label><strong>{{ duration }} 秒</strong><input id="dh-duration" v-model.number="duration" class="vs-range" type="range" min="5" :max="currentModel.maxDurationSeconds" step="1"></div>
              <div class="vs-setting-row"><span>分辨率</span><div class="vs-segmented" role="group" aria-label="分辨率"><button v-for="item in availableResolutions" :key="item" type="button" :aria-pressed="resolution === item" :class="{ active: resolution === item }" @click="resolution = item">{{ item }}</button></div></div>
              <small class="vs-setting-note">{{ currentModel.label }} 可用素材：{{ materialLimitLabel }}</small>
            </div>
          </section>
        </fieldset>
        <div class="dh-form-footer vs-footer">
          <p v-if="notice || videoNotice" class="vs-notice" :class="{ 'is-error': videoError }" role="status">{{ notice || videoNotice }}</p>
          <button class="vs-generate" type="button" :disabled="isGenerating || quoting" @click="generate">{{ isGenerating ? '正在生成…' : quoting ? '正在预估费用…' : '预估费用并生成' }}</button>
          <small>先预估烟火，确认后开始生成</small>
        </div>
      </aside>

      <VideoPreviewPanel
        v-if="previewStarted"
        class="dh-preview-panel dh-result-panel"
        :is-generating="isGenerating"
        :output-url="outputUrl"
        :actual-width="workflow?.actualWidth"
        :actual-height="workflow?.actualHeight"
        :format="submittedForm?.ratio || ratio"
        :model-label="videoModelLabel(submittedForm?.model || selectedModel)"
        :duration="submittedForm?.durationSeconds || duration"
        :resolution="submittedForm?.resolution || resolution"
        :stage-label="stageLabel"
        :error="isGenerating ? '' : videoError"
        active-workspace="digital-human"
        continue-text="生成完成后可直接播放，成片将保存到作品库。"
        @switch-workspace="switchWorkspace"
      />
      <section v-else class="dh-preview-panel vs-preview" aria-label="数字人视频预览">
        <div class="vs-preview-bar">
          <div class="dh-studio-switcher video-mode-switch" role="group" aria-label="切换工作台">
            <button type="button" @click="switchWorkspace('/video/workbench')">视频工作台</button>
            <button type="button" class="active" aria-pressed="true" @click="switchWorkspace('/digital-human/studio')"><span class="material-symbols-outlined">record_voice_over</span>数字人摄影棚</button>
          </div>
        </div>
        <div class="vs-stage" :class="{ 'is-portrait': ratio === '9:16' }">
          <figure><img :src="avatarFile?.url || digitalHumanAvatars[0].image" alt="数字人示例"><figcaption>数字人</figcaption></figure>
          <figure><img :src="packageFile?.url || productFile?.url || mockProduct" alt="商品示例"><figcaption>商品</figcaption></figure>
        </div>
        <div class="vs-avatars">
          <div class="vs-avatars-head"><span>方志原创数字人</span><button type="button" @click="showAllAvatars = true">查看全部</button></div>
          <div class="vs-avatars-grid">
            <button v-for="item in digitalHumanAvatars.slice(0, 4)" :key="item.id" type="button" :aria-pressed="avatarFile?.url === item.image" :class="{ active: avatarFile?.url === item.image }" @click="selectPresetAvatar(item)"><img :src="item.image" :alt="item.label"><span>{{ item.label }}</span></button>
          </div>
        </div>
      </section>
    </main>
    <dialog ref="priceDialog" class="yanhuo-payment video-price-dialog" aria-labelledby="dh-price-title" @cancel.prevent="closeQuote">
      <template v-if="quoted"><header><img src="/images/brand/yifangzhi-mark.png" width="32" height="32" alt=""><h2 id="dh-price-title">确认视频费用</h2><button class="yanhuo-icon" type="button" aria-label="关闭报价" :disabled="isGenerating" @click="closeQuote">×</button></header><template v-if="quoted.quote.available"><p class="yanhuo-payment-amount"><YanhuoIcon :size="28" />{{ number(quoted.quote.credits) }}</p><p class="yanhuo-note">{{ quoted.quote.message }}</p><p class="yanhuo-note">{{ quoted.quote.providerModel }} · {{ quoted.quote.resolution }}</p><p class="yanhuo-note">可用烟火：{{ billing.error ? '暂不可读' : number(billing.summary?.available || 0) }}</p><button class="yanhuo-primary" type="button" :disabled="isGenerating || !billing.summary?.memberActive || !billing.summary?.plan?.videoEnabled || !!billing.error || (billing.summary?.available || 0) < quoted.quote.credits" @click="confirmQuote">{{ isGenerating ? '正在提交...' : '确认费用，开始生成' }}</button></template><p v-else class="yanhuo-alert">{{ quoted.quote.message }}</p><RouterLink to="/billing" class="generation-billing-link" @click="closeQuote">套餐与充值 →</RouterLink><p v-if="videoError" class="yanhuo-alert" role="alert">{{ videoError }}</p></template>
    </dialog>
    <Transition name="dh-avatar-modal" appear>
      <div v-if="showAllAvatars" class="dh-avatar-modal" role="dialog" aria-modal="true" aria-label="全部数字人形象" @click.self="showAllAvatars = false">
        <Transition name="dh-avatar-modal-card" appear>
          <div class="dh-avatar-modal-card">
            <div class="dh-avatar-modal-header"><h2>方志原创数字人形象</h2><button type="button" aria-label="关闭" @click="showAllAvatars = false">×</button></div>
            <div class="dh-avatar-modal-grid"><button v-for="item in digitalHumanAvatars" :key="item.id" type="button" class="dh-avatar-modal-item" @click="selectPresetAvatar(item); showAllAvatars = false"><span class="dh-avatar-modal-thumb"><img :src="item.image" :alt="item.label"></span><span><strong>{{ item.label }}</strong><small>{{ item.name }}</small></span></button></div>
          </div>
        </Transition>
      </div>
    </Transition>
  </div>
</template>
