<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import VideoPreviewPanel from '../components/VideoPreviewPanel.vue'
import { useDigitalHumanVideo } from '../composables/useDigitalHumanVideo'
import { digitalHumanVideoInput } from '../domain/digitalHumanVideo'
import { get } from '../utils/request'
import { assets as platformAssets, assetSource } from '../stores/assetLibrary'
import { videoModels, videoModelLabel } from '../services/videoConversations'

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
const libraryType = ref('image')
const libraryTarget = ref('assets')
const libraryOpen = ref(false)
const libraryLoading = ref(false)
const libraryError = ref('')
const libraryQuery = ref('')
const libraryAssets = ref([])
const script = ref('')
const duration = ref(10)
const ratio = ref('auto')
const selectedModel = ref('SEEDANCE_2_5')
const resolution = ref('720p')
const { isGenerating, previewStarted, submittedForm, outputUrl, stageLabel, notice: videoNotice, error: videoError, start } = useDigitalHumanVideo()
const notice = ref('')
const showAllAvatars = ref(false)
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
const libraryKind = computed(() => ({ audio: 'AUDIO', video: 'VIDEO' }[libraryType.value] || 'IMAGE'))
const visibleLibraryAssets = computed(() => {
  const query = libraryQuery.value.trim().toLowerCase()
  return libraryAssets.value.filter(item => item.type === libraryKind.value && (!query || item.name.toLowerCase().includes(query)))
})
const normalizeLibraryAsset = item => ({
  id: item.id,
  assetId: Number.isFinite(Number(item.id)) ? Number(item.id) : null,
  name: item.name,
  type: item.type || (item.kind === 'audio' ? 'AUDIO' : 'IMAGE'),
  url: item.previewUrl || item.mediaUrl || itemSource(item),
  image: item.previewUrl || item.mediaUrl || itemSource(item),
})
const itemSource = item => assetSource(item) || item.url || item.image || ''
const fallbackLibraryAssets = computed(() => platformAssets.value
  .filter(item => item.kind === libraryType.value)
  .map(normalizeLibraryAsset))

const closeAssetLibrary = () => { libraryOpen.value = false }

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

