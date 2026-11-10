import { get, post } from '../utils/request.js'

/** 灵感绘图「灵感方案」：看参考图出 3 个画面描述，每次（含换一批）3 烟火，失败退回。 */
export const imageInspirationApi = {
  config: () => get('/api/image/inspirations/config'),
  create: request => post('/api/image/inspirations', request),
  get: id => get(`/api/image/inspirations/${id}`),
  history: page => get('/api/image/inspirations', { page, size: 10 }),
}

export const isImageInspirationDone = batch => ['SUCCEEDED', 'FAILED'].includes(batch?.status)

/** 左侧选「帮我搭配」时不算选了风格。 */
export const inspirationStyle = style => (style && style !== '帮我搭配' ? style : '')

/**
 * 复用灵感记录时要换回的参考图：画面描述按「图1、图2」写，左侧图片和当初不同才换，顺序保持一致；
 * 已删除的图片跳过。返回 null 表示不用动左侧图片。
 */
export function inspirationRecordFiles(record, currentFiles) {
  if (!record?.images?.length) return null
  if (record.images.map(image => image.assetId).join() === currentFiles.slice(0, record.images.length).map(file => file.assetId).join()) return null
  return record.images.filter(image => image.url)
    .map(image => ({ file: null, name: image.name, role: 'SUBJECT', preview: image.url, assetId: image.assetId }))
}
