<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { Upload, Video, Images, FolderOpen, Heart, MessageCircle, MapPin, ChevronLeft, ChevronRight, Music2 } from 'lucide-vue-next'
const props = defineProps({
  previewPlatform: { type: String, required: true },
  title: { type: String, default: '' },
  displayBody: { type: String, default: '' },
  poi: { type: String, default: '' },
  currentAccount: { type: Object, default: () => ({}) },
  videoUrl: { type: String, default: '' },
  uploading: Boolean,
  mediaType: { type: String, default: 'video' },
  pictures: { type: Array, default: () => [] },
  pictureIndex: { type: Number, default: 0 },
  music: { type: Object, default: null },
  musicVolume: { type: Number, default: 35 },
  originalVolume: { type: Number, default: 100 },
  musicStart: { type: Number, default: 0 },
})
const emit = defineEmits(['upload', 'library', 'playback-error', 'picture-change', 'music-playing'])
const soundtrack = ref(null)
const musicActive = ref(false)
const hasMedia = computed(() => !!props.videoUrl || props.pictures.length > 0)
const currentPicture = computed(() => props.pictures[props.pictureIndex] || props.pictures[0])
const showOverlay = ref(false)
const playbackError = ref(false)
const player = ref(null)
const previewStage = ref(null)
const phoneSize = ref({ width: 320, height: 644 })
let resizeObserver
watch(() => props.videoUrl, () => { playbackError.value = false; showOverlay.value = false; pauseMusic() })
watch(() => props.mediaType, () => { pauseMusic(); player.value?.pause() })
watch(() => props.music?.url, () => { pauseMusic() }, { flush: 'pre' })
watch(() => props.musicVolume, value => { if (soundtrack.value) soundtrack.value.volume = value / 100 })
watch(() => props.originalVolume, value => { if (player.value) player.value.volume = value / 100 })
watch(() => props.musicStart, () => syncMusic())
function syncMusic() {
  const audio = soundtrack.value
  if (!audio || !Number.isFinite(audio.duration)) return
  const start = Math.min(props.musicStart, Math.max(0, audio.duration - 0.1))
  audio.currentTime = start + (player.value?.currentTime || 0) % Math.max(0.1, audio.duration - start)
}
async function playMusic() {
  const audio = soundtrack.value
  if (!audio) return
  audio.volume = props.musicVolume / 100
  audio.playbackRate = player.value?.playbackRate || 1
  try { await audio.play() }
  catch (error) { if (error.name !== 'AbortError') emit('playback-error', '音乐暂时无法播放，请点击试听或更换音频。') }
}
function pauseMusic() { soundtrack.value?.pause(); musicActive.value = false; emit('music-playing', false) }
function toggleMusic() {
  if (musicActive.value) pauseMusic()
  else { syncMusic(); playMusic() }
}
function videoPlaying() { syncMusic(); playMusic() }
function musicError() { pauseMusic(); emit('playback-error', '背景音乐读取失败，请更换音频。') }
onMounted(() => {
  resizeObserver = new ResizeObserver(([entry]) => {
    const { width, height } = entry.contentRect
    const fittedWidth = Math.max(1, Math.floor(Math.min(340, width - 16, (height - 12) * 320 / 644)))
    phoneSize.value = { width: fittedWidth, height: Math.round(fittedWidth * 644 / 320) }
  })
  if (previewStage.value) resizeObserver.observe(previewStage.value)
})
onBeforeUnmount(() => { resizeObserver?.disconnect(); pauseMusic() })
function captureFrame() {
  const video = player.value
  if (!video || video.readyState < 2 || playbackError.value) throw new Error('请先上传视频并等待画面加载，再截取当前帧。')
  const ratio = Math.min(1, 1080 / Math.max(video.videoWidth, video.videoHeight))
  const canvas = document.createElement('canvas')
  canvas.width = Math.round(video.videoWidth * ratio)
  canvas.height = Math.round(video.videoHeight * ratio)
  canvas.getContext('2d').drawImage(video, 0, 0, canvas.width, canvas.height)
  return canvas.toDataURL('image/jpeg', 0.88)
}
function onPlaybackError() {
  pauseMusic()
  playbackError.value = true
  emit('playback-error', '视频播放失败，请重新选择或转换为 H.264 MP4。')
}
defineExpose({ captureFrame, toggleMusic })
</script>

