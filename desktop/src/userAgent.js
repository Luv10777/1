// Electron 默认把软件名写进浏览器标识（User-Agent）：“… 一方志/0.1.0 Chrome/… Electron/… Safari/…”。
// 软件名是中文时，标识里就有了非英文字符。普通请求没事，但后端在建立播报用的长连接（WebSocket）时
// 会拒绝这样的请求，表现为播报一直“重连中”。所以把标识里的软件名换成英文的。

const ENGINE = '(KHTML, like Gecko)'
const APP = 'YifangzhiDesktop'

/** 把标识里的软件名换成英文名；认不出格式时，至少把非英文字符去掉。 */
export function asciiUserAgent(userAgent, version) {
  const value = String(userAgent ?? '')
  const head = value.indexOf(ENGINE)
  const tail = value.indexOf(' Chrome/')
  const replaced = head >= 0 && tail > head
    ? `${value.slice(0, head + ENGINE.length)} ${APP}/${version}${value.slice(tail)}`
    : value
  return replaced.replace(/[^\x20-\x7E]+/g, '').replace(/ {2,}/g, ' ').trim()
}
