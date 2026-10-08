// 控制台页面能用到的全部桌面端能力。页面拿不到 Node，也拿不到这里以外的任何接口。
const { contextBridge, ipcRenderer } = require('electron')

const ask = async (channel, ...args) => {
  const result = await ipcRenderer.invoke(channel, ...args)
  if (!result.ok) throw new Error(result.message)
  return result.value
}

contextBridge.exposeInMainWorld('yifangzhiDesktop', {
  danmaku: {
    /** 分享链接或直播间号 → 直播间号 */
    resolveRoom: input => ask('danmaku:resolve-room', String(input ?? '')),
    start: roomId => ask('danmaku:start', String(roomId ?? '')),
    stop: () => ask('danmaku:stop'),
    /** 订阅直播间状态和弹幕，返回取消订阅的函数。 */
    onMessage: (listener) => {
      const handler = (_event, data) => listener(data)
      ipcRenderer.on('danmaku:message', handler)
      return () => ipcRenderer.removeListener('danmaku:message', handler)
    },
  },
})
