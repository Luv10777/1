// Keep local product records small enough for browser storage.
export async function readProductImage(file) {
  if (!['image/jpeg', 'image/png'].includes(file.type)) throw new Error('请选择 JPG 或 PNG 格式的图片。')
  if (file.size > 5 * 1024 * 1024) throw new Error('图片不能超过 5MB，请选择较小的图片。')
  const url = URL.createObjectURL(file)
  try {
    const image = new Image()
    image.src = url
    await image.decode()
    const scale = Math.min(1, 640 / Math.max(image.naturalWidth, image.naturalHeight))
    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.round(image.naturalWidth * scale))
    canvas.height = Math.max(1, Math.round(image.naturalHeight * scale))
    const context = canvas.getContext('2d')
    if (!context) throw new Error('无法处理图片')
    context.fillStyle = '#ffffff'
    context.fillRect(0, 0, canvas.width, canvas.height)
    context.drawImage(image, 0, 0, canvas.width, canvas.height)
    return canvas.toDataURL('image/jpeg', 0.85)
  } catch {
    throw new Error('图片读取失败，请重新选择有效的 JPG 或 PNG 图片。')
  } finally {
    URL.revokeObjectURL(url)
  }
}
