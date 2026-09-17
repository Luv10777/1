import { ref, onBeforeUnmount } from 'vue'

export const IMAGE_LIMIT = 9
const imageTypes = ['image/jpeg', 'image/png', 'image/webp']

export function usePublishingAttachments() {
  const pictures = ref([])
  const music = ref(null)
  const busy = ref(false)
  const error = ref('')
  let disposed = false
  const pending = new Set()
  const release = item => { if (item?.url) URL.revokeObjectURL(item.url) }

  function probe(file, kind) {
    const url = URL.createObjectURL(file)
    const element = kind === 'image' ? new Image() : document.createElement('audio')
    return new Promise((resolve, reject) => {
      const finish = failure => {
        clearTimeout(timer)
        pending.delete(cancel)
        element.onload = element.onerror = element.onloadeddata = null
        const result = kind === 'image'
          ? { width: element.naturalWidth, height: element.naturalHeight }
          : { duration: element.duration }
        if (kind === 'audio') { element.removeAttribute('src'); element.load() }
        if (failure || disposed) { URL.revokeObjectURL(url); reject(new Error(failure || '已取消读取')) }
        else resolve({ id: crypto.randomUUID(), name: file.name, url, ...result })
      }
      const cancel = () => finish('已取消读取')
      const timer = setTimeout(() => finish('素材读取超时，请重试'), 15000)
      pending.add(cancel)
      element.onerror = () => finish(`无法读取 ${file.name}，请检查文件格式`)
      if (kind === 'image') element.onload = () => finish(element.naturalWidth ? '' : '图片尺寸无效')
      else {
        element.preload = 'auto'
        element.onloadeddata = () => finish(Number.isFinite(element.duration) && element.duration > 0 ? '' : '音频时长无效')
      }
      element.src = url
    })
  }

  async function addPictures(files) {
    if (busy.value || !files.length) return false
    error.value = ''
    if (pictures.value.length + files.length > IMAGE_LIMIT) { error.value = `最多添加 ${IMAGE_LIMIT} 张图片`; return false }
    if (files.some(f => !imageTypes.includes(f.type) || !f.size || f.size > 20 * 1024 * 1024)) {
      error.value = '请选择不超过 20 MB 的 JPG、PNG 或 WebP 图片'; return false
    }
    busy.value = true
    const results = await Promise.allSettled(files.map(file => probe(file, 'image')))
    const valid = results.filter(r => r.status === 'fulfilled').map(r => r.value)
    const failed = results.find(r => r.status === 'rejected')
    if (failed || disposed) { valid.forEach(release); error.value = failed?.reason.message || '已取消读取' }
    else pictures.value.push(...valid)
    busy.value = false
    return !failed && !disposed
  }

  async function selectMusic(file) {
    if (busy.value || !file) return false
    error.value = ''
    if (!/\.(mp3|wav|m4a|ogg|aac)$/i.test(file.name) || !file.size || file.size > 50 * 1024 * 1024) {
      error.value = '请选择 50 MB 以内的 MP3、WAV、M4A、OGG 或 AAC 音频'; return false
    }
    busy.value = true
    try {
      const next = await probe(file, 'audio')
      release(music.value)
      music.value = next
      return true
    } catch (e) { error.value = e.message; return false }
    finally { busy.value = false }
  }
  function removePicture(id) {
    release(pictures.value.find(item => item.id === id))
    pictures.value = pictures.value.filter(item => item.id !== id)
  }
  function movePicture(index, direction) {
    const destination = index + direction
    if (destination < 0 || destination >= pictures.value.length) return
    const next = [...pictures.value]
    ;[next[index], next[destination]] = [next[destination], next[index]]
    pictures.value = next
  }
  function removeMusic() { release(music.value); music.value = null }
  onBeforeUnmount(() => {
    disposed = true
    for (const cancel of [...pending]) cancel()
    pictures.value.forEach(release)
    removeMusic()
  })
  return { pictures, music, busy, error, addPictures, selectMusic, removePicture, movePicture, removeMusic }
}
