// Use a local blob URL so Mac and Windows browsers save cross-origin media instead of opening it.
export async function downloadMedia(url, filename) {
  // Previews may have cached this URL without CORS; request a fresh, readable response.
  const response = await fetch(url, { cache: 'no-store' })
  if (!response.ok) throw new Error('下载地址可能已过期，请刷新记录后重试')
  const objectUrl = URL.createObjectURL(await response.blob())
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
