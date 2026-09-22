import { ref, onBeforeUnmount } from 'vue'

// Blob URLs are session-only; never persist video files in localStorage.
export function usePublishingVideo() {
  const videoUrl = ref('')
  const videoInfo = ref(null)
  const videoBusy = ref(false)
  const videoError = ref('')
  let cancelProbe
  let disposed = false

  async function selectVideo(file) {
    if (!file || videoBusy.value) return false
    videoError.value = ''
    if (!/\.(mp4|mov|webm)$/i.test(file.name) || (file.type && !file.type.startsWith('video/'))) {
      videoError.value = '请选择 MP4、MOV 或 WebM 视频文件。'
      return false
    }
    if (!file.size || file.size > 500 * 1024 * 1024) {
      videoError.value = '请选择非空且不超过 500 MB 的视频。'
      return false
    }
    videoBusy.value = true
    const url = URL.createObjectURL(file)
    const probe = document.createElement('video')
    let timer
    try {
      probe.muted = true
      probe.preload = 'auto'
      await new Promise((resolve, reject) => {
        cancelProbe = () => reject(new Error('视频读取已取消'))
        probe.onloadeddata = resolve
        probe.onerror = () => reject(new Error('无法解码此视频，请使用 H.264 编码的 MP4 后重试。'))
        timer = setTimeout(() => reject(new Error('视频读取超时，请重新选择文件。')), 15000)
        probe.src = url
      })
      if (!Number.isFinite(probe.duration) || probe.duration <= 0 || !probe.videoWidth) {
        throw new Error('未能读取有效的视频时长或尺寸，请更换文件。')
      }
      const previous = videoUrl.value
      videoInfo.value = { name: file.name, size: file.size, duration: probe.duration, width: probe.videoWidth, height: probe.videoHeight }
      videoUrl.value = url
      if (previous) URL.revokeObjectURL(previous)
      return true
    } catch (error) {
      URL.revokeObjectURL(url)
      if (!disposed) videoError.value = error.message
      return false
    } finally {
      clearTimeout(timer)
      cancelProbe = undefined
      probe.onloadeddata = probe.onerror = null
      probe.removeAttribute('src')
      probe.load()
      videoBusy.value = false
    }
  }

  function removeVideo() {
    if (videoUrl.value) URL.revokeObjectURL(videoUrl.value)
    videoUrl.value = ''
    videoInfo.value = null
    videoError.value = ''
  }

  onBeforeUnmount(() => {
    disposed = true
    cancelProbe?.()
    removeVideo()
  })
  return { videoUrl, videoInfo, videoBusy, videoError, selectVideo, removeVideo }
}
