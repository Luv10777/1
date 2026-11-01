export const IMAGE_QUALITIES = ['1K', '2K', '4K']
export const IMAGE_RATIOS = ['1:1', '3:4', '4:3', '9:16', '16:9', '2:3', '3:2']
export const IMAGE_STATUS = {
  QUEUED: '等待处理', PLANNING: '正在理解需求与规划画面', GENERATING: '正在生成或接收图片',
  SAVING: '正在保存图片', SUCCEEDED: '已完成', PARTIAL: '部分完成',
  NEEDS_INPUT: '需要补充一句', FAILED: '生成失败', INTERRUPTED: '执行中断', UPSTREAM_UNKNOWN: '等待核对中转站结果', CANCELLED: '已取消',
}
export const isImageActive = status => ['QUEUED', 'PLANNING', 'GENERATING', 'SAVING'].includes(status)
export function supportsImageSize(capabilities, quality, ratio) {
  try { imageDimensions(quality, ratio) } catch { return false }
  if (!capabilities) return true
  return capabilities.qualities?.includes(quality) === true
    && (!capabilities.qualityRatios || !!capabilities.qualityRatios[quality]?.includes(ratio))
}
export function imageDimensions(quality, ratio) {
  if (!IMAGE_QUALITIES.includes(quality) || !IMAGE_RATIOS.includes(ratio)) throw new Error('不支持的画质或比例')
  const sizes = {
    '1K': { '1:1': [1024, 1024], '3:2': [1536, 1024], '2:3': [1024, 1536] },
    '2K': { '1:1': [2048, 2048], '16:9': [2048, 1152], '9:16': [1152, 2048] },
    '4K': { '16:9': [3840, 2160], '9:16': [2160, 3840] },
  }
  let size = sizes[quality]?.[ratio]
  if (!size && quality !== '4K') {
    const [x, y] = ratio.split(':').map(Number)
    const base = quality === '1K' ? 1024 : 2048
    const scale = base / (['3:4', '4:3'].includes(ratio) ? Math.max(x, y) : Math.min(x, y))
    size = [Math.round(x * scale), Math.round(y * scale)]
  }
  if (!size) throw new Error('该画质不支持此比例')
  return { width: size[0], height: size[1] }
}
export function validateImageFile(file) {
  if (!['image/jpeg', 'image/png'].includes(file.type)) throw new Error('请选择 JPG 或 PNG 图片')
  if (file.size > 20 * 1024 * 1024) throw new Error('每张图片不能超过 20 MB')
  if (file.size === 0) throw new Error('不能上传空文件')
}
