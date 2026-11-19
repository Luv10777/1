// 保存生成的图片、视频：
// - 电脑上的 Chrome / Edge：弹出「另存为」，自己选文件夹和文件名；
// - 苹果设备（iPhone、iPad、Mac 上的 Safari）：弹出系统分享面板，选「存储视频 / 存储图像 / 添加到照片」进相册；
// - 其他浏览器（Firefox、安卓等）：直接下载到「下载」文件夹，安卓相册里能看到。
// 取文件用 blob，跨域的作品地址才会保存而不是在新窗口打开。

const MIME = { mp4: 'video/mp4', mov: 'video/quicktime', webm: 'video/webm', png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', webp: 'image/webp' }
const isAbort = error => error?.name === 'AbortError'
const isApple = () => /iPhone|iPad|iPod|Macintosh/.test(globalThis.navigator?.userAgent || '')
const canPickFile = () => typeof globalThis.window?.showSaveFilePicker === 'function' && !/Android|iPhone|iPad|iPod/.test(globalThis.navigator?.userAgent || '')

// 分享面板必须在点击后很短时间内弹出；文件大、下载慢时浏览器会拒绝，这时把文件留着，再点一次直接弹出。
const prepared = new Map()

async function fetchBlob(url) {
  // Previews may have cached this URL without CORS; request a fresh, readable response.
  const response = await fetch(url, { cache: 'no-store' })
  if (!response.ok) throw new Error('下载地址可能已过期，请刷新记录后重试')
  return response.blob()
}

function saveWithLink(blob, filename) {
  const objectUrl = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = filename
  document.body.appendChild(link)
  try {
    link.click()
  } finally {
    link.remove()
    setTimeout(() => URL.revokeObjectURL(objectUrl), 60_000)
  }
}

async function share(file, key) {
  try {
    await navigator.share({ files: [file] })
    prepared.delete(key)
  } catch (error) {
    if (isAbort(error)) { prepared.delete(key); return }
    if (error?.name === 'NotAllowedError') { prepared.set(key, file); throw new Error('文件已准备好，请再点一次「下载」保存到相册') }
    throw error
  }
}

/** 保存一个作品文件；用户在另存为或分享面板里取消时安静结束。 */
export async function downloadMedia(url, filename) {
  const key = url + '\n' + filename
  if (prepared.has(key)) return share(prepared.get(key), key)

  const extension = filename.split('.').pop().toLowerCase()
  const type = MIME[extension] || 'application/octet-stream'
  if (canPickFile()) {
    // 先弹「另存为」再取文件：弹窗要趁点击还算数的时候打开。
    let handle
    try {
      handle = await window.showSaveFilePicker({ suggestedName: filename, types: MIME[extension] ? [{ description: type.startsWith('video') ? '视频' : '图片', accept: { [type]: ['.' + extension] } }] : undefined })
    } catch (error) {
      if (isAbort(error)) return
      handle = null // 浏览器不让用（比如嵌在别的页面里），退回普通下载。
    }
    if (handle) {
      const blob = await fetchBlob(url)
      const writable = await handle.createWritable()
      await writable.write(blob)
      await writable.close()
      return
    }
  }

  const blob = await fetchBlob(url)
  if (isApple() && typeof globalThis.navigator?.canShare === 'function') {
    const file = new File([blob], filename, { type: blob.type || type })
    if (navigator.canShare({ files: [file] })) return share(file, key)
  }
  saveWithLink(blob, filename)
}
