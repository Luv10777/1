<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Aurora from '../components/reactbits/Aurora.vue'
import BlurText from '../components/reactbits/BlurText.vue'
import ShinyText from '../components/reactbits/ShinyText.vue'
import SplashCursor from '../components/reactbits/SplashCursor.vue'

const prompt = ref('')
const format = ref('9:16')
const duration = ref(10)
const assets = ref([])
const fileInput = ref(null)
const isGenerating = ref(false)
const notice = ref('')
const router = useRouter()
const isMorphing = ref(false)
let morphTimer

const addFiles = (fileList) => {
  const images = Array.from(fileList || []).filter(file => file.type.startsWith('image/'))
  assets.value = [...assets.value, ...images.map(file => ({ id: `${file.name}-${file.lastModified}-${Math.random()}`, name: file.name, url: URL.createObjectURL(file) }))]
}
const handleFileChange = (event) => { addFiles(event.target.files); event.target.value = '' }
const handleDrop = (event) => { event.preventDefault(); addFiles(event.dataTransfer.files) }
const removeAsset = (asset) => { URL.revokeObjectURL(asset.url); assets.value = assets.value.filter(item => item.id !== asset.id) }
const generate = () => {
  isGenerating.value = !isGenerating.value
  notice.value = isGenerating.value ? '星枢正在运作，设备边缘光效已启动。' : '已暂停本次生成，可随时继续。'
}
const switchWorkspace = (path) => {
  if (path === router.currentRoute.value.path) return
  if (typeof document.startViewTransition === 'function') {
    document.startViewTransition(() => router.push(path))
    return
  }
  router.push(path)
}
onBeforeUnmount(() => assets.value.forEach(asset => URL.revokeObjectURL(asset.url)))
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
      <div class="video-workbench-scroll flex-1 overflow-y-auto p-8 space-y-8">
        <div class="video-form-section">
          <div class="video-form-heading flex justify-between items-center mb-3">
            <h2 class="text-sm font-semibold text-gray-800">添加商品或套餐图</h2>
            <span class="text-xs text-gray-400">{{ assets.length }} 张</span>
          </div>
          <button class="video-upload-zone w-full h-32 bg-gray-50 rounded-2xl border border-gray-200 hover:border-indigo-300 hover:bg-indigo-50/30 transition-all flex flex-col items-center justify-center gap-2 group" type="button" @click="fileInput?.click()" @dragover.prevent @drop="handleDrop">
            <span class="material-symbols-outlined text-indigo-500 group-hover:scale-110 transition-transform">add_photo_alternate</span>
            <strong class="text-sm font-medium text-gray-600">+ 添加图片</strong>
            <small class="text-xs text-gray-400">支持 JPG、PNG · 可拖拽上传</small>
          </button>
          <input ref="fileInput" class="video-file-input" type="file" accept="image/*" multiple @change="handleFileChange">
          <div v-if="assets.length" class="video-asset-strip flex overflow-x-auto gap-2 mt-3">
            <div v-for="asset in assets" :key="asset.id" class="video-asset-thumb relative">
              <img :src="asset.url" :alt="asset.name">
              <button type="button" aria-label="删除图片" @click="removeAsset(asset)">×</button>
            </div>
          </div>
        </div>

        <div class="video-form-section">
          <div class="video-form-heading flex justify-between items-center mb-3">
            <h2 class="text-sm font-semibold text-gray-800">描述你的成片需求</h2>
            <span class="text-xs text-gray-400">{{ prompt.length }}/500</span>
          </div>
          <div class="video-textarea-shell bg-gray-50/80 rounded-xl p-1 border border-gray-100 focus-within:border-indigo-400 focus-within:ring-4 focus-within:ring-indigo-500/10 focus-within:bg-white transition-all">
            <textarea v-model="prompt" maxlength="500" class="w-full h-32 bg-transparent resize-none outline-none p-3 text-sm text-gray-800 placeholder-gray-400" placeholder="例如：为这份双人海鲜套餐制作一段诱人的展示视频，镜头从特写拉远，强调食材的新鲜与就餐的松弛感。" />
          </div>
        </div>

        <div class="video-form-section video-settings-section">
          <div class="video-form-heading flex justify-between items-center mb-3">
            <h2 class="text-sm font-semibold text-gray-800">画面与时长</h2>
          </div>
          <div class="video-segmented bg-gray-100 p-1 rounded-xl flex gap-1 mb-6">
            <button type="button" :class="{ selected: format === '9:16' }" @click="format = '9:16'">9:16 (竖屏)</button>
            <button type="button" :class="{ selected: format === '16:9' }" @click="format = '16:9'">16:9 (横屏)</button>
          </div>
          <div class="video-setting-block">
            <div class="video-duration-label flex items-center justify-between">
              <span class="video-setting-label">视频时长</span>
              <strong>{{ duration }} 秒</strong>
            </div>
            <input v-model.number="duration" class="video-duration-range" :style="{ '--duration-progress': `${((duration - 5) / 10) * 100}%` }" type="range" min="5" max="15" step="1">
            <div class="video-range-meta flex justify-between"><span>5s</span><span>15s</span></div>
          </div>
        </div>
      </div>

      <div class="video-workbench-action p-6 border-t border-gray-100">
        <button class="video-generate-button w-full py-3.5 bg-[#18181B] hover:bg-black text-white text-sm font-medium rounded-xl shadow-lg shadow-black/20 ring-1 ring-inset ring-white/10 transition-all flex justify-center items-center gap-2" type="button" :aria-pressed="isGenerating" @click="generate">
          <ShinyText class-name="video-button-shine" :speed="3" :text="isGenerating ? '正在排队…' : '开始生成视频'" />
          <span>→</span>
        </button>
        <p v-if="notice" class="video-generate-notice" role="status">{{ notice }}</p>
      </div>
    </div>

    <div class="video-workbench-canvas relative flex-1 bg-[#0A0A0B] flex flex-col items-center justify-center overflow-hidden" aria-label="成片预览">
      <div class="absolute inset-0 z-0">
        <Aurora :color-stops="['#000000', '#312e81', '#1e1b4b']" :blend="0.8" :speed="0.4" />
        <div class="video-canvas-veil absolute inset-0 bg-black/80 backdrop-blur-[60px]" />
        <div class="absolute inset-0 bg-[radial-gradient(#ffffff22_1px,transparent_1px)] bg-[size:24px_24px] opacity-20" />
      </div>

      <div class="video-canvas-heading absolute top-6 w-full px-8 z-10 flex justify-between items-center pointer-events-none">
        <h2 class="text-gray-400 font-medium">成片预览</h2>
        <div class="video-mode-switch" role="group" aria-label="切换工作台">
          <button type="button" class="active" aria-pressed="true" @click="switchWorkspace('/video/workbench')"><span class="material-symbols-outlined">auto_awesome</span>视频工作台</button>
          <button type="button" @click="switchWorkspace('/digital-human/studio')"><span class="material-symbols-outlined">record_voice_over</span>数字人摄影棚</button>
        </div>
      </div>

      <div class="video-player device-player relative z-10 overflow-hidden shadow-[0_0_120px_rgba(99,102,241,0.15)] transition-all duration-700 ease-[cubic-bezier(0.23,1,0.32,1)]" :class="[format === '9:16' ? 'is-portrait device-phone' : 'is-landscape device-browser', { 'is-morphing': isMorphing, 'is-generating': isGenerating }]">
        <div v-if="isGenerating" class="device-glow-layer" aria-hidden="true" />
        <div v-else class="device-static-border" :class="format === '9:16' ? 'is-phone-border' : 'is-browser-border'" aria-hidden="true" />

        <div class="device-screen relative z-10 w-full h-full bg-[#0A0A0B] flex flex-col justify-center items-center overflow-hidden transition-all duration-700 ease-[cubic-bezier(0.23,1,0.32,1)]" :class="format === '9:16' ? 'rounded-[calc(2.5rem-2px)]' : 'rounded-[calc(0.75rem-2px)]'">
        <div class="device-phone-ui absolute inset-0 pointer-events-none transition-opacity duration-500" :class="format === '9:16' ? 'is-visible' : 'is-hidden'">
          <div class="device-island absolute top-3 left-1/2 -translate-x-1/2 w-24 h-7 bg-black rounded-full shadow-[inset_0_-1px_2px_rgba(255,255,255,0.1)] z-30" />
          <div class="device-home absolute bottom-3 left-1/2 -translate-x-1/2 w-32 h-1.5 bg-white/20 rounded-full z-30" />
        </div>

        <div class="device-mac-ui absolute top-0 left-0 w-full h-10 bg-white/[0.02] border-b border-white/5 flex items-center px-4 gap-1.5 pointer-events-none z-30 transition-opacity duration-500" :class="format === '16:9' ? 'is-visible' : 'is-hidden'">
          <div class="device-dot device-dot-red w-2.5 h-2.5 rounded-full" />
          <div class="device-dot device-dot-yellow w-2.5 h-2.5 rounded-full" />
          <div class="device-dot device-dot-green w-2.5 h-2.5 rounded-full" />
        </div>

          <div class="device-state-content relative z-20 flex flex-col items-center gap-4 mt-8">
            <span class="device-film-icon text-indigo-400/80 transition-transform duration-700 hover:scale-110">
              <svg v-if="!isGenerating" class="w-12 h-12" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M7 4v16M17 4v16M3 8h4m10 0h4M3 12h18M3 16h4m10 0h4M4 20h16a1 1 0 001-1V5a1 1 0 00-1-1H4a1 1 0 00-1 1v14a1 1 0 001 1z" /></svg>
              <svg v-else class="w-12 h-12 device-loading-icon" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M19.428 15.428a2 2 0 00-1.022-.547l-2.387-.477a6 6 0 00-3.86.517l-.318.158a6 6 0 01-3.86.517L6.05 15.21a2 2 0 00-1.806.547M8 4h8l-1 1v5.172a2 2 0 00.586 1.414l5 5c1.26 1.26.367 3.414-1.415 3.414H4.828c-1.782 0-2.674-2.154-1.414-3.414l5-5A2 2 0 009 10.172V5L8 4z" /></svg>
            </span>
            <div class="device-copy text-center space-y-2">
              <Transition name="device-state" mode="out-in">
                <strong v-if="isGenerating" key="loading" class="text-lg font-medium text-gray-200 tracking-wide"><ShinyText class-name="device-loading-text" :speed="2.4" text="✨ 星枢运作中..." /></strong>
                <strong v-else key="idle" class="text-lg font-medium text-gray-200 tracking-wide"><BlurText animate-by="words" :delay="50" direction="bottom" text="你的故事将在这里成片" /></strong>
              </Transition>
              <p class="text-xs text-gray-500 transition-opacity duration-300" :class="isGenerating ? 'opacity-0' : 'opacity-100'">添加素材并描述需求，开始生成</p>
            </div>
          </div>
        </div>
      </div>

      <div class="video-canvas-hint absolute bottom-8 z-10 text-xs text-gray-600 tracking-wider pointer-events-none"><ShinyText class-name="text-indigo-400/80" :speed="3" text="AI 将自动匹配镜头节奏、字幕与品牌色" /></div>
      <SplashCursor :opacity="0.12" />
    </div>
  </div>
</template>
