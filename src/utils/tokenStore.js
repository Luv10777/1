/**
 * Token 存储。request.js 和 stores/auth.js 共用这一处，
 * 避免两边各存一份、键名对不上。
 */

const TOKEN_KEY = 'wuyao-ai-token'
const USER_KEY = 'wuyao-ai-auth'

export function readTokens() {
  try {
    const raw = localStorage.getItem(TOKEN_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

export function writeTokens(tokens) {
  localStorage.setItem(TOKEN_KEY, JSON.stringify(tokens))
}

export function readUser() {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

export function writeUser(user) {
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function clearAll() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

export function accessToken() {
  return readTokens()?.accessToken ?? null
}
