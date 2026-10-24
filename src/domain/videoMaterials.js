const seedance25Limits = { image: 30, video: 10, audio: 10 }
const seedance20Limits = { image: 9, video: 3, audio: 3 }

export const videoMaterialLimits = model => model === 'SEEDANCE_2_5' ? seedance25Limits : seedance20Limits

export function validateVideoMaterials({ model, images = [], videos = [], audios = [] }) {
  const limits = videoMaterialLimits(model)
  if (images.length > limits.image) throw new Error(`当前模型最多支持 ${limits.image} 张图片。`)
  if (videos.length > limits.video) throw new Error(`当前模型最多支持 ${limits.video} 个参考视频。`)
  if (audios.length > limits.audio) throw new Error(`当前模型最多支持 ${limits.audio} 个音频。`)
}
