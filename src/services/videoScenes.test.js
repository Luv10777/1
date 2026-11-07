import test from 'node:test'
import assert from 'node:assert/strict'
import { findVideoScene, videoSceneFields, videoSceneName } from './videoScenes.js'

const scenes = [{ key: 'free', name: '自由创作' }, { key: 'ecommerce', name: '电商带货' }]

test('free creation sends the same request fields as before scenes existed', () => {
  assert.deepEqual(videoSceneFields({ sceneKey: 'free', planId: null }), {})
  assert.deepEqual(videoSceneFields({ sceneKey: null, planId: undefined }), {})
  assert.deepEqual(videoSceneFields({ sceneKey: 'ecommerce', planId: 7 }), { sceneKey: 'ecommerce', planId: 7 })
  assert.deepEqual(videoSceneFields({ sceneKey: 'free', planId: 7 }), { planId: 7 })
})

test('records name only guided scenes that are still listed', () => {
  assert.equal(videoSceneName(scenes, 'ecommerce'), '电商带货')
  assert.equal(videoSceneName(scenes, null), '')
  assert.equal(videoSceneName(scenes, 'free'), '')
  assert.equal(videoSceneName(scenes, 'retired'), '')
  assert.equal(findVideoScene(scenes, null).key, 'free')
})
