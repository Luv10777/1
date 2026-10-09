// 在一方志控制台页面自己的脚本运行之前放进去的脚本（只放进控制台自己的页面）。
// 它在页面里搭一座桥 window.yifangzhiDesktop，和桌面端的那座（desktop/src/preload.cjs）长得一样，
// 所以页面不用区分自己开在桌面软件里还是手机软件里：读弹幕、被链接唤起，都走同样的几个函数。
// 桥的另一头是软件本身（ConsoleWeb.java）。页面能用到的软件能力全在这里，别的拿不到。
(() => {
  const native = window.YifangzhiNative
  if (!native || window.yifangzhiDesktop) return
  let next = 1
  const waiting = new Map()
  const danmaku = new Set()
  const links = new Set()
  const each = (listeners, ...args) => listeners.forEach((listener) => { try { listener(...args) } catch { /* 一个听的人出错不影响别人 */ } })

  native.onmessage = (event) => {
    let message
    try { message = JSON.parse(event.data) } catch { return }
    if (message.kind === 'answer') {
      const asked = waiting.get(message.id)
      if (!asked) return
      waiting.delete(message.id)
      if (message.ok) asked.resolve(message.value ?? null)
      else asked.reject(new Error(message.message || '操作没有成功'))
    } else if (message.kind === 'danmaku') {
      each(danmaku, message.data)
    } else if (message.kind === 'link') {
      each(links)
    }
  }

  const ask = (method, ...args) => new Promise((resolve, reject) => {
    const id = next++
    waiting.set(id, { resolve, reject })
    native.postMessage(JSON.stringify({ id, method, args }))
  })
  const subscribe = (listeners, listener) => { listeners.add(listener); return () => listeners.delete(listener) }

  window.yifangzhiDesktop = {
    platform: 'android',
    danmaku: {
      /** 分享链接或直播间号 → 直播间号 */
      resolveRoom: input => ask('danmaku.resolveRoom', String(input ?? '')),
      start: roomId => ask('danmaku.start', String(roomId ?? '')),
      stop: () => ask('danmaku.stop'),
      /** 订阅直播间状态和弹幕，返回取消订阅的函数。 */
      onMessage: listener => subscribe(danmaku, listener),
    },
    link: {
      take: () => ask('link.take'),
      onOpen: listener => subscribe(links, listener),
    },
  }
  // 打个招呼：软件要先收到页面的一句话，才有办法主动往页面送东西。
  ask('hello').catch(() => {})
})()
