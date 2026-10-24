import test from 'node:test'
import assert from 'node:assert/strict'
import { isVideoInProgress, snapshotVideoForm, videoFormFromWorkflow } from './videoConversations.js'

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
  const legacy = videoFormFromWorkflow({ referenceVideo: media(6) })
  assert.deepEqual(legacy.referenceVideos.map(entry => entry.assetId), [6])
  assert.deepEqual(legacy.referenceAudios, [])
  assert.deepEqual(snapshotVideoForm({ referenceImages: [], referenceVideo: media(6) }).referenceVideos.map(entry => entry.assetId), [6])
})
