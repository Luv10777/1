import test from 'node:test'
import assert from 'node:assert/strict'
import { isVideoInProgress, snapshotVideoForm, videoFormFromWorkflow, videoModelLabel, videoModels } from './videoConversations.js'
import { videoMaterialLimits } from '../domain/videoMaterials.js'

test('special models offer 720p and 1080p with their base model limits', () => {
  for (const [id, label, seconds, images] of [['SEEDANCE_2_5_SPECIAL', 'Seedance 2.5-特价', 30, 30], ['SEEDANCE_2_0_SPECIAL', 'Seedance 2.0-特价', 15, 9]]) {
    const model = videoModels.find(item => item.id === id)
    assert.deepEqual(model.resolutions, ['720p', '1080p'])
    assert.equal(model.maxDurationSeconds, seconds)
    assert.equal(videoModelLabel(id), label)
    assert.equal(videoMaterialLimits(id).image, images)
  }
})

test('shared video state checks treat CANCELLED as terminal in both video screens', () => {
  for (const status of ['SUCCEEDED', 'FAILED', 'CANCELLED']) {
    assert.equal(isVideoInProgress({ status }), false, status)
  }
  for (const status of ['QUEUED', 'SUBMITTING', 'GENERATING', 'IMPORTING', 'QA']) {
    assert.equal(isVideoInProgress({ status }), true, status)
  }
  assert.equal(isVideoInProgress(null), false)
})

test('conversation snapshots preserve every reference without changing earlier generations', () => {
  const form = { referenceImages: [{ assetId: 1 }], referenceVideos: [{ assetId: 2 }, { assetId: 3 }], referenceAudios: [{ assetId: 4 }, { assetId: 5 }] }
  const snapshot = snapshotVideoForm(form)
  for (const key of ['referenceImages', 'referenceVideos', 'referenceAudios']) {
    assert.deepEqual(snapshot[key], form[key])
    snapshot[key][0].assetId = 99
    snapshot[key].pop()
    assert.notEqual(snapshot[key], form[key])
    assert.notEqual(form[key][0].assetId, 99)
  }
})

test('history restores multi media workflows and existing single video records', () => {
  const media = id => ({ assetId: id, name: `素材 ${id}`, url: `/assets/${id}` })
  const restored = videoFormFromWorkflow({ referenceImages: [media(1)], referenceVideos: [media(2), media(3)], referenceAudios: [media(4), media(5)] })
  assert.deepEqual(restored.referenceVideos.map(entry => entry.assetId), [2, 3])
  assert.deepEqual(restored.referenceAudios.map(entry => entry.assetId), [4, 5])
  assert.equal(restored.referenceVideos[0].file, null)
  const fromPlan = videoFormFromWorkflow({ sceneKey: 'local_life', planId: 9 })
  assert.equal(fromPlan.sceneKey, 'local_life')
  assert.equal(fromPlan.planId, 9)
  const legacy = videoFormFromWorkflow({ referenceVideo: media(6) })
  assert.deepEqual(legacy.referenceVideos.map(entry => entry.assetId), [6])
  assert.deepEqual(legacy.referenceAudios, [])
  assert.equal(legacy.sceneKey, 'free')
  assert.equal(legacy.planId, null)
  assert.deepEqual(snapshotVideoForm({ referenceImages: [], referenceVideo: media(6) }).referenceVideos.map(entry => entry.assetId), [6])
})
