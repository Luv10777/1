export const IMAGE_QUALITIES = ['480P', '720P', '1080P', '4K']
export const IMAGE_RATIOS = ['1:1', '3:4', '4:3', '9:16', '16:9', '2:3', '3:2']
export const POSTER_PURPOSE_GROUPS = [
  { label: '线上分享', options: [
    { label: '朋友圈活动宣传', ratio: '3:4' },
    { label: '微信群 / 社群通知', ratio: '3:4' },
    { label: '小红书笔记封面', ratio: '3:4' },
    { label: '抖音短视频封面', ratio: '9:16' },
    { label: '视频号分享海报', ratio: '3:4' },
  ] },
  { label: '本地生活平台', options: [
    { label: '抖音团购宣传', ratio: '3:4' },
    { label: '美团点评团购主图', ratio: '1:1' },
    { label: '外卖平台活动图', ratio: '1:1' },
  ] },
  { label: '门店线下', options: [
    { label: '门店竖版海报', ratio: '3:4' },
    { label: '门店横版电子屏', ratio: '16:9' },
    { label: '门口立牌 / 易拉宝', ratio: '9:16' },
    { label: '桌贴 / 台卡', ratio: '1:1' },
  ] },
]
export const IMAGE_STATUS = {
  QUEUED: '等待处理', PLANNING: '正在规划文案与画面', GENERATING: '正在生成或接收图片',
  SAVING: '正在保存图片', SUCCEEDED: '已完成', PARTIAL: '部分完成',
  NEEDS_INPUT: '需要补充一句', FAILED: '生成失败', INTERRUPTED: '执行中断', UPSTREAM_UNKNOWN: '等待核对中转站结果', CANCELLED: '已取消',
}
export const isImageActive = status => ['QUEUED', 'PLANNING', 'GENERATING', 'SAVING'].includes(status)
export function supportsImageSize(capabilities, quality, ratio) {
  if (!capabilities) return true
  return capabilities.qualities.includes(quality)
    && (!capabilities.qualityRatios || !!capabilities.qualityRatios[quality]?.includes(ratio))
}
export function imageDimensions(quality, ratio) {
  if (!IMAGE_QUALITIES.includes(quality) || !IMAGE_RATIOS.includes(ratio)) throw new Error('不支持的画质或比例')
  const [x, y] = ratio.split(':').map(Number)
  const base = { '480P': 480, '720P': 720, '1080P': 1080, '4K': 3840 }[quality]
  const scale = base / (quality === '4K' ? Math.max(x, y) : Math.min(x, y))
  return { width: Math.round(x * scale), height: Math.round(y * scale) }
}
export function validateImageFile(file) {
  if (!['image/jpeg', 'image/png'].includes(file.type)) throw new Error('请选择 JPG 或 PNG 图片')
  if (file.size > 20 * 1024 * 1024) throw new Error('每张图片不能超过 20 MB')
  if (file.size === 0) throw new Error('不能上传空文件')
}