const openAssetLibrary = async (type, target = 'assets') => {
  libraryTarget.value = target
  libraryType.value = type
  libraryQuery.value = ''
  libraryError.value = ''
  libraryOpen.value = true
  libraryLoading.value = true
  try {
    const page = await get('/api/assets', { page: 0, size: 100 })
    const apiAssets = (page.items || []).map(normalizeLibraryAsset).filter(item => item.type === libraryKind.value)
    libraryAssets.value = apiAssets.length ? apiAssets : fallbackLibraryAssets.value
  } catch (error) {
    libraryAssets.value = fallbackLibraryAssets.value
    if (!libraryAssets.value.length) libraryError.value = error.message || '素材库暂时无法加载'
  } finally {
    libraryLoading.value = false
  }
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
const selectLibraryAsset = (type, asset) => {
  if (type === 'avatar') {
    if (!avatarFile.value && imageSlotsRemaining.value <= 0) return
    if (avatarFile.value?.url?.startsWith('blob:')) URL.revokeObjectURL(avatarFile.value.url)
    avatarFile.value = { name: asset.name, url: asset.url || asset.image, assetId: asset.assetId, type: 'IMAGE' }
    return
  }
  if (type === 'voice') {
    if (audioSlotsRemaining.value <= 0) return
    const selected = { name: asset.name, url: asset.url || asset.image, assetId: asset.assetId, type: 'AUDIO' }
    if (!voiceFile.value) voiceFile.value = selected
    else extraVoices.value.push(selected)
    return
  }
  if (type === 'video') {
    if (videoSlotsRemaining.value <= 0) return
    referenceVideos.value = [...referenceVideos.value, { name: asset.name, url: asset.url || asset.image, assetId: asset.assetId, type: 'VIDEO' }]
    return
  }
  if (imageSlotsRemaining.value <= 0) return
  const selected = { name: asset.name, url: asset.url || asset.image, assetId: asset.assetId, type: 'IMAGE' }
  if (!productFile.value) productFile.value = selected
  else if (!packageFile.value) packageFile.value = selected
  else extraImages.value.push(selected)
}
const clearUpload = (type) => {
  const target = { avatar: avatarFile, product: productFile, package: packageFile, voice: voiceFile }[type]
  revokeBlob(target.value)
  target.value = null
}
const removeExtraImage = index => { revokeBlob(extraImages.value[index]); extraImages.value.splice(index, 1) }
const removeReferenceVideo = index => { revokeBlob(referenceVideos.value[index]); referenceVideos.value.splice(index, 1) }
const removeExtraVoice = index => { revokeBlob(extraVoices.value[index]); extraVoices.value.splice(index, 1) }
const selectLibraryItem = asset => {
  selectLibraryAsset(libraryType.value === 'audio' ? 'voice' : libraryType.value === 'video' ? 'video' : libraryTarget.value === 'avatar' ? 'avatar' : 'asset', asset)
  closeAssetLibrary()
}
const generate = () => {
  if (isGenerating.value) return
  notice.value = ''
  try {
    const input = digitalHumanVideoInput({ script: script.value, avatar: avatarFile.value,
      images: [productFile.value, packageFile.value, ...extraImages.value].filter(Boolean),
      videos: referenceVideos.value, voices: [voiceFile.value, ...extraVoices.value].filter(Boolean),
      model: selectedModel.value, ratio: ratio.value, durationSeconds: duration.value, resolution: resolution.value })
    start(input)
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
        <fieldset class="dh-form-scroll" :disabled="isGenerating">
          <section class="dh-form-section dh-asset-section"><div class="dh-section-heading"><div><h2>上传数字人形象</h2></div><span class="dh-section-note">可选</span></div><div class="dh-upload-actions"><button type="button" class="dh-source-button" @click="avatarInput?.click()"><span class="material-symbols-outlined">upload</span><span>从本地上传</span></button><button type="button" class="dh-source-button" @click="openAssetLibrary('image', 'avatar')"><span class="material-symbols-outlined">photo_library</span><span>从素材库上传</span></button></div><div v-if="avatarFile" class="dh-upload-preview"><img :src="avatarFile.url" alt="已选数字人形象"><span class="dh-upload-file">{{ avatarFile.name }}</span><button type="button" aria-label="移除数字人形象" @click="clearUpload('avatar')">×</button></div><input ref="avatarInput" class="video-file-input" type="file" accept="image/*" @change="setUpload('avatar', $event)"><p class="dh-field-hint">支持 JPG、PNG、WEBP · 大小 6MB 以内</p></section>
          <section class="dh-form-section dh-asset-section"><div class="dh-section-heading"><div><h2>上传所需图片</h2></div><span class="dh-section-note">可选 · {{ materialLimits.image }} 张以内</span></div><div class="dh-upload-actions"><button type="button" class="dh-source-button" @click="assetsInput?.click()"><span class="material-symbols-outlined">upload</span><span>从本地上传</span></button><button type="button" class="dh-source-button" @click="openAssetLibrary('image', 'assets')"><span class="material-symbols-outlined">photo_library</span><span>从素材库上传</span></button></div><div v-if="productFile || packageFile || extraImages.length" class="dh-upload-preview-list"><div v-for="asset in [productFile, packageFile].filter(Boolean)" :key="asset.name" class="dh-upload-preview"><img :src="asset.url" :alt="asset.name"><span class="dh-upload-file">{{ asset.name }}</span><button type="button" aria-label="移除参考图片" @click="clearUpload(asset === productFile ? 'product' : 'package')">×</button></div><div v-for="(asset, index) in extraImages" :key="asset.name + index" class="dh-upload-preview"><img :src="asset.url" :alt="asset.name"><span class="dh-upload-file">{{ asset.name }}</span><button type="button" aria-label="移除参考图片" @click="removeExtraImage(index)">×</button></div></div><input ref="assetsInput" class="video-file-input" type="file" accept="image/*" multiple @change="setUpload('assets', $event)"></section>
          <section class="dh-form-section dh-asset-section"><div class="dh-section-heading"><div><h2>上传参考视频</h2></div><span class="dh-section-note">可选 · {{ materialLimits.video }} 个以内</span></div><div class="dh-upload-actions"><button type="button" class="dh-source-button" @click="referenceVideoInput?.click()"><span class="material-symbols-outlined">upload</span><span>从本地上传</span></button><button type="button" class="dh-source-button" @click="openAssetLibrary('video')"><span class="material-symbols-outlined">video_library</span><span>从素材库上传</span></button></div><div v-if="referenceVideos.length" class="dh-upload-preview-list"><div v-for="(asset, index) in referenceVideos" :key="asset.name + index" class="dh-upload-preview"><span class="material-symbols-outlined">movie</span><span class="dh-upload-file">{{ asset.name }}</span><button type="button" aria-label="移除参考视频" @click="removeReferenceVideo(index)">×</button></div></div><input ref="referenceVideoInput" class="video-file-input" type="file" accept="video/*" multiple @change="setUpload('video', $event)"></section>
          <section class="dh-form-section dh-asset-section"><div class="dh-section-heading"><div><h2>上传音色</h2></div><span class="dh-section-note">可选 · {{ materialLimits.audio }} 个以内</span></div><div class="dh-upload-actions"><button type="button" class="dh-source-button" @click="voiceInput?.click()"><span class="material-symbols-outlined">upload</span><span>从本地上传</span></button><button type="button" class="dh-source-button" @click="openAssetLibrary('audio')"><span class="material-symbols-outlined">library_music</span><span>从素材库上传</span></button></div><div v-if="voiceFile || extraVoices.length" class="dh-upload-preview-list"><div v-if="voiceFile" class="dh-upload-preview dh-upload-audio"><span class="material-symbols-outlined">graphic_eq</span><span class="dh-upload-file">{{ voiceFile.name }}</span><button type="button" aria-label="移除音色" @click="clearUpload('voice')">×</button></div><div v-for="(asset, index) in extraVoices" :key="asset.name + index" class="dh-upload-preview dh-upload-audio"><span class="material-symbols-outlined">graphic_eq</span><span class="dh-upload-file">{{ asset.name }}</span><button type="button" aria-label="移除音色" @click="removeExtraVoice(index)">×</button></div></div><input ref="voiceInput" class="video-file-input" type="file" accept="audio/*" multiple @change="setUpload('voice', $event)"><p class="dh-field-hint">支持 MP3、WAV、M4A · 建议 10 秒以上清晰人声</p></section>
          <section class="dh-form-section dh-copy-section"><div class="dh-section-heading"><div><h2>描述内容</h2></div><span class="dh-counter">{{ script.length }}/500</span></div><div class="dh-textarea-shell"><textarea v-model="script" maxlength="500" placeholder="输入视频文案或商品描述，例如：今天给大家分享一款适合敏感肌的春日精华，轻薄好吸收，换季也能保持水润光泽。" /></div></section>
          <section class="dh-form-section dh-setting-section"><div class="dh-section-heading"><div><h2>模型与输出</h2></div><span class="dh-section-note">{{ currentModel.label }} · {{ materialLimitLabel }}</span></div><div class="video-model-grid dh-model-grid"><button v-for="model in videoModels" :key="model.id" type="button" :class="{ selected: selectedModel === model.id }" @click="selectModel(model.id)"><strong>{{ model.label }}</strong></button></div><label class="video-ratio-select dh-ratio-select"><span class="video-setting-label">画面比例</span><select v-model="ratio" aria-label="选择画面比例"><option v-for="option in ratioOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label><div class="dh-setting-row dh-duration-setting"><span>视频时长</span><strong>{{ duration }} 秒</strong></div><input v-model.number="duration" class="video-duration-range" type="range" min="5" :max="currentModel.maxDurationSeconds" step="1"><div class="video-range-meta flex justify-between"><span>5s</span><span>{{ currentModel.maxDurationSeconds }}s</span></div><div class="dh-setting-row dh-resolution-setting"><span>分辨率</span><div class="video-resolution-grid"><button v-for="item in availableResolutions" :key="item" type="button" :class="{ selected: resolution === item }" @click="resolution = item">{{ item }}</button></div></div></section>
        </fieldset>
        <div class="dh-form-footer"><button class="dh-generate-button" type="button" :disabled="isGenerating" @click="generate"><span>{{ isGenerating ? '正在生成…' : previewStarted ? '再次生成' : '立即生成' }}</span><span class="material-symbols-outlined">arrow_forward</span></button><p v-if="notice || videoNotice" class="dh-notice" :class="{ 'is-error': videoError }" role="status">{{ notice || videoNotice }}</p><small>预计消耗 12 算力 · 约 1–2 分钟完成</small></div>
      </aside>

      <VideoPreviewPanel
        v-if="previewStarted"
        class="dh-preview-panel dh-result-panel"
        :is-generating="isGenerating"
        :output-url="outputUrl"
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
      <section v-else class="dh-preview-panel" aria-label="数字人视频预览">
        <div class="dh-studio-switcher video-mode-switch" role="group" aria-label="切换工作台">
          <button type="button" @click="switchWorkspace('/video/workbench')"><span class="material-symbols-outlined">auto_awesome</span>视频工作台</button>
          <button type="button" class="active" aria-pressed="true" @click="switchWorkspace('/digital-human/studio')"><span class="material-symbols-outlined">record_voice_over</span>数字人摄影棚</button>
        </div>
        <div class="dh-preview-intro"><h2>上传模特与商品素材，<br><em>智能生成口播带货视频</em></h2><p>快去左侧创建你的灵感吧～</p></div><div class="dh-banner" :class="ratio === '9:16' ? 'banner-portrait' : 'banner-landscape'"><div class="dh-banner-image dh-banner-person"><img :src="avatarFile?.url || digitalHumanAvatars[0].image" alt="数字人示例"><span class="dh-banner-tag">数字人出镜</span></div><div class="dh-banner-image dh-banner-product"><img :src="packageFile?.url || productFile?.url || mockProduct" alt="商品示例"><span class="dh-banner-tag">商品卖点</span></div><div class="dh-banner-glow" /></div><div class="dh-inspiration"><div class="dh-inspiration-heading"><div><h3>没有灵感？试试方志原创数字人形象</h3></div><button type="button" @click="showAllAvatars = true">查看全部 <span class="material-symbols-outlined">arrow_forward</span></button></div><div class="dh-inspiration-grid"><button v-for="item in digitalHumanAvatars.slice(0, 4)" :key="item.id" class="dh-inspiration-card" type="button" @click="selectLibraryAsset('avatar', item)"><span class="dh-inspiration-thumb"><img :src="item.image" :alt="item.label"></span><span><strong>{{ item.label }}</strong><small>{{ item.name }}</small></span></button></div></div></section>
    </main>
    <Transition name="dh-avatar-modal" appear>
      <div v-if="showAllAvatars" class="dh-avatar-modal" role="dialog" aria-modal="true" aria-label="全部数字人形象" @click.self="showAllAvatars = false">
        <Transition name="dh-avatar-modal-card" appear>
          <div class="dh-avatar-modal-card">
            <div class="dh-avatar-modal-header"><h2>方志原创数字人形象</h2><button type="button" aria-label="关闭" @click="showAllAvatars = false">×</button></div>
            <div class="dh-avatar-modal-grid"><button v-for="item in digitalHumanAvatars" :key="item.id" type="button" class="dh-avatar-modal-item" @click="selectLibraryAsset('avatar', item); showAllAvatars = false"><span class="dh-avatar-modal-thumb"><img :src="item.image" :alt="item.label"></span><span><strong>{{ item.label }}</strong><small>{{ item.name }}</small></span></button></div>
          </div>
        </Transition>
      </div>
    </Transition>
    <Transition name="dh-avatar-modal" appear>
      <div v-if="libraryOpen" class="dh-asset-library-modal" role="dialog" aria-modal="true" aria-label="选择平台素材" @click.self="closeAssetLibrary">
        <section class="dh-asset-library-card">
          <header class="dh-asset-library-header"><div><p class="studio-kicker">YIFANGZHI LIBRARY</p><h2>{{ libraryType === 'audio' ? '选择音色' : libraryType === 'video' ? '选择参考视频' : libraryTarget === 'avatar' ? '选择数字人形象' : '选择图片素材' }}</h2><small>从一方志素材库中选择已上传内容</small></div><button type="button" aria-label="关闭素材库" @click="closeAssetLibrary">×</button></header>
          <label class="dh-asset-library-search"><span class="material-symbols-outlined">search</span><input v-model="libraryQuery" autofocus type="search" placeholder="搜索素材名称" /></label>
          <div v-if="libraryLoading" class="dh-asset-library-state" role="status">正在读取素材库…</div>
          <div v-else-if="libraryError" class="dh-asset-library-state is-error" role="status">{{ libraryError }}</div>
          <div v-else-if="!visibleLibraryAssets.length" class="dh-asset-library-state">暂时没有可用的{{ libraryType === 'audio' ? '音色' : libraryType === 'video' ? '参考视频' : libraryTarget === 'avatar' ? '数字人形象' : '图片素材' }}。</div>
          <div v-else class="dh-asset-library-grid"><button v-for="asset in visibleLibraryAssets" :key="asset.id || asset.name" type="button" class="dh-asset-library-item" @click="selectLibraryItem(asset)"><span class="dh-asset-library-thumb"><img v-if="asset.url && asset.type === 'IMAGE'" :src="asset.url" :alt="asset.name"><span v-else class="material-symbols-outlined">{{ asset.type === 'AUDIO' ? 'graphic_eq' : asset.type === 'VIDEO' ? 'movie' : 'image' }}</span></span><span class="dh-asset-library-copy"><strong>{{ asset.name }}</strong><small>{{ asset.type === 'AUDIO' ? '音色素材' : asset.type === 'VIDEO' ? '参考视频' : '图片素材' }}</small></span><span class="material-symbols-outlined">arrow_forward</span></button></div>
        </section>
      </div>
    </Transition>
  </div>
</template>
