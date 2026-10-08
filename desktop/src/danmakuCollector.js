import { EventEmitter } from 'node:events'
import { BrowserWindow } from 'electron'
import { readFrame } from './douyinFrames.js'

// 直播间网页用来接收弹幕的那条长连接。没开播时网页也会建立它，只是里面只有心跳。
const PUSH_SOCKET = /^wss:\/\/[a-z0-9.-]+\.douyin\.com\/webcast\/im\/push/
// 网页自己查询直播间状态的请求，回应里有“是否在播”和这一场的内部房间号。
const ROOM_LOOKUP = '/webcast/room/web/enter'
const STATUS_LIVE = 2
// 打开网页后这么久还读不到直播间状态，就先当作没在播。
const WAIT_FOR_ROOM = 25000
// 没在播时隔这么久重新打开一次网页，看看开播了没有。
const RECHECK = 30000

/**
 * 在一个不显示的窗口里打开商家自己的直播间网页，读网页收到的弹幕。
 * 连接、签名都由抖音自己的网页完成，这里只旁听它收到了什么，所以抖音改签名算法不影响这里。
 *
 * 事件：
 *   status  { code, live, text }  code 取 OPENING | ROOM_ONLINE | ROOM_OFFLINE | ROOM_ENDED | PAGE_FAILED
 *   chat    { id, text }
 *   room    { id, live }           网页查到的直播间状态，排查用
 *   stats   { frames, kinds, methods }  各类帧和消息的条数，排查用，不含内容
 */
export class DanmakuCollector extends EventEmitter {
  constructor({ roomId }) {
    super()
    this.roomId = roomId
    this.window = null
    this.stopped = true
    this.timer = null
    this.room = { id: '', live: false }
    // 弹幕连接 → 它所属的内部房间号
    this.sockets = new Map()
    this.lookups = new Map()
    this.frames = 0
    this.kinds = {}
    this.methods = {}
  }

  async start() {
    if (!this.stopped) return
    this.stopped = false
    this.window = new BrowserWindow({
      show: false,
      width: 1280,
      height: 800,
      webPreferences: {
        // 抖音网页的登录状态和我们控制台的分开存放，互不可见。
        partition: 'persist:douyin-live',
        backgroundThrottling: false,
        sandbox: true,
        contextIsolation: true,
        nodeIntegration: false,
      },
    })
    const contents = this.window.webContents
    contents.setAudioMuted(true)
    contents.setWindowOpenHandler(() => ({ action: 'deny' }))
    contents.on('did-fail-load', (_event, code, description, _url, isMainFrame) => {
      // -3 是上一次加载被新的一次顶掉，不是打不开。
      if (isMainFrame && code !== -3) this.offline('PAGE_FAILED', `直播间网页打不开（${description || code}）`)
    })
    contents.on('render-process-gone', () => this.offline('PAGE_FAILED', '直播间网页意外关闭，稍后重新打开'))
    // 还没有页面时调试通道不应答，先放一张空白页。
    await contents.loadURL('about:blank')
    contents.debugger.attach('1.3')
    contents.debugger.on('message', (_event, method, params, sessionId) => this.observe(method, params, sessionId))
    await contents.debugger.sendCommand('Network.enable')
    // 弹幕连接可能建在网页的后台线程里，一并旁听。
    await contents.debugger.sendCommand('Target.setAutoAttach', { autoAttach: true, waitForDebuggerOnStart: false, flatten: true })
    this.open()
  }

  open() {
    if (this.stopped) return
    clearTimeout(this.timer)
    this.room = { id: '', live: false }
    this.sockets.clear()
    this.lookups.clear()
    this.emit('status', { code: 'OPENING', live: false, text: '正在打开直播间网页…' })
    this.window.webContents.loadURL(`https://live.douyin.com/${encodeURIComponent(this.roomId)}`).catch(() => {})
    this.timer = setTimeout(() => this.offline('ROOM_OFFLINE', '没有读到直播间状态，稍后重试'), WAIT_FOR_ROOM)
  }

