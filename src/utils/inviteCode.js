// 邀请链接 /login?invite= 带来的邀请码。微信登录要跳出去再回来，链接参数会丢，所以先存进本地，
// 30 天内有效；登录成功就清掉（老账号登录不会改归属，新账号注册时已经交给后端）。
const KEY = 'wuyao-invite-code'
const TTL_MS = 30 * 24 * 60 * 60 * 1000
const PATTERN = /^[A-Za-z0-9]{4,16}$/

export function rememberInviteCode(value, now = Date.now()) {
  const code = String(value || '').trim().toUpperCase()
  if (!PATTERN.test(code)) return ''
  try { localStorage.setItem(KEY, JSON.stringify({ code, at: now })) } catch { /* 本地存储不可用时只对当前页面生效 */ }
  return code
}

export function pendingInviteCode(now = Date.now()) {
  try {
    const saved = JSON.parse(localStorage.getItem(KEY) || 'null')
    if (saved && PATTERN.test(saved.code) && now - Number(saved.at) < TTL_MS) return saved.code
  } catch { /* 读不到就当没有 */ }
  return ''
}

export function clearInviteCode() {
  try { localStorage.removeItem(KEY) } catch { /* 忽略 */ }
}
