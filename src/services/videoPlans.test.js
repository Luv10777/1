import test from 'node:test'
import assert from 'node:assert/strict'
import { isVideoPlanDone, videoPlanForm, videoPlanRecordImages, videoPlanRequestedSeconds, videoPlanSeconds } from './videoPlans.js'

const plan = { prompt: ' 如图1中的香水瓶，9:16 竖屏 12 秒 ', negativePrompt: '画面文字', ratio: '9:16', durationSeconds: 12 }

test('using a historical plan fills its script without appending generic negative instructions', () => {
  assert.deepEqual(videoPlanForm(plan, { maxDuration: 15, ratios: ['9:16'] }),
    { prompt: '如图1中的香水瓶，9:16 竖屏 12 秒', format: '9:16', duration: 12, shortened: false })
})

test('a reviewed script uses its compiled instructions without duplicating the original brief', () => {
  const reviewed = { ...plan, promptVersion: 2, prompt: '总时长20秒。0–3秒：顾客停下付款动作。3–20秒：活动权益与参与引导。', durationSeconds: 20 }
  const form = videoPlanForm(reviewed, { idea: '双十一充值活动持续10天，生成20s视频', sourceBrief: '旧稿堆叠的运镜说明' })
  assert.equal(form.prompt, reviewed.prompt)
  assert.equal(form.duration, 20)
})

test('a plan longer than the model allows is blocked rather than keeping a contradictory timeline', () => {
  assert.throws(() => videoPlanForm({ ...plan, durationSeconds: 15 }, { maxDuration: 10 }), /不能直接截短分镜/)
  const form = videoPlanForm({ ...plan, negativePrompt: '', ratio: '2:1' }, { maxDuration: 15, ratios: ['9:16'] })
  assert.equal(form.prompt, '如图1中的香水瓶，9:16 竖屏 12 秒')
  assert.equal(form.format, '', 'a plan without a usable ratio leaves the choice to the merchant')
  assert.equal(form.duration, 12)
  assert.equal(form.shortened, false)
})

test('a ten day campaign keeps the twenty second requirement and rejects an old fifteen second plan', () => {
  const idea = '这个是双十一充值活动，开展10天的充值活动，为我这个充值活动生成20s的宣传推广视频'
  assert.equal(videoPlanRequestedSeconds(idea), 20)
  assert.equal(videoPlanRequestedSeconds('活动开展10天'), null)
  assert.throws(() => videoPlanForm({ ...plan, durationSeconds: 15 }, { idea }), /原需求是 20 秒/)
  const form = videoPlanForm({ ...plan, prompt: '20秒充值活动推广', durationSeconds: 20 }, { idea })
  assert.equal(form.duration, 20)
  assert.ok(form.prompt.includes(idea), 'original campaign goal reaches the generation model')
})

test('only succeeded or failed plans stop polling', () => {
  assert.equal(isVideoPlanDone({ status: 'GENERATING' }), false)
  assert.equal(isVideoPlanDone({ status: 'FAILED' }), true)
  assert.equal(videoPlanSeconds(2.5), '2.5')
  assert.equal(videoPlanSeconds(4), '4')
})

test('a missing reference cannot shift later image indexes when reusing a plan', () => {
  const record = { images: [{ assetId: 1, name: '图1', url: '/1.png' }, { assetId: 2, name: '图2', url: null }] }
  assert.throws(() => videoPlanRecordImages(record, [{ assetId: 9 }]), /图序号错位/)
  const complete = { images: [{ assetId: 1, name: '图1', url: '/1.png' }] }
  assert.equal(videoPlanRecordImages(complete, [{ assetId: 1 }]), null)
  assert.equal(videoPlanRecordImages(complete, [{ assetId: 1 }, { assetId: 3 }]).length, 1)
  assert.equal(videoPlanRecordImages({ images: [] }, [{ assetId: 9 }]), null)
})
