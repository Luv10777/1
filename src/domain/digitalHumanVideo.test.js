import test from 'node:test'
import assert from 'node:assert/strict'
import { digitalHumanVideoInput } from './digitalHumanVideo.js'

const form = overrides => ({ script: '介绍这款茶叶', avatar: { assetId: 1 }, images: [{ assetId: 2 }],
  videos: [], voices: [], model: 'SEEDANCE_2_5', ratio: '9:16', durationSeconds: 10, resolution: '720p', ...overrides })

test('digital human requests preserve avatar order, copy and output settings', () => {
  const input = digitalHumanVideoInput(form())
  assert.deepEqual(input.images.map(entry => entry.assetId), [1, 2])
  assert.match(input.prompt, /介绍这款茶叶/)
  assert.match(input.prompt, /第 1 张参考图是数字人形象/)
  assert.equal(input.ratio, '9:16')
  assert.equal(input.durationSeconds, 10)
  assert.equal(input.resolution, '720p')
  assert.match(digitalHumanVideoInput(form({ script: '' })).prompt, /数字人口播/)
})

test('digital human inputs use the selected model limits and preserve all media', () => {
  assert.throws(() => digitalHumanVideoInput(form({ script: '', avatar: null })), /照片或输入播报文案/)
  const images = Array.from({ length: 29 }, (_, id) => ({ assetId: id + 2 }))
  const videos = Array.from({ length: 10 }, (_, id) => ({ assetId: id + 31 }))
  const voices = Array.from({ length: 10 }, (_, id) => ({ assetId: id + 41 }))
  const input = digitalHumanVideoInput(form({ images, videos, voices }))
  assert.equal(input.images.length, 30)
  assert.deepEqual(input.videos, videos)
  assert.deepEqual(input.audios, voices)
  assert.throws(() => digitalHumanVideoInput(form({ images: [...images, { assetId: 51 }] })), /30 张图片/)
  for (const model of ['SEEDANCE_2_0', 'SEEDANCE_2_0_MINI', 'SEEDANCE_2_0_FAST']) {
    const smaller = { model, images: images.slice(0, 8), videos: videos.slice(0, 3), voices: voices.slice(0, 3) }
    assert.equal(digitalHumanVideoInput(form(smaller)).images.length, 9)
    assert.throws(() => digitalHumanVideoInput(form({ ...smaller, images: images.slice(0, 9) })), /9 张图片/)
    assert.throws(() => digitalHumanVideoInput(form({ ...smaller, videos: videos.slice(0, 4) })), /3 个参考视频/)
    assert.throws(() => digitalHumanVideoInput(form({ ...smaller, voices: voices.slice(0, 4) })), /3 个音频/)
  }
})
