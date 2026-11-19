import { get, post } from '../utils/request.js'
import { generationWalletPath } from '../domain/billing.js'

/** 灵感绘图「灵感方案」：看参考图出 3 个画面描述，每次（含换一批）1 烟火，失败退回。 */
export const imageInspirationApi = {
  config: () => get('/api/image/inspirations/config'),
  create: (request, wallet) => post(generationWalletPath('/api/image/inspirations', wallet), request),
  get: id => get(`/api/image/inspirations/${id}`),
  history: page => get('/api/image/inspirations', { page, size: 10 }),
}

export const isImageInspirationDone = batch => ['SUCCEEDED', 'FAILED'].includes(batch?.status)

/** 左侧选「帮我搭配」时不算选了风格。 */
export const inspirationStyle = style => (style && style !== '帮我搭配' ? style : '')

/**
 * 复用灵感记录时要换回的参考图：画面描述按「图1、图2」写，左侧图片和当初不同才换，顺序保持一致；
 * 缺失图片时不压缩序号，要求重新出方案。返回 null 表示不用动左侧图片。
 */
export function inspirationRecordFiles(record, currentFiles) {
  if (!record?.images?.length) return null
  if (record.images.some(image => !image.url)) throw new Error('方案的参考图已不可用，请重新添加素材并生成方案，避免图序号错位。')
  if (record.images.map(image => image.assetId).join() === currentFiles.map(file => file.assetId).join()) return null
  return record.images
    .map(image => ({ file: null, name: image.name, role: 'SUBJECT', preview: image.url, assetId: image.assetId }))
}
