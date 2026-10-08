import { gunzipSync } from 'node:zlib'

// 抖音直播间网页收到的弹幕是一帧帧二进制数据（protobuf，外面再包一层 gzip）。
// 这里只读用得着的几个字段：消息类型、弹幕 ID、弹幕文字、下播通知。
// 观众的昵称、头像、账号都在别的字段里，这里从不读取。

const CHAT = 'WebcastChatMessage'
const CONTROL = 'WebcastControlMessage'
// ControlMessage.action 的取值里，3 表示本场直播结束。
const ACTION_ENDED = 3n

function varint(buffer, offset) {
  let value = 0n
  let shift = 0n
  for (;;) {
    if (offset >= buffer.length || shift > 63n) throw new Error('数据不完整')
    const byte = buffer[offset++]
    value |= BigInt(byte & 0x7f) << shift
    if (!(byte & 0x80)) return [value, offset]
    shift += 7n
  }
}

/** 依次给出一条 protobuf 消息里的字段。只展开用得着的两种编码，其余跳过。 */
function* fields(buffer) {
  let offset = 0
  while (offset < buffer.length) {
    const [key, afterKey] = varint(buffer, offset)
    const number = Number(key >> 3n)
    const wire = Number(key & 7n)
    offset = afterKey
    if (wire === 0) {
      const [value, next] = varint(buffer, offset)
      offset = next
      yield { number, value }
    } else if (wire === 2) {
      const [length, start] = varint(buffer, offset)
      const end = start + Number(length)
      if (end > buffer.length) throw new Error('数据不完整')
      offset = end
      yield { number, bytes: buffer.subarray(start, end) }
    } else if (wire === 1) {
      offset += 8
    } else if (wire === 5) {
      offset += 4
    } else {
      throw new Error('无法识别的数据格式')
    }
  }
}

const text = bytes => bytes.toString('utf8')
const isGzip = bytes => bytes.length > 2 && bytes[0] === 0x1f && bytes[1] === 0x8b

/** 最外层的帧：payload_type 为 msg 的才带直播消息，另外还有心跳和确认。 */
function pushFrame(buffer) {
  let type = ''
  let payload = null
  for (const field of fields(buffer)) {
    if (field.number === 7 && field.bytes) type = text(field.bytes)
    else if (field.number === 8 && field.bytes) payload = field.bytes
  }
  return { type, payload }
}

function chat(payload, fallbackId) {
  let id = fallbackId
  let content = ''
  for (const field of fields(payload)) {
    if (field.number === 1 && field.bytes) {
      for (const common of fields(field.bytes)) if (common.number === 2 && common.value !== undefined) id = common.value
    } else if (field.number === 3 && field.bytes) {
      content = text(field.bytes)
    }
  }
  return { kind: 'chat', id: id ? id.toString() : '', text: content.trim() }
}

function ended(payload) {
  for (const field of fields(payload)) if (field.number === 2 && field.value === ACTION_ENDED) return true
  return false
}

/**
 * 读一帧，返回里面和我们有关的事件，以及各类消息各有几条（只用于排查，不含内容）。
 * 读不懂的帧抛出错误，由调用方决定忽略。
 */
export function readFrame(buffer) {
  const frame = pushFrame(buffer)
  const events = []
  const methods = {}
  if (frame.type !== 'msg' || !frame.payload) return { type: frame.type, events, methods }
  const response = isGzip(frame.payload) ? gunzipSync(frame.payload) : frame.payload
  for (const field of fields(response)) {
    if (field.number !== 1 || !field.bytes) continue
    let method = ''
    let payload = null
    let id = 0n
    for (const part of fields(field.bytes)) {
      if (part.number === 1 && part.bytes) method = text(part.bytes)
      else if (part.number === 2 && part.bytes) payload = part.bytes
      else if (part.number === 3 && part.value !== undefined) id = part.value
    }
    methods[method] = (methods[method] || 0) + 1
    if (!payload) continue
    if (method === CHAT) {
      const event = chat(payload, id)
      if (event.text) events.push(event)
    } else if (method === CONTROL && ended(payload)) {
      events.push({ kind: 'ended' })
    }
  }
  return { type: frame.type, events, methods }
}
