// 桌面端从直播间读到的弹幕 → 交给页面去提交。
// 弹幕由桌面软件读取（desktop/），经 window.yifangzhiDesktop.danmaku 送进来；网页版浏览器里没有这座桥。

const SEEN_LIMIT = 2000
// 哪些弹幕值得回由后端判断（打招呼、刷屏的不问模型，弹幕多时先处理提问），所以这里尽量都送过去，
// 只防刷屏：间隔短于这个数的直接丢掉。
const MIN_INTERVAL = 300
const TEXT_LIMIT = 500

/**
 * 桌面端送来的一条文本（格式见 desktop/src/wire.js）。只认两种：直播间状态，和观众发的文字弹幕。
 * 里面不带观众的昵称和账号。
 */
export function parseDanmakuMessage(data) {
  let message
  try { message = JSON.parse(data) } catch { return null }
  if (!message || typeof message !== 'object') return null
  if (message.type === 'system') {
    if (message.event !== 'live_status') return null
    return { kind: 'status', code: String(message.code || ''), live: message.live === true, text: String(message.status_text || '') }
  }
  if (message.method !== 'WebcastChatMessage') return null
  const text = typeof message.content === 'string' ? message.content.trim().slice(0, TEXT_LIMIT) : ''
  if (!text) return null
  const id = message.common?.msgId
  return { kind: 'chat', id: id ? String(id) : '', text }
}

/**
 * 让桌面端开始读一个直播间，把读到的文字弹幕交给 onChat：同一条只交一次，刷屏时丢掉过密的。
 *
 * status: idle 未连接 | connecting 连接中 | waiting 已连接，直播间未开播或状态未知
 *         | live 正在接收弹幕 | failed 没能开始
 */
export class LiveDanmakuRelay {
  constructor({ bridge, roomId, onChat, onChange = () => {}, minInterval = MIN_INTERVAL } = {}) {
    this.bridge = bridge
    this.roomId = roomId
    this.onChat = onChat
    this.onChange = onChange
    this.minInterval = minInterval
    this.status = 'idle'
    this.message = ''
    this.received = 0
    this.forwarded = 0
    this.skipped = 0
    this.unsubscribe = null
    this.lastForwardAt = -Infinity
    this.seen = new Set()
    this.sequence = 0
  }

  snapshot() {
    return { status: this.status, message: this.message, received: this.received, forwarded: this.forwarded, skipped: this.skipped }
  }

  setStatus(status, message) {
    this.status = status
    this.message = message
    this.onChange(this.snapshot())
  }

  async start() {
    if (this.unsubscribe) return
    this.setStatus('connecting', '正在连接…')
    // 软件一开始读就会报状态，可能比“已开始”的答复先到，所以先订阅。
    const unsubscribe = this.bridge.onMessage(data => this.receive(data))
    this.unsubscribe = unsubscribe
    try {
      await this.bridge.start(this.roomId)
    } catch (error) {
      // 等答复的时候已经被停掉了，就不再改状态。
      if (this.unsubscribe !== unsubscribe) return
      this.release()
      this.setStatus('failed', error?.message || '没能开始读取直播间，请重试')
    }
  }

  stop() {
    const active = Boolean(this.unsubscribe)
    this.release()
    if (active) Promise.resolve(this.bridge.stop()).catch(() => {})
    this.setStatus('idle', '')
  }

  release() {
    this.unsubscribe?.()
    this.unsubscribe = null
  }

  receive(data) {
    const parsed = parseDanmakuMessage(data)
    if (!parsed) return
    if (parsed.kind === 'status') {
      this.setStatus(parsed.live ? 'live' : 'waiting', parsed.text)
      return
    }
    this.received += 1
    const id = parsed.id || `local-${Date.now()}-${++this.sequence}`
    const now = Date.now()
    if (this.seen.has(id) || now - this.lastForwardAt < this.minInterval) {
      this.skipped += 1
    } else {
      this.remember(id)
      this.lastForwardAt = now
      this.forwarded += 1
      this.onChat({ id, text: parsed.text })
    }
    if (this.status === 'live') this.onChange(this.snapshot())
    else this.setStatus('live', '正在接收弹幕')
  }

  remember(id) {
    this.seen.add(id)
    if (this.seen.size > SEEN_LIMIT) this.seen.delete(this.seen.values().next().value)
  }
}
