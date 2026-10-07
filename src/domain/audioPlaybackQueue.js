const MODES = new Set(['APPEND', 'INTERRUPT', 'CLEAR_REPLAY'])
export const AUDIO_DEDUP_HISTORY_LIMIT = 4096

// 真人说话有气口：讲完一段、被提问打断、答完回到讲解，停下来的长短各不相同。单位毫秒。
const GAPS = {
  betweenSegments: [600, 1200],
  beforeReply: [300, 500],
  afterReply: [350, 600],
}
const isReply = mode => mode === 'INTERRUPT'
// 回复插进来之后，被打断的那段至少还得剩这么长才值得回头接着讲；更短就让它先讲完。
export const MIN_RESUME_TAIL_MILLIS = 5000

/** 上一段声音结束到下一段开始之间留多久。没有上一段（刚开始、暂停后继续）时不留。 */
export function transitionGapMillis(previousMode, nextMode, random = Math.random) {
  if (!previousMode) return 0
  const [min, max] = isReply(nextMode) ? GAPS.beforeReply : isReply(previousMode) ? GAPS.afterReply : GAPS.betweenSegments
  return Math.round(min + (max - min) * random())
}

export function normalizeAudioCommand(value) {
  if (!value || typeof value.id !== 'string' || !value.id.trim() || !MODES.has(value.mode)) {
    throw new Error('无效的播报指令')
  }
  if (typeof value.audioUrl !== 'string' || !value.audioUrl.trim()) throw new Error('播报指令缺少音频')
  const durationMillis = Number(value.durationMillis)
  if (!Number.isFinite(durationMillis) || durationMillis <= 0) throw new Error('音频时长无效')
  const pauseOffsetsMillis = [...new Set((value.pauseOffsetsMillis || []).map(Number))]
    .filter(point => Number.isFinite(point) && point > 0 && point < durationMillis)
    .sort((a, b) => a - b)
  // 回复末尾“接着说”那句从哪里开始；没有就整段照播。
  const outro = Number(value.outroOffsetMillis)
  const outroOffsetMillis = value.outroOffsetMillis != null && Number.isFinite(outro) && outro > 0 && outro < durationMillis ? outro : null
  return { id: value.id, mode: value.mode, text: String(value.text || ''), audioUrl: value.audioUrl, durationMillis, pauseOffsetsMillis, outroOffsetMillis, offsetMillis: 0 }
}

/** Pure queue policy. AudioContext provides elapsed time; no wall-clock playback simulation. */
export class AudioPlaybackQueue {
  constructor() {
    this.current = null
    this.normal = []
    this.priority = []
    this.suspended = []
    this.seen = new Set()
    this.completed = new Set()
  }

  enqueue(value) {
    const command = normalizeAudioCommand(value)
    if (this.seen.has(command.id)) return { accepted: false, discarded: [] }
    this.seen.add(command.id)
    const discarded = []
    if (command.mode === 'CLEAR_REPLAY') {
      for (const item of [this.current, ...this.normal, ...this.priority, ...this.suspended]) {
        if (item) discarded.push(item.id)
      }
      this.current = null
      this.normal = [command]
      this.priority = []
      this.suspended = []
      for (const id of discarded) this.markCompleted(id)
    } else if (command.mode === 'INTERRUPT') this.priority.push(command)
    else this.normal.push(command)
    return { accepted: true, discarded }
  }

  next() {
    if (!this.current) this.current = this.priority.shift() || this.suspended.pop() || this.normal.shift() || null
    return this.current
  }

  interruptionPoint(positionMillis) {
    if (!this.current || this.current.mode === 'INTERRUPT' || !this.priority.length) return null
    const duration = this.current.durationMillis
    if (duration - positionMillis < MIN_RESUME_TAIL_MILLIS) return null
    // Only a server-provided sentence start may interrupt playback. The original segment must
    // still have at least five seconds left there: a shorter tail is not worth coming back to, so
    // the segment is left to finish and the reply follows it.
    return this.current.pauseOffsetsMillis.find(point => point > positionMillis && duration - point >= MIN_RESUME_TAIL_MILLIS) ?? null
  }

  interruptAt(positionMillis) {
    if (!this.current || !this.priority.length) return false
    const allowed = this.current.pauseOffsetsMillis.includes(positionMillis) && this.current.durationMillis - positionMillis >= MIN_RESUME_TAIL_MILLIS
    if (!allowed || this.current.mode === 'INTERRUPT') return false
    this.suspended.push({ ...this.current, offsetMillis: positionMillis })
    this.current = null
    return true
  }

  /**
   * 回复播到“接着说”那句之前时问一次：这句还要不要说。后面紧跟着另一条回复，
   * 或者根本没有讲解可以接回去，说了反而奇怪。
   */
  outroWanted() {
    return !this.priority.length && (this.suspended.length > 0 || this.normal.length > 0)
  }

  /**
   * 排好了但还没出声的讲解让位给回复：原样放回去，回复播完再播它。
   * 被打断过、等着续讲的片段放回等待续讲的位置，其余放回队首。
   */
  defer() {
    const current = this.current
    if (!current || isReply(current.mode) || !this.priority.length) return false
    this.current = null
    if (current.offsetMillis > 0) this.suspended.push(current)
    else this.normal.unshift(current)
    return true
  }

  finish() {
    const id = this.current?.id
    this.current = null
    if (id) this.markCompleted(id)
    return id
  }

  markCompleted(id) {
    this.completed.add(id)
    while (this.completed.size > AUDIO_DEDUP_HISTORY_LIMIT) {
      const oldest = this.completed.values().next().value
      this.completed.delete(oldest)
      // Pending IDs are retained independently of the bounded history window.
      if (this.current?.id !== oldest && !this.pending().some(item => item.id === oldest)) this.seen.delete(oldest)
    }
  }

  pending() { return [...this.priority, ...this.suspended.slice().reverse(), ...this.normal] }
}
