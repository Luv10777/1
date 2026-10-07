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

test('unsupported digital human inputs are rejected without dropping selected materials', () => {
  assert.throws(() => digitalHumanVideoInput(form({ script: '', avatar: null })), /照片或输入播报文案/)
  assert.throws(() => digitalHumanVideoInput(form({ images: Array.from({ length: 6 }, (_, id) => ({ assetId: id + 2 })) })), /6 张图片/)
  assert.throws(() => digitalHumanVideoInput(form({ videos: [{ assetId: 3 }, { assetId: 4 }] })), /1 个参考视频/)
  assert.throws(() => digitalHumanVideoInput(form({ voices: [{ assetId: 5 }] })), /不支持音色/)
})
