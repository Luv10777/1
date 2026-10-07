import { BrowserAudioPlayer } from './audioPlayer.js'
import { AUDIO_DEDUP_HISTORY_LIMIT } from '../domain/audioPlaybackQueue.js'

const HEARTBEAT_INTERVAL = 10000
const HEARTBEAT_TIMEOUT = 30000
const HANDSHAKE_TIMEOUT = 12000
// 服务端以这个关闭码表示配对已作废：别的页面接管了播报，或本场已结束。
const PAIRING_REVOKED = 1008

/**
 * 在当前页面播放本场播报。向后端要一张播报凭证，连上播报通道，收到的音频交给
 * BrowserAudioPlayer 播放，播完逐条确认。队列保存在服务端：断线或刷新后重新连接，
 * 拿到的是还没播的全部内容。
 *
 * status: idle 未开始 | connecting 连接中 | connected 已连接 | reconnecting 重连中 | revoked 已被接管或场次结束
 */
export class LivePlayerSession {
  constructor({ issueToken, onChange = () => {}, baseUrl = '', createSocket, createEngine } = {}) {
    this.issueToken = issueToken
    this.onChange = onChange
    this.baseUrl = baseUrl
    this.createSocket = createSocket || (url => new WebSocket(url))
    this.status = 'idle'
    this.error = ''
    this.token = ''
    this.socket = null
    this.starting = null
    this.intentional = false
    this.destroyed = false
    this.retryCount = 0
    this.lastHeartbeat = 0
    this.pendingAcks = new Set()
    this.engine = (createEngine || (options => new BrowserAudioPlayer(options)))({
      onChange: () => this.notify(),
      onComplete: id => this.complete(id),
      audioBaseUrl: baseUrl,
      getMediaToken: () => this.token,
    })
  }

  /** error 是播放器自己的音频错误；连接层的问题单独放在 connectionError，两者互不覆盖。 */
  snapshot() {
    return { ...this.engine.snapshot(), status: this.status, connectionError: this.error }
  }

  notify() {
    if (!this.destroyed) this.onChange(this.snapshot())
  }

  setStatus(status, error = '') {
    this.status = status
    this.error = error
    this.notify()
  }

  /** 浏览器只允许在用户操作时开始出声，所以必须在点击事件里直接调用，先于任何网络请求。 */
  unlock() {
    return this.engine.unlock()
  }

  /** 重复调用是安全的：已经在连或已连上时只确保声音处于解锁状态。 */
  start() {
    if (this.destroyed) return Promise.resolve()
    if (!this.starting) this.starting = this.open().finally(() => { this.starting = null })
    return this.starting
  }

  async open() {
    this.intentional = false
    await this.engine.unlock()
    if (this.socket) return
    this.setStatus('connecting')
    try {
      this.token = (await this.issueToken()).token
    } catch (error) {
      this.setStatus('idle', error.message || '无法开始播报，请重试')
      throw error
    }
    if (this.destroyed || this.intentional) return
    this.connect(false)
  }

  connect(reconnecting) {
    this.clearTimers()
    const previous = this.socket
    this.socket = null
    previous?.close(1000, 'reconnecting')
    this.setStatus(reconnecting ? 'reconnecting' : 'connecting')
    const url = new URL('/api/player/ws', this.baseUrl || window.location.origin)
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
    url.searchParams.set('token', this.token)
    const socket = this.createSocket(url.href)
    this.socket = socket
    this.handshakeTimer = setTimeout(() => {
      if (this.socket === socket && this.status !== 'connected') socket.close(4000, 'handshake timeout')
    }, HANDSHAKE_TIMEOUT)
    socket.onopen = () => {
      if (this.socket !== socket) return
      this.lastHeartbeat = Date.now()
      this.send({ type: 'heartbeat' })
      this.heartbeatTimer = setInterval(() => {
        if (Date.now() - this.lastHeartbeat > HEARTBEAT_TIMEOUT) socket.close(4000, 'heartbeat timeout')
        else this.send({ type: 'heartbeat' })
      }, HEARTBEAT_INTERVAL)
    }
    socket.onmessage = (event) => {
      if (this.socket !== socket) return
      try {
        const message = JSON.parse(event.data)
        if (message.type === 'snapshot') this.accept(message)
        else if (message.type === 'command') this.engine.enqueue(message.command)
        else if (message.type === 'heartbeat') this.lastHeartbeat = Date.now()
      } catch (error) {
        this.error = error.message || '无法读取播报指令'
        this.notify()
      }
    }
    socket.onclose = (event) => {
      if (this.socket !== socket || this.destroyed) return
      this.socket = null
      this.clearTimers()
      if (this.intentional) return
      if (event.code === PAIRING_REVOKED) {
        this.setStatus('revoked')
        this.engine.pause().catch(() => {})
        return
      }
      this.setStatus('reconnecting')
      const delay = Math.min(15000, 1000 * 2 ** Math.min(this.retryCount++, 4))
      this.reconnectTimer = setTimeout(() => this.connect(true), delay)
    }
  }

  accept(snapshot) {
    clearTimeout(this.handshakeTimer)
    this.retryCount = 0
    this.lastHeartbeat = Date.now()
    // 断线期间播完的片段，服务端还不知道：仍在队列里的补发确认，其余的不用再记。
    const outstanding = new Set((snapshot.commands || []).map(command => command.id))
    for (const id of this.pendingAcks) {
      if (outstanding.has(id)) this.send({ type: 'ack', id })
      else this.pendingAcks.delete(id)
    }
    for (const command of snapshot.commands || []) this.engine.enqueue(command)
    this.setStatus('connected')
    this.send({ type: 'heartbeat' })
  }

  complete(id) {
    this.pendingAcks.add(id)
    while (this.pendingAcks.size > AUDIO_DEDUP_HISTORY_LIMIT) this.pendingAcks.delete(this.pendingAcks.values().next().value)
    this.send({ type: 'ack', id })
  }

  send(message) {
    if (this.socket?.readyState !== 1) return false
    try {
      this.socket.send(JSON.stringify(message))
      return true
    } catch {
      return false
    }
  }

  async togglePause() {
    if (this.engine.snapshot().paused) await this.engine.resume()
    else await this.engine.pause()
  }

  setVolume(percent) {
    this.engine.setVolume(percent / 100)
  }

  retry() {
    this.engine.retry()
  }

  /** 本页不再播放。服务端队列原样保留，之后在任意页面开始播报都会接着播。 */
  async stop() {
    this.intentional = true
    this.clearTimers()
    const socket = this.socket
    this.socket = null
    socket?.close(1000, 'player stopped')
    this.setStatus('idle')
    await this.engine.pause()
  }

  async destroy() {
    this.destroyed = true
    this.intentional = true
    this.clearTimers()
    const socket = this.socket
    this.socket = null
    socket?.close(1000, 'player closed')
    await this.engine.destroy()
  }

  clearTimers() {
    clearTimeout(this.reconnectTimer)
    clearTimeout(this.handshakeTimer)
    clearInterval(this.heartbeatTimer)
  }
}
