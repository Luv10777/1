import test from 'node:test'
import assert from 'node:assert/strict'
import { AUDIO_DEDUP_HISTORY_LIMIT, AudioPlaybackQueue, normalizeAudioCommand, transitionGapMillis } from './audioPlaybackQueue.js'

const clip = (id, mode = 'APPEND', extra = {}) => ({ id, mode, text: id, audioUrl: '/audio.wav', durationMillis: 16000, pauseOffsetsMillis: [3000, 7000, 12000], ...extra })

test('reconnect snapshots do not replay current, queued or completed IDs', () => {
  const queue = new AudioPlaybackQueue()
  queue.enqueue(clip('a')); queue.enqueue(clip('b')); queue.next()
  assert.equal(queue.enqueue(clip('a')).accepted, false)
  assert.equal(queue.enqueue(clip('b')).accepted, false)
  queue.finish()
  assert.equal(queue.enqueue(clip('a')).accepted, false)
  assert.equal(queue.next().id, 'b')
})

test('reply interrupts at a supplied quiet point, then resumes the original offset', () => {
  const queue = new AudioPlaybackQueue()
  queue.enqueue(clip('a')); queue.enqueue(clip('b')); queue.next(); queue.enqueue(clip('reply', 'INTERRUPT'))
  assert.equal(queue.interruptionPoint(4200), 7000)
  assert.equal(queue.interruptAt(4200), false)
  assert.equal(queue.interruptAt(7000), true)
  assert.equal(queue.next().id, 'reply')
  queue.finish()
  assert.equal(queue.next().id, 'a')
  assert.equal(queue.current.offsetMillis, 7000)
  queue.finish()
  assert.equal(queue.next().id, 'b')
})

test('last five seconds and a missing quiet point defer reply until segment completion', () => {
  for (const command of [clip('a'), clip('a', 'APPEND', { pauseOffsetsMillis: [] })]) {
    const queue = new AudioPlaybackQueue()
    queue.enqueue(command); queue.next(); queue.enqueue(clip('reply', 'INTERRUPT'))
    assert.equal(queue.interruptionPoint(11500), null)
    assert.equal(queue.interruptAt(12000), false)
    queue.finish()
    assert.equal(queue.next().id, 'reply')
  }
})

test('a segment that is lined up but has not sounded yet gives way to a reply and keeps its place', () => {
  const queue = new AudioPlaybackQueue()
  queue.enqueue(clip('a')); queue.enqueue(clip('b')); queue.next()
  // 没有回复在等时不让位。
  assert.equal(queue.defer(), false)
  queue.enqueue(clip('reply', 'INTERRUPT'))
  assert.equal(queue.defer(), true)
  assert.equal(queue.next().id, 'reply')
  // 回复自己不给后来的回复让位。
  queue.enqueue(clip('reply-2', 'INTERRUPT'))
  assert.equal(queue.defer(), false)
  // 两条回复都播完，才轮到让了位的那一段，后面的顺序不变。
  const order = []
  for (let step = 0; step < 3; step++) { queue.finish(); order.push(queue.next().id) }
  assert.deepEqual(order, ['reply-2', 'a', 'b'])

  // 被打断过、正等着续讲的片段让位后，仍从原来的位置续讲。
  const resumed = new AudioPlaybackQueue()
  resumed.enqueue(clip('a')); resumed.enqueue(clip('b')); resumed.next(); resumed.enqueue(clip('r1', 'INTERRUPT'))
  resumed.interruptAt(7000); resumed.next(); resumed.finish()
  assert.equal(resumed.next().offsetMillis, 7000)
  resumed.enqueue(clip('r2', 'INTERRUPT'))
  assert.equal(resumed.defer(), true)
  assert.equal(resumed.next().id, 'r2'); resumed.finish()
  assert.deepEqual([resumed.next().id, resumed.current.offsetMillis], ['a', 7000])
})

