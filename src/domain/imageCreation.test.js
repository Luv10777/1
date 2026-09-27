import test from 'node:test'
import assert from 'node:assert/strict'
import { imageDimensions, validateImageFile, isImageActive, supportsImageSize } from './imageCreation.js'

test('quality resolves landscape, portrait and square without labelling 1080P as 4K', () => {
  assert.deepEqual(imageDimensions('480P', '16:9'), { width: 853, height: 480 })
  assert.deepEqual(imageDimensions('720P', '3:4'), { width: 720, height: 960 })
  assert.deepEqual(imageDimensions('1080P', '9:16'), { width: 1080, height: 1920 })
  assert.deepEqual(imageDimensions('4K', '16:9'), { width: 3840, height: 2160 })
  assert.deepEqual(imageDimensions('4K', '3:4'), { width: 2880, height: 3840 })
  assert.deepEqual(imageDimensions('4K', '1:1'), { width: 3840, height: 3840 })
  assert.throws(() => imageDimensions('8K', '3:4'))
  assert.throws(() => imageDimensions('4K', '0:1'))
})
test('upload rejects unrenderable or oversized reference files', () => {
  assert.throws(() => validateImageFile({ type: 'image/svg+xml', size: 10 }))
  assert.throws(() => validateImageFile({ type: 'image/png', size: 21 * 1024 * 1024 }))
  assert.throws(() => validateImageFile({ type: 'image/jpeg', size: 0 }))
  assert.doesNotThrow(() => validateImageFile({ type: 'image/jpeg', size: 500 }))
})
test('partial failures and clarification do not trigger endless frontend polling', () => {
  for (const state of ['PARTIAL', 'FAILED', 'INTERRUPTED', 'NEEDS_INPUT', 'SUCCEEDED']) assert.equal(isImageActive(state), false)
  for (const state of ['QUEUED', 'PLANNING', 'GENERATING', 'SAVING']) assert.equal(isImageActive(state), true)
})
test('model capabilities restrict quality and ratio combinations while older APIs remain compatible', () => {
  const caps = { qualities: ['1080P', '4K'], qualityRatios: { '1080P': ['1:1', '3:4'], '4K': ['16:9', '9:16'] } }
  assert.equal(supportsImageSize(caps, '4K', '1:1'), false)
  assert.equal(supportsImageSize(caps, '4K', '16:9'), true)
  assert.equal(supportsImageSize(caps, '720P', '16:9'), false)
  assert.equal(supportsImageSize({ qualities: ['4K'] }, '4K', '3:4'), true)
  assert.equal(supportsImageSize(null, '4K', '3:4'), true)
})
