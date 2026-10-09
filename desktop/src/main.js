import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { app, BrowserWindow, dialog, ipcMain, Menu, shell } from 'electron'
import { DanmakuCollector } from './danmakuCollector.js'
import { chatMessage, statusMessage } from './wire.js'
import { normalizeRoomId, resolveRoom } from './roomLookup.js'
import { asciiUserAgent } from './userAgent.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const metadata = createRequire(import.meta.url)('../package.json')
// 窗口里显示的是网页版的直播页（页面发现自己开在桌面端里，会收起平台的其余导航）。
// 正式包连正式平台，开发时连本机的开发服务器。测试包在打包时写入了别的地址（scripts/dist-win.js）。
const CONSOLE_URL = process.env.YIFANGZHI_CONSOLE_URL || metadata.consoleUrl || (app.isPackaged ? 'https://yifangzhi.com' : 'http://localhost:4173')
// 测试包和开发时留着开发者工具，方便看出了什么问题；正式包不给。
const DIAGNOSTICS = !app.isPackaged || Boolean(metadata.consoleUrl)
// 网页版里的“打开桌面端”是一条 yifangzhi://live 链接。安装包登记了这个协议；这里只有正式包认领它。
// 链接里不带任何参数，也不看参数：被它唤起时软件只做一件事，把窗口拿到前面来。
const LINK_SCHEME = app.isPackaged && !metadata.consoleUrl ? 'yifangzhi' : ''
// 软件名是中文，不处理的话会被写进浏览器标识，后端因此拒绝建立播报的长连接。要在打开任何窗口之前换掉。
app.userAgentFallback = asciiUserAgent(app.userAgentFallback, app.getVersion())
// 数据目录用英文名：软件名是中文，有些 Windows 账户下中文路径会出麻烦。正式包和测试包各用各的。
if (app.isPackaged) app.setPath('userData', path.join(app.getPath('appData'), metadata.name))
const LIVE_PATH = '/digital-human'

let consoleWindow = null
let collector = null

const consoleOrigin = () => new URL(CONSOLE_URL).origin
const sameOrigin = (url) => { try { return new URL(url).origin === consoleOrigin() } catch { return false } }

/** 只有控制台页面自己（不是它里面嵌的别的网页）可以使用桌面端能力。 */
function requireConsole(event) {
  const frame = event.senderFrame
  if (!consoleWindow || event.sender !== consoleWindow.webContents || !frame || frame !== consoleWindow.webContents.mainFrame || !sameOrigin(frame.url)) {
    throw new Error('只有控制台页面可以使用这个功能')
  }
}

function stopCollector() {
  collector?.stop()
  collector = null
}

function sendToConsole(message) {
  if (consoleWindow && !consoleWindow.isDestroyed()) consoleWindow.webContents.send('danmaku:message', message)
}

/** 出错时把原因原样带回页面：直接抛出的话，页面拿到的是一串带内部信息的英文。 */
const answer = work => async (event, ...args) => {
  try {
    requireConsole(event)
    return { ok: true, value: await work(...args) }
  } catch (error) {
    return { ok: false, message: error.message || '操作没有成功' }
  }
}

ipcMain.handle('danmaku:resolve-room', answer(input => resolveRoom(input)))

ipcMain.handle('danmaku:start', answer(async (input) => {
  const roomId = normalizeRoomId(input)
  stopCollector()
  const next = new DanmakuCollector({ roomId })
  collector = next
  // 换了直播间或停止之后，上一个采集器迟到的事件不再往页面送。
  next.on('status', status => { if (collector === next) sendToConsole(statusMessage(status)) })
  next.on('chat', chat => { if (collector === next) sendToConsole(chatMessage(chat)) })
  await next.start()
}))

ipcMain.handle('danmaku:stop', answer(() => stopCollector()))

function openConsole() {
  // 默认菜单是英文的一整排（File / Edit / View…），商家用不上，换成自己的。
  const view = {
    label: '视图',
    submenu: [
      { role: 'reload', label: '重新加载' },
      { role: 'togglefullscreen', label: '全屏' },
      ...(DIAGNOSTICS ? [{ role: 'toggleDevTools', label: '开发者工具' }] : []),
    ],
  }
  // macOS 上复制、粘贴这些快捷键是菜单给的：没有“编辑”菜单，Cmd+V 就粘贴不了分享链接。
  // 第一项在 macOS 上是以软件名显示的那个菜单，退出、隐藏放在里面。Windows 不需要这两项。
  const mac = [
    { label: app.getName(), submenu: [
      { role: 'about', label: `关于${app.getName()}` },
      { type: 'separator' },
      { role: 'hide', label: `隐藏${app.getName()}` },
      { role: 'hideOthers', label: '隐藏其他' },
      { type: 'separator' },
      { role: 'quit', label: `退出${app.getName()}` },
    ] },
    { label: '编辑', submenu: [
      { role: 'undo', label: '撤销' },
      { role: 'redo', label: '重做' },
      { type: 'separator' },
      { role: 'cut', label: '剪切' },
      { role: 'copy', label: '复制' },
      { role: 'paste', label: '粘贴' },
      { role: 'selectAll', label: '全选' },
    ] },
  ]
  Menu.setApplicationMenu(Menu.buildFromTemplate(process.platform === 'darwin' ? [...mac, view] : [view]))
  consoleWindow = new BrowserWindow({
    width: 1360,
    height: 900,
    minWidth: 1024,
    minHeight: 700,
    title: `${app.getName()} · AI 实景直播`,
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(here, 'preload.cjs'),
      sandbox: true,
      contextIsolation: true,
      nodeIntegration: false,
    },
  })
  const contents = consoleWindow.webContents
  // 控制台以外的链接交给系统浏览器，窗口本身不离开控制台。
  contents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url)) shell.openExternal(url)
    return { action: 'deny' }
  })
  contents.on('will-navigate', (event, url) => {
    if (sameOrigin(url)) return
    event.preventDefault()
    if (/^https?:/.test(url)) shell.openExternal(url)
  })
  // 页面刷新或换页后，上一页开着的采集不该继续。
  contents.on('did-start-navigation', (_event, _url, isInPlace, isMainFrame) => { if (isMainFrame && !isInPlace) stopCollector() })
  contents.on('did-fail-load', (_event, code, description, url, isMainFrame) => {
    if (isMainFrame && code !== -3) dialog.showErrorBox('打不开控制台', `${url}\n${description || code}`)
  })
  consoleWindow.on('closed', () => { consoleWindow = null; stopCollector(); app.quit() })
  consoleWindow.loadURL(new URL(LIVE_PATH, CONSOLE_URL).href)
}

if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  // Windows 上点链接会再启动一次软件：新的那个立刻退出，已经开着的这个到前面来。
  const surface = () => {
    if (!consoleWindow) return
    if (consoleWindow.isMinimized()) consoleWindow.restore()
    consoleWindow.show()
    consoleWindow.focus()
  }
  app.on('second-instance', surface)
  // macOS 上链接是以事件送来的。
  app.on('open-url', (event) => { event.preventDefault(); surface() })
  app.whenReady().then(() => {
    if (LINK_SCHEME) app.setAsDefaultProtocolClient(LINK_SCHEME)
    openConsole()
  })
}

// 采集窗口不显示；关掉控制台窗口时由上面的 closed 负责退出。
app.on('window-all-closed', () => {})