  offline(code, text) {
    if (this.stopped) return
    clearTimeout(this.timer)
    this.room.live = false
    this.emit('status', { code, live: false, text })
    this.timer = setTimeout(() => this.open(), RECHECK)
  }

  online() {
    clearTimeout(this.timer)
    if (this.room.live) return
    this.room.live = true
    this.emit('status', { code: 'ROOM_ONLINE', live: true, text: '直播间已开播，正在接收弹幕' })
  }

  observe(method, params, sessionId) {
    if (this.stopped) return
    const debug = this.window.webContents.debugger
    if (method === 'Target.attachedToTarget') {
      debug.sendCommand('Network.enable', {}, params.sessionId).catch(() => {})
      return
    }
    const key = `${sessionId || ''}:${params.requestId}`
    if (method === 'Network.responseReceived') {
      if (new URL(params.response.url).pathname.startsWith(ROOM_LOOKUP)) this.lookups.set(key, sessionId)
    } else if (method === 'Network.loadingFinished') {
      if (!this.lookups.has(key)) return
      const session = this.lookups.get(key)
      this.lookups.delete(key)
      debug.sendCommand('Network.getResponseBody', { requestId: params.requestId }, session || undefined)
        .then(({ body, base64Encoded }) => this.looked(base64Encoded ? Buffer.from(body, 'base64').toString('utf8') : body))
        .catch(() => {})
    } else if (method === 'Network.webSocketCreated') {
      if (PUSH_SOCKET.test(params.url)) this.sockets.set(key, new URL(params.url).searchParams.get('room_id') || '')
    } else if (method === 'Network.webSocketFrameReceived') {
      // opcode 2 是二进制帧，内容以 base64 给出。
      if (!this.sockets.has(key) || params.response.opcode !== 2) return
      // 网页上还有推荐的其他直播间；只读这个直播间自己的连接。
      const owner = this.sockets.get(key)
      if (this.room.id && owner && owner !== this.room.id) return
      this.receive(Buffer.from(params.response.payloadData, 'base64'))
    } else if (method === 'Network.webSocketClosed') {
      this.sockets.delete(key)
    }
  }

  /** 网页查到了直播间状态。只取状态和内部房间号，其余资料不看。 */
  looked(body) {
    if (this.stopped) return
    let room
    try {
      const first = JSON.parse(body).data.data[0]
      room = { id: String(first.id_str || ''), live: first.status === STATUS_LIVE }
    } catch { return }
    this.room.id = room.id
    this.emit('room', room)
    if (room.live) this.online()
    else if (!this.room.live) this.offline('ROOM_OFFLINE', '直播间未开播')
  }

  receive(buffer) {
    let frame
    // 读不懂的帧不影响后面的。
    try { frame = readFrame(buffer) } catch { this.kinds.unreadable = (this.kinds.unreadable || 0) + 1; return }
    this.frames += 1
    this.kinds[frame.type || '?'] = (this.kinds[frame.type || '?'] || 0) + 1
    for (const [name, count] of Object.entries(frame.methods)) this.methods[name] = (this.methods[name] || 0) + count
    for (const event of frame.events) {
      if (event.kind === 'ended') {
        this.offline('ROOM_ENDED', '直播间已下播')
        break
      }
      // 没等到状态查询的结果也算开播：弹幕已经在来了。
      this.online()
      this.emit('chat', { id: event.id, text: event.text })
    }
    this.emit('stats', { frames: this.frames, kinds: { ...this.kinds }, methods: { ...this.methods } })
  }

  stop() {
    if (this.stopped) return
    this.stopped = true
    clearTimeout(this.timer)
    const window = this.window
    this.window = null
    if (window && !window.isDestroyed()) {
      try { window.webContents.debugger.detach() } catch { /* already detached with the window */ }
      window.destroy()
    }
  }
}
