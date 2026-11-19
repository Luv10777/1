const seedance25Limits = { image: 30, video: 10, audio: 10 }
const seedance20Limits = { image: 9, video: 3, audio: 3 }

export const materialLabels = { image: '图片', video: '视频', audio: '音频' }
const mention = /[@＠](图片|视频|音频)(\d+)/g
const missing = '【素材引用已失效】'

export function remapMentions(text, type, before, after) {
  return text.replace(mention, (token, label, index) => {
    if (label !== materialLabels[type]) return token
    const original = before[Number(index) - 1]
    if (!original) return missing
    const position = after.findIndex(entry => (entry.id && entry.id === original.id)
      || (entry.assetId && entry.assetId === original.assetId))
    return position < 0 ? missing : `@${label}${position + 1}`
  })
}

export function validateMentions(text, counts) {
  if (text.includes(missing)) throw new Error('部分引用的素材已移除，请删除失效标记或重新 @ 素材。')
  for (const [token, label, index] of text.matchAll(mention)) {
    if (label in counts && (Number(index) < 1 || Number(index) > counts[label])) {
      throw new Error(`${token} 没有对应素材，请重新选择。`)
    }
  }
}

export const videoMaterialLimits = model => ['SEEDANCE_2_5', 'SEEDANCE_2_5_SPECIAL'].includes(model) ? seedance25Limits : seedance20Limits

export function validateVideoMaterials({ model, images = [], videos = [], audios = [] }) {
  const limits = videoMaterialLimits(model)
  if (images.length > limits.image) throw new Error(`当前模型最多支持 ${limits.image} 张图片。`)
  if (videos.length > limits.video) throw new Error(`当前模型最多支持 ${limits.video} 个参考视频。`)
  if (audios.length > limits.audio) throw new Error(`当前模型最多支持 ${limits.audio} 个音频。`)
}