<template>
  <div ref="previewStage" class="phone-wrap pub-video-stage" :style="{ '--phone-width': phoneSize.width + 'px', '--phone-height': phoneSize.height + 'px' }">
    <audio v-if="music" :key="music.url" ref="soundtrack" :src="music.url" preload="auto" loop @loadedmetadata="syncMusic" @timeupdate="soundtrack.currentTime < musicStart && (soundtrack.currentTime = musicStart)" @play="musicActive = true; emit('music-playing', true)" @pause="musicActive = false; emit('music-playing', false)" @error="musicError" />
    <div class="phone">
      <div class="phone-island" />
      <div class="phone-screen pub-video-screen">
        <div class="pub-device-status" aria-hidden="true"><span>9:41</span><span>5G ▮▮▮</span></div>
        <div class="pub-video-canvas">
          <video v-if="videoUrl && !playbackError" :key="videoUrl" ref="player" :src="videoUrl" class="pub-preview-video" aria-label="作品视频预览" controls playsinline preload="auto" @loadedmetadata="player.volume = originalVolume / 100" @playing="videoPlaying" @pause="pauseMusic" @waiting="pauseMusic" @seeking="pauseMusic" @seeked="syncMusic(); !player.paused && playMusic()" @ratechange="soundtrack && (soundtrack.playbackRate = player.playbackRate)" @ended="pauseMusic" @error="onPlaybackError" />
          <img v-else-if="currentPicture" :src="currentPicture.url" :alt="currentPicture.name" class="pub-preview-picture" />
          <div v-else class="pub-video-empty">
            <component :is="mediaType === 'image' ? Images : Video" :size="32" :stroke-width="1.5" />
            <strong>{{ playbackError ? '视频暂时无法播放' : '在这里预览你的作品' }}</strong>
            <p>{{ mediaType === 'image' ? '添加图片，预览图文效果。' : '添加视频，预览完整画面。' }}</p>
            <button type="button" :disabled="uploading" @click="emit('upload')"><Upload :size="15" />{{ uploading ? '正在读取…' : '本地上传' }}</button>
            <button type="button" class="pub-phone-library" :disabled="uploading" @click="emit('library')"><FolderOpen :size="15" />从素材库选择</button>
          </div>
          <div v-if="hasMedia && !playbackError && showOverlay" class="pub-platform-overlay" aria-label="平台发布效果示意">
            <div class="pub-overlay-copy">
              <b>@{{ currentAccount?.name }}</b>
              <p>{{ previewPlatform === '小红书' ? title : displayBody }}</p>
              <span><MapPin :size="12" />{{ poi }}</span>
            </div>
            <div class="pub-overlay-actions" aria-hidden="true"><Heart :size="20" /><MessageCircle :size="20" /></div>
          </div>
        </div>
        <div v-if="pictures.length" class="pub-image-pager"><button aria-label="上一张图片" :disabled="pictureIndex === 0" @click="emit('picture-change', pictureIndex - 1)"><ChevronLeft :size="16" /></button><span>{{ pictureIndex + 1 }} / {{ pictures.length }}</span><button aria-label="下一张图片" :disabled="pictureIndex >= pictures.length - 1" @click="emit('picture-change', pictureIndex + 1)"><ChevronRight :size="16" /></button></div>
        <div v-if="music" class="pub-phone-music"><Music2 :size="12" /><span>{{ music.name }}</span><span v-if="musicActive" class="pub-music-playing">播放中</span></div>
        <div class="pub-device-toolbar">
          <span>{{ showOverlay ? '发布效果' : mediaType === 'image' ? '图文预览' : '原片预览' }}</span>
          <button type="button" :disabled="!hasMedia || playbackError" :aria-pressed="showOverlay" @click="showOverlay = !showOverlay">{{ showOverlay ? '隐藏平台信息' : '显示平台信息' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>
