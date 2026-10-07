import { AudioPlaybackQueue, transitionGapMillis } from '../domain/audioPlaybackQueue.js'

export class BrowserAudioPlayer {
  constructor({ onChange = () => {}, onComplete = () => {}, audioBaseUrl, getMediaToken = () => '', random = Math.random } = {}) {
    this.queue = new AudioPlaybackQueue()
    this.onChange = onChange
    this.onComplete = onComplete
    this.audioBaseUrl = audioBaseUrl
    this.getMediaToken = getMediaToken
    this.context = null
    this.gain = null
    this.source = null
    this.started = false
    this.paused = false
    this.loading = false
    this.error = ''
    this.volume = 0.75
    this.startedAt = 0
    this.startOffset = 0
    this.stopPoint = null
    this.outroStop = false
    this.random = random
    // 上一段声音是什么、在音频时钟的哪一刻结束；据此给下一段留出停顿。
    this.lastEnded = null
    this.precededBy = null
    this.generation = 0
    this.buffers = new Map()
    this.requests = new Map()
    this.requestControllers = new Map()
    this.disposed = false
  }

  async unlock() {
    if (!this.context) {
      const Context = window.AudioContext || window.webkitAudioContext
      if (!Context) throw new Error('当前浏览器不支持 Web Audio，请更换浏览器。')
      this.context = new Context()
      this.gain = this.context.createGain()
      this.gain.gain.value = this.volume
      this.gain.connect(this.context.destination)
      this.context.onstatechange = () => this.notify()
    }
    // Called directly from a click handler, before any network request.
    await this.context.resume()
    if (this.context.state !== 'running') throw new Error('浏览器尚未允许播放声音，请再次点击开始播报。')
    this.started = true
    this.paused = false
    this.notify()
    this.pump()
  }

  positionMillis() {
    if (!this.queue.current) return 0
    return Math.min(this.queue.current.durationMillis, this.source
      ? this.startOffset + Math.max(0, this.context.currentTime - this.startedAt) * 1000
      : this.queue.current.offsetMillis)
  }

  snapshot() {
    return { current: this.queue.current ? { ...this.queue.current } : null, pending: this.queue.pending().map(item => ({ ...item })), positionMillis: this.positionMillis(), paused: this.paused, loading: this.loading, error: this.error, audioState: this.context?.state || 'uninitialized' }
  }

  notify() { this.onChange(this.snapshot()) }

  enqueue(command) {
    const result = this.queue.enqueue(command)
    if (!result.accepted) {
      if (this.queue.completed.has(command.id)) this.onComplete(command.id)
      return
    }
    if (command.mode === 'CLEAR_REPLAY') {
      this.generation += 1
      this.stopSource()
      this.lastEnded = null
      this.loading = false
      for (const id of result.discarded) {
        this.buffers.delete(id)
        this.requestControllers.get(id)?.abort()
        this.onComplete(id)
      }
    }
    this.notify()
    // Preparing early avoids a network fetch at a speech pause. A reply is only
    // scheduled into a pause after its complete audio buffer is available.
    this.prefetch()
    this.scheduleInterruption()
    this.pump()
  }

  prefetch() {
    const soon = [this.queue.current, ...this.queue.priority.slice(0, 2), ...this.queue.suspended.slice(-1), ...this.queue.normal.slice(0, 2)].filter(Boolean)
    for (const command of soon) this.prepare(command).then(() => this.scheduleInterruption()).catch(() => {})
  }

  prepare(command) {
    if (this.buffers.has(command.id)) return Promise.resolve(this.buffers.get(command.id))
    if (this.requests.has(command.id)) return this.requests.get(command.id)
    if (!this.context) return Promise.reject(new Error('请先点击开始播报'))
    const controller = new AbortController()
    this.requestControllers.set(command.id, controller)
    const promise = (async () => {
      const base = new URL(this.audioBaseUrl || window.location.origin)
      const url = new URL(command.audioUrl, base)
      if (!['http:', 'https:'].includes(url.protocol)) throw new Error('音频链接格式无效')
      if (url.origin === base.origin && /^\/api\/player\/audio\/[a-zA-Z0-9-]+$/.test(url.pathname)) {
        const token = this.getMediaToken()
        if (!token) throw new Error('缺少音频配对凭证，请重新配对。')
        url.searchParams.set('token', token)
      }
      const response = await fetch(url.href, { signal: controller.signal, credentials: 'same-origin', referrerPolicy: 'no-referrer' })
      if (!response.ok) throw new Error(`音频加载失败（${response.status}），请重试。`)
      const data = await response.arrayBuffer()
      const buffer = await this.context.decodeAudioData(data)
      if (!controller.signal.aborted && !this.disposed) this.buffers.set(command.id, buffer)
      return buffer
    })().finally(() => { this.requests.delete(command.id); this.requestControllers.delete(command.id) })
    this.requests.set(command.id, promise)
    return promise
  }

