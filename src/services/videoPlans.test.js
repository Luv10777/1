import test from 'node:test'
import assert from 'node:assert/strict'
import { isVideoPlanDone, videoPlanForm, videoPlanSeconds } from './videoPlans.js'

const plan = { prompt: ' 如图1中的香水瓶，9:16 竖屏 12 秒 ', negativePrompt: '画面文字', ratio: '9:16', durationSeconds: 12 }

test('using a plan fills the prompt with what to avoid, the ratio and the duration', () => {
  assert.deepEqual(videoPlanForm(plan, { maxDuration: 15, ratios: ['auto', '9:16'] }),
    { prompt: '如图1中的香水瓶，9:16 竖屏 12 秒\n避免：画面文字', format: '9:16', duration: 12, shortened: false })
})

test('a plan longer than the model allows is shortened and an unknown ratio falls back to auto', () => {
  const form = videoPlanForm({ ...plan, negativePrompt: '', ratio: '2:1', durationSeconds: 15 }, { maxDuration: 10, ratios: ['auto'] })
  assert.equal(form.prompt, '如图1中的香水瓶，9:16 竖屏 12 秒')
  assert.equal(form.format, 'auto')
  assert.equal(form.duration, 10)
  assert.equal(form.shortened, true)
})

test('only succeeded or failed plans stop polling', () => {
  assert.equal(isVideoPlanDone({ status: 'GENERATING' }), false)
  assert.equal(isVideoPlanDone({ status: 'FAILED' }), true)
  assert.equal(videoPlanSeconds(2.5), '2.5')
  assert.equal(videoPlanSeconds(4), '4')
})