test('pending replies run in order before resuming and do not interrupt each other', () => {
  const queue = new AudioPlaybackQueue()
  queue.enqueue(clip('a')); queue.next()
  queue.enqueue(clip('r1', 'INTERRUPT')); queue.enqueue(clip('r2', 'INTERRUPT'))
  queue.interruptAt(3000)
  assert.equal(queue.next().id, 'r1')
  assert.equal(queue.interruptionPoint(1), null)
  queue.finish(); assert.equal(queue.next().id, 'r2')
  queue.finish(); assert.equal(queue.next().offsetMillis, 3000)
})

test('clear/replay cancels active, waiting and suspended items, retaining ID dedup', () => {
  const queue = new AudioPlaybackQueue()
  queue.enqueue(clip('a')); queue.enqueue(clip('b')); queue.next(); queue.enqueue(clip('r', 'INTERRUPT'))
  queue.interruptAt(3000); queue.next()
  const result = queue.enqueue(clip('restart', 'CLEAR_REPLAY'))
  assert.deepEqual(result.discarded.sort(), ['a', 'b', 'r'])
  assert.equal(queue.next().id, 'restart')
  assert.equal(queue.current.offsetMillis, 0)
  assert.equal(queue.enqueue(clip('b')).accepted, false)
})

test('normalization rejects missing media and filters invalid pause offsets', () => {
  assert.throws(() => normalizeAudioCommand(clip('a', 'OTHER')))
  assert.throws(() => normalizeAudioCommand(clip('a', 'APPEND', { audioUrl: '' })))
  assert.deepEqual(normalizeAudioCommand(clip('a', 'APPEND', { pauseOffsetsMillis: [-1, 0, 7000, 3000, 3000, 16000, 'x'] })).pauseOffsetsMillis, [3000, 7000])
})

test('long sessions bound completed history while retaining outstanding IDs', () => {
  const queue = new AudioPlaybackQueue()
  queue.enqueue(clip('still-waiting'))
  for (let index = 0; index < AUDIO_DEDUP_HISTORY_LIMIT + 80; index++) {
    queue.enqueue(clip(`reply-${index}`, 'INTERRUPT')); queue.next(); queue.finish()
  }
  assert.equal(queue.completed.size, AUDIO_DEDUP_HISTORY_LIMIT)
  assert.equal(queue.seen.size, AUDIO_DEDUP_HISTORY_LIMIT + 1)
  assert.equal(queue.enqueue(clip('still-waiting')).accepted, false)
  assert.equal(queue.next().id, 'still-waiting')
})

test('pauses between clips depend on what is ending and what comes next', () => {
  assert.equal(transitionGapMillis(null, 'APPEND', () => 0.5), 0)
  assert.equal(transitionGapMillis('APPEND', 'APPEND', () => 0), 600)
  assert.equal(transitionGapMillis('APPEND', 'APPEND', () => 1), 1200)
  assert.equal(transitionGapMillis('CLEAR_REPLAY', 'APPEND', () => 0.5), 900)
  assert.equal(transitionGapMillis('APPEND', 'INTERRUPT', () => 0.5), 400)
  assert.equal(transitionGapMillis('INTERRUPT', 'INTERRUPT', () => 0.5), 400)
  assert.equal(transitionGapMillis('INTERRUPT', 'APPEND', () => 0.5), 475)
})

test('a reply closing bridge is kept only as an offset inside the clip, and wanted only before narration', () => {
  assert.equal(normalizeAudioCommand(clip('r', 'INTERRUPT', { outroOffsetMillis: 9000 })).outroOffsetMillis, 9000)
  for (const bad of [undefined, null, 0, -5, 16000, 'x']) {
    assert.equal(normalizeAudioCommand(clip('r', 'INTERRUPT', { outroOffsetMillis: bad })).outroOffsetMillis, null)
  }
  const queue = new AudioPlaybackQueue()
  queue.enqueue(clip('r1', 'INTERRUPT')); queue.next()
  assert.equal(queue.outroWanted(), false)
  queue.enqueue(clip('next'))
  assert.equal(queue.outroWanted(), true)
  queue.enqueue(clip('r2', 'INTERRUPT'))
  assert.equal(queue.outroWanted(), false)
})
