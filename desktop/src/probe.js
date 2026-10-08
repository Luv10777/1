// 可行性验证：对着一个直播间跑采集器，把看到的东西打印出来。
//   npm run probe -- <抖音分享链接或直播间号> [最多运行几分钟，默认 20]
// 只打印弹幕文字、弹幕 ID 和各类消息的条数，不读也不打印观众的昵称或账号。
// 看到过开播、之后又下播，就在下播后不久自动退出。
import { app } from 'electron'
import { DanmakuCollector } from './danmakuCollector.js'
import { resolveRoom } from './roomLookup.js'

const stamp = () => new Date().toTimeString().slice(0, 8)
const say = (...parts) => console.log(stamp(), ...parts)

const args = process.argv.slice(2).filter(arg => !arg.startsWith('-'))
const minutes = /^\d{1,3}$/.test(args.at(-1) || '') && args.length > 1 ? Number(args.pop()) : 20
const input = (args.pop() || '').trim()

app.whenReady().then(async () => {
  app.dock?.hide()
  if (!input) throw new Error('用法：npm run probe -- <抖音分享链接或直播间号> [分钟]')
  const roomId = await resolveRoom(input)
  say('直播间号', roomId, `最多运行 ${minutes} 分钟`)

  const collector = new DanmakuCollector({ roomId })
  const seen = new Set()
  let chats = 0
  let repeats = 0
  let wasLive = false
  let lastStatus = ''
  let lastRoom = ''
  let lastStats = ''
  const summary = (stats) => {
    const kinds = Object.entries(stats.kinds).map(([name, count]) => `${name}=${count}`).join(' ')
    const methods = Object.entries(stats.methods).sort((a, b) => b[1] - a[1]).map(([name, count]) => `${name.replace(/^Webcast|Message$/g, '')}=${count}`).join(' ')
    return `帧=${stats.frames} (${kinds}) 弹幕=${chats} 重复ID=${repeats} ${methods}`
  }
  const quit = (reason) => {
    say('[结束]', reason, lastStats)
    collector.stop()
    app.quit()
  }

  collector.on('status', (status) => {
    // 没开播时每半分钟重查一次，同样的状态不重复打印。
    if (status.code !== 'OPENING' && status.code !== lastStatus) say('[状态]', status.code, status.text)
    if (status.code !== 'OPENING') lastStatus = status.code
    if (status.live) wasLive = true
    else if (wasLive && status.code === 'ROOM_ENDED') setTimeout(() => quit('已下播'), 8000)
  })
  collector.on('room', (room) => {
    const line = `内部房间号 ${room.id} ${room.live ? '在播' : '未在播'}`
    if (line !== lastRoom) say('[房间]', line)
    lastRoom = line
  })
  collector.on('chat', (chat) => {
    chats += 1
    if (seen.has(chat.id)) repeats += 1
    seen.add(chat.id)
    say('[弹幕]', chat.id || '(无ID)', chat.text)
  })
  collector.on('stats', (stats) => {
    lastStats = summary(stats)
    // 心跳之外每多 25 帧汇报一次各类消息的条数。
    const messages = stats.frames - (stats.kinds.hb || 0)
    if (messages > 0 && messages % 25 === 0) say('[统计]', lastStats)
  })
  await collector.start()

  setTimeout(() => quit('到时间了'), minutes * 60000)
  process.on('SIGINT', () => quit('手动停止'))
  process.on('SIGTERM', () => quit('手动停止'))
}).catch((error) => {
  console.error(stamp(), '[失败]', error.message)
  app.exit(1)
})

// 采集窗口不显示，也不该因为它关闭而退出。
app.on('window-all-closed', () => {})