  async pump() {
    if (!this.started || this.paused || this.source || this.loading) return
    const command = this.queue.next()
    if (!command) { this.notify(); return }
    const generation = this.generation
    this.loading = true
    this.prefetch()
    this.error = ''
    this.notify()
    try {
      const buffer = await this.prepare(command)
      if (generation !== this.generation || this.paused || this.queue.current?.id !== command.id) return
      command.durationMillis = buffer.duration * 1000
      command.pauseOffsetsMillis = command.pauseOffsetsMillis.filter(point => point < command.durationMillis)
      if (command.outroOffsetMillis != null && command.outroOffsetMillis >= command.durationMillis) command.outroOffsetMillis = null
      if (command.offsetMillis >= command.durationMillis) {
        this.loading = false
        this.completeCurrent()
        return
      }
      const source = this.context.createBufferSource()
      source.buffer = buffer
      source.connect(this.gain)
      this.source = source
      // 停顿排在音频时钟上，不用定时器：加载和解码花掉的时间算在停顿里。
      const now = this.context.currentTime
      const previous = this.lastEnded
      this.lastEnded = null
      // 留着：这一段要是还没出声就让位给回复，回复的停顿仍从上一段结束时算起。
      this.precededBy = previous
      const when = previous
        ? Math.max(now, previous.at + transitionGapMillis(previous.mode, command.mode, this.random) / 1000)
        : now
      this.startOffset = command.offsetMillis
      this.startedAt = when
      this.stopPoint = null
      this.outroStop = false
      this.loading = false
      source.onended = () => {
        if (this.source !== source) return
        source.disconnect()
        this.source = null
        const point = this.stopPoint
        const atOutro = this.outroStop
        this.stopPoint = null
        this.outroStop = false
        if (atOutro && point != null && this.queue.outroWanted()) {
          // 接着播同一段的结尾，中间不另加停顿。
          command.offsetMillis = point
          command.outroOffsetMillis = null
          this.pump()
        } else {
          this.lastEnded = { mode: command.mode, at: this.context.currentTime }
          if (!atOutro && point != null && this.queue.interruptAt(point)) this.pump()
          else this.completeCurrent()
        }
        this.notify()
      }
      source.start(when > now ? when : 0, command.offsetMillis / 1000)
      this.scheduleOutro()
      this.scheduleInterruption()
      this.notify()
    } catch (error) {
      if (generation !== this.generation || error.name === 'AbortError') return
      this.loading = false
      this.error = error.message || '音频无法播放，请重新加载。'
      this.notify()
    }
  }

  scheduleInterruption() {
    if (!this.source || this.stopPoint != null || !this.buffers.has(this.queue.priority[0]?.id)) return
    // 下一段讲解只是排好了、还在停顿里没出声：回复直接先播，不必等它讲完第一句。
    if (this.context.currentTime < this.startedAt && this.queue.defer()) {
      this.lastEnded = this.precededBy
      this.stopSource()
      this.pump()
      return
    }
    const position = this.positionMillis()
    const point = this.queue.interruptionPoint(position + 20)
    if (point == null) return
    this.stopPoint = point
    this.source.stop(this.stopTime(point))
  }

  /** 回复自带的“接着说”先停在它前面，到那一刻再决定说不说。 */
  scheduleOutro() {
    const point = this.queue.current?.outroOffsetMillis
    if (!this.source || this.stopPoint != null || point == null || point <= this.startOffset) return
    this.stopPoint = point
    this.outroStop = true
    this.source.stop(this.stopTime(point))
  }

  /** 片段内的位置换算成音频时钟上的时刻；片段还在停顿里没出声时同样成立。 */
  stopTime(point) {
    return this.startedAt + (point - this.startOffset) / 1000
  }

  completeCurrent() {
    const id = this.queue.finish()
    if (id) { this.buffers.delete(id); this.onComplete(id) }
    this.pump()
  }

  stopSource() {
    if (!this.source) return
    this.source.onended = null
    try { this.source.stop() } catch { /* It may already have ended. */ }
    this.source.disconnect()
    this.source = null
    this.stopPoint = null
    this.outroStop = false
  }

  async pause() {
    this.paused = true
    this.generation += 1
    this.lastEnded = null
    if (this.queue.current) this.queue.current.offsetMillis = this.positionMillis()
    this.stopSource()
    this.loading = false
    await this.context?.suspend()
    this.notify()
  }

  async resume() { await this.unlock() }

  setVolume(value) {
    this.volume = Math.min(1, Math.max(0, value))
    if (this.gain) this.gain.gain.setTargetAtTime(this.volume, this.context.currentTime, 0.015)
  }

  retry() { this.error = ''; this.pump() }

  async destroy() {
    this.started = false
    this.disposed = true
    this.generation += 1
    for (const controller of this.requestControllers.values()) controller.abort()
    this.stopSource()
    this.buffers.clear()
    if (this.context) { this.context.onstatechange = null; await this.context.close() }
  }
}
