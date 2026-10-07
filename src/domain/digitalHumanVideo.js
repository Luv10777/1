export function digitalHumanVideoInput({ script, avatar, images, videos, voices, model, ratio, durationSeconds, resolution }) {
  if (!avatar && !script.trim()) throw new Error('上传一张数字人照片或输入播报文案后再开始。')
  const references = [avatar, ...images].filter(Boolean)
  if (references.length > 6) throw new Error('当前视频任务最多支持 6 张图片（含数字人形象），请减少素材后生成。')
  if (videos.length > 1) throw new Error('当前视频任务最多支持 1 个参考视频，请减少素材后生成。')
  if (voices.length) throw new Error('当前视频任务暂不支持音色素材，请移除音色后生成。')
  const instructions = ['生成数字人口播带货视频。']
  if (script.trim()) instructions.push(`口播文案与画面要求：${script.trim()}`)
  if (avatar) instructions.push('第 1 张参考图是数字人形象，请保持人物身份一致。')
  if (images.length) instructions.push(`${avatar ? '其余' : '所有'}参考图片是商品及包装素材，请保持商品外观一致。`)
  return { prompt: instructions.join('\n'), images: references, video: videos[0] || null, model, ratio, durationSeconds, resolution }
}
