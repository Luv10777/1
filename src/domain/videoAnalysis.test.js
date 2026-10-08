import test from 'node:test'
import assert from 'node:assert/strict'
import { VIDEO_ANALYSIS_LIMITS, validateAnalysisFile, analysisMimeType, analysisTime, nearestAnalysisFrame } from './videoAnalysis.js'

test('video upload accepts the exact size limit and rejects oversized or disguised inputs', () => {
  const file = { name: '素材.MP4', size: VIDEO_ANALYSIS_LIMITS.maxBytes, type: 'video/mp4' }
  assert.equal(validateAnalysisFile(file), '')
  assert.match(validateAnalysisFile({ ...file, size: file.size + 1 }), /100 MB/)
  assert.match(validateAnalysisFile({ ...file, name: '素材.avi' }), /MP4/)
  assert.match(validateAnalysisFile({ ...file, size: 0 }), /为空/)
  assert.match(validateAnalysisFile({ ...file, type: 'text/plain' }), /有效/)
  assert.equal(validateAnalysisFile({ ...file, type: '' }), '')
  assert.equal(analysisMimeType({ name: '手机.MOV' }), 'video/quicktime')
})

test('history keyframes resolve to available samples and timestamps carry correctly', () => {
  const frames = [{ seconds: 0, imageUrl: 'a' }, { seconds: 1.25, imageUrl: 'b' }, { seconds: 2.5, imageUrl: 'c' }]
  assert.equal(nearestAnalysisFrame(frames, 1.4).imageUrl, 'b')
  assert.equal(nearestAnalysisFrame([], 0), null)
  assert.equal(analysisTime(59.99), '01:00')
  assert.equal(analysisTime(1.25), '00:01.3')
})
