// 在抖音直播间网页自己的脚本运行之前放进去的一小段脚本（只放进 live.douyin.com 的页面）。
// 安卓上没有桌面端那种“旁听网络”的办法，只能在页面里做三件事，做完把看到的交给软件：
//   1. 网页建立接收弹幕的那条长连接时，把它收到的每一帧原样抄一份；
//   2. 网页自己查询直播间状态时，把回应抄一份；
//   3. 别的一概不碰：不改请求、不发请求、不读页面上的任何内容。
// 连接和签名仍由抖音自己的网页完成。解读这些数据在软件里做（DouyinFrames.java），不在这里。
(() => {
  const out = window.YifangzhiCollector
  if (!out || window.__yifangzhiCollector) return
  window.__yifangzhiCollector = true
  const tell = (message) => { try { out.postMessage(JSON.stringify(message)) } catch { /* 软件那头不在了 */ } }
  // 先报个到：软件靠这一句知道脚本确实赶在网页自己的脚本之前放进去了。
  tell({ kind: 'ready' })

  const PUSH_SOCKET = /^wss:\/\/[a-z0-9.-]+\.douyin\.com\/webcast\/im\/push/
  const ROOM_LOOKUP = '/webcast/room/web/enter'

  const base64 = (bytes) => {
    let text = ''
    for (let at = 0; at < bytes.length; at += 0x8000) text += String.fromCharCode.apply(null, bytes.subarray(at, at + 0x8000))
    return btoa(text)
  }

  // 用 Proxy 包一层而不是换成自己写的函数：包完之后它在网页眼里仍是浏览器原来的那个。
  window.WebSocket = new Proxy(window.WebSocket, {
    construct(target, args) {
      const socket = new target(...args)
      try {
        const url = String(args[0])
        if (PUSH_SOCKET.test(url)) {
          const room = new URL(url).searchParams.get('room_id') || ''
          tell({ kind: 'socket', room })
          socket.addEventListener('message', (event) => {
            const data = event.data
            if (data instanceof ArrayBuffer) tell({ kind: 'frame', room, data: base64(new Uint8Array(data)) })
            else if (data instanceof Blob) data.arrayBuffer().then(buffer => tell({ kind: 'frame', room, data: base64(new Uint8Array(buffer)) })).catch(() => {})
          })
          socket.addEventListener('close', () => tell({ kind: 'closed', room }))
        }
      } catch { /* 抄不到就算了，不能影响网页自己 */ }
      return socket
    },
  })

  window.fetch = new Proxy(window.fetch, {
    apply(target, self, args) {
      const pending = Reflect.apply(target, self, args)
      try {
        const url = typeof args[0] === 'string' ? args[0] : args[0]?.url
        if (url && String(url).includes(ROOM_LOOKUP)) pending.then(response => response.clone().text()).then(body => tell({ kind: 'room', body })).catch(() => {})
      } catch { /* 同上 */ }
      return pending
    },
  })

  XMLHttpRequest.prototype.open = new Proxy(XMLHttpRequest.prototype.open, {
    apply(target, self, args) {
      try {
        if (String(args[1]).includes(ROOM_LOOKUP)) {
          self.addEventListener('load', () => {
            try { tell({ kind: 'room', body: typeof self.response === 'string' ? self.response : JSON.stringify(self.response) }) } catch { /* 同上 */ }
          })
        }
      } catch { /* 同上 */ }
      return Reflect.apply(target, self, args)
    },
  })
})()
