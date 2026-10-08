import test from 'node:test'
import assert from 'node:assert/strict'
import { chatMessage, statusMessage } from './wire.js'
import { parseDanmakuMessage } from '../../src/services/liveDanmakuRelay.js'

test('what the desktop app sends is what the console page reads', () => {
  assert.deepEqual(parseDanmakuMessage(chatMessage({ id: '7694182886523032630', text: '你好' })),
    { kind: 'chat', id: '7694182886523032630', text: '你好' })
  assert.deepEqual(parseDanmakuMessage(statusMessage({ code: 'ROOM_ONLINE', live: true, text: '直播间已开播，正在接收弹幕' })),
    { kind: 'status', code: 'ROOM_ONLINE', live: true, text: '直播间已开播，正在接收弹幕' })
  assert.deepEqual(parseDanmakuMessage(statusMessage({ code: 'ROOM_OFFLINE', live: false, text: '直播间未开播' })),
    { kind: 'status', code: 'ROOM_OFFLINE', live: false, text: '直播间未开播' })
})

test('a comment carries its text and id and nothing else', () => {
  assert.deepEqual(Object.keys(JSON.parse(chatMessage({ id: '1', text: '在吗', nickname: '不该出现' }))), ['method', 'common', 'content'])
})
