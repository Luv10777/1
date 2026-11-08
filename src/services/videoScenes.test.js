import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync } from 'node:fs'
import { findVideoScene, videoSceneCover, videoSceneFields, videoSceneName } from './videoScenes.js'

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

test('every built-in scene has a cover image and a scene added later falls back to its icon', () => {
  for (const key of ['free', 'ecommerce', 'local_life', 'event', 'brand', 'drama']) {
    assert.equal(videoSceneCover(key), `/images/video-scenes/${key}.webp`)
    assert.ok(existsSync(new URL(`../../public/images/video-scenes/${key}.webp`, import.meta.url)), key)
  }
  assert.equal(videoSceneCover('retired'), '')
})
