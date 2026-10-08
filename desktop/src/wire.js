// 采集器的事件 → 交给控制台页面的文本。
// 页面那边由 src/services/liveDanmakuRelay.js 的 parseDanmakuMessage 读取，两边的约定由 wire.test.js 守着。

export const statusMessage = ({ code, live, text }) =>
  JSON.stringify({ type: 'system', event: 'live_status', code, live, status_text: text })

export const chatMessage = ({ id, text }) =>
  JSON.stringify({ method: 'WebcastChatMessage', common: { msgId: id }, content: text })
