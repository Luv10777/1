import test from 'node:test'
import assert from 'node:assert/strict'
import { gzipSync } from 'node:zlib'
import { readFrame } from './douyinFrames.js'

// A small protobuf writer, enough to build frames shaped like the ones the live page receives.
const varint = (value) => {
  let rest = BigInt(value)
  const bytes = []
  do { const byte = Number(rest & 0x7fn); rest >>= 7n; bytes.push(rest ? byte | 0x80 : byte) } while (rest)
  return Buffer.from(bytes)
}
const key = (number, wire) => varint((number << 3) | wire)
const num = (number, value) => Buffer.concat([key(number, 0), varint(value)])
const bytes = (number, value) => { const body = Buffer.isBuffer(value) ? value : Buffer.from(value, 'utf8'); return Buffer.concat([key(number, 2), varint(body.length), body]) }
const fixed64 = number => Buffer.concat([key(number, 1), Buffer.alloc(8, 7)])
const fixed32 = number => Buffer.concat([key(number, 5), Buffer.alloc(4, 7)])

const common = msgId => Buffer.concat([bytes(1, 'WebcastChatMessage'), num(2, msgId), num(3, 7694155249048734474n)])
const viewer = Buffer.concat([num(1, 99887766n), bytes(3, '观众昵称不该被读到')])
const chatPayload = (msgId, content) => Buffer.concat([bytes(1, common(msgId)), bytes(2, viewer), bytes(3, content), num(4, 0)])
const message = (method, payload, msgId = 0n) => bytes(1, Buffer.concat([bytes(1, method), bytes(2, payload), num(3, msgId), num(4, 1)]))
const response = (...messages) => Buffer.concat([...messages, bytes(2, 'cursor-1'), num(8, 10000), num(9, 1)])
const frame = (payload, { type = 'msg', gzip = true } = {}) => Buffer.concat([
  num(1, 12), num(2, 7301234567890123456n),
  bytes(5, Buffer.concat([bytes(1, 'compress_type'), bytes(2, gzip ? 'gzip' : 'none')])),
  bytes(6, 'pb'), bytes(7, type), bytes(8, gzip ? gzipSync(payload) : payload),
])

test('a viewer comment comes out as its id and its words, and nothing about the viewer', () => {
  const result = readFrame(frame(response(message('WebcastChatMessage', chatPayload(7694155249048799999n, '  多少钱一杯？ ')))))
  assert.deepEqual(result.events, [{ kind: 'chat', id: '7694155249048799999', text: '多少钱一杯？' }])
  assert.equal(JSON.stringify(result).includes('观众昵称'), false)
})

test('an id larger than a JavaScript number survives exactly', () => {
  const id = 18446744073709551615n
  assert.equal(readFrame(frame(response(message('WebcastChatMessage', chatPayload(id, '在吗'))))).events[0].id, id.toString())
})

test('several messages in one frame are read in order, and only comments and the end of the stream are kept', () => {
  const result = readFrame(frame(response(
    message('WebcastLikeMessage', Buffer.concat([bytes(1, common(1n)), num(2, 3)])),
    message('WebcastChatMessage', chatPayload(11n, '第一条')),
    message('WebcastGiftMessage', bytes(1, common(2n))),
    message('WebcastChatMessage', chatPayload(12n, '第二条')),
    message('WebcastControlMessage', Buffer.concat([bytes(1, common(3n)), num(2, 3)])),
  )))
  assert.deepEqual(result.events, [
    { kind: 'chat', id: '11', text: '第一条' },
    { kind: 'chat', id: '12', text: '第二条' },
    { kind: 'ended' },
  ])
  assert.deepEqual(result.methods, { WebcastLikeMessage: 1, WebcastChatMessage: 2, WebcastGiftMessage: 1, WebcastControlMessage: 1 })
})

test('a control message that is not the end of the stream is not taken for one', () => {
  const result = readFrame(frame(response(message('WebcastControlMessage', Buffer.concat([bytes(1, common(3n)), num(2, 1)])))))
  assert.deepEqual(result.events, [])
})

test('heartbeats and acknowledgements carry nothing and are passed over', () => {
  assert.deepEqual(readFrame(frame(Buffer.from('ignored'), { type: 'hb', gzip: false })), { type: 'hb', events: [], methods: {} })
  assert.deepEqual(readFrame(frame(Buffer.from('ignored'), { type: 'ack', gzip: false })).events, [])
})

test('an uncompressed payload is read just the same', () => {
  const result = readFrame(frame(response(message('WebcastChatMessage', chatPayload(5n, '没压缩'))), { gzip: false }))
  assert.deepEqual(result.events, [{ kind: 'chat', id: '5', text: '没压缩' }])
})

test('the outer message id is used when the comment itself carries none, and an empty comment is dropped', () => {
  const noCommonId = Buffer.concat([bytes(1, bytes(1, 'WebcastChatMessage')), bytes(3, '有货吗')])
  assert.deepEqual(readFrame(frame(response(message('WebcastChatMessage', noCommonId, 77n)))).events, [{ kind: 'chat', id: '77', text: '有货吗' }])
  assert.deepEqual(readFrame(frame(response(message('WebcastChatMessage', chatPayload(8n, '   '))))).events, [])
})

test('fields of kinds this reader does not expand are skipped without losing its place', () => {
  const payload = Buffer.concat([fixed64(20), bytes(1, common(9n)), fixed32(21), bytes(3, '还在'), fixed64(22)])
  assert.deepEqual(readFrame(frame(response(message('WebcastChatMessage', payload)))).events, [{ kind: 'chat', id: '9', text: '还在' }])
})

test('a frame cut short or of an unknown shape is refused rather than half read', () => {
  const whole = frame(response(message('WebcastChatMessage', chatPayload(1n, '完整的一条'))), { gzip: false })
  assert.throws(() => readFrame(whole.subarray(0, whole.length - 5)), /数据不完整/)
  assert.throws(() => readFrame(Buffer.from([0x0b, 0x01])), /无法识别的数据格式/)
  assert.throws(() => readFrame(Buffer.from([0x80])), /数据不完整/)
})
