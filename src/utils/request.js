/**
 * 统一请求封装
 *
 * 后端契约（见 backend/growth-api/README.md）：
 *   HTTP 状态表示请求结果，响应体保留 { code, message, data }。
 *   code 200 成功；其余为业务错误码，1401 表示未登录 / access token 过期。
 *
 * 这里做三件事：
 *   1. 自动带 Authorization
 *   2. 拆掉 {code,message,data} 信封，调用方直接拿 data
 *   3. 遇到 1401 自动用 refresh token 换一次，再重放原请求
 */

import { accessToken, readTokens, writeTokens, clearAll } from './tokenStore.js'

class ApiError extends Error {
  constructor(message, code, status) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

const API_BASE_URL = import.meta.env?.VITE_API_BASE_URL || ''

/** 同一时刻只允许一个刷新请求，避免并发 401 打出一串刷新。 */
let refreshing = null
let authExpiredNotified = false

/**
 * refresh token 用一次就作废，而 token 存在各标签页共享的 localStorage 里。
 * 用 Web Locks 让多个标签页排队刷新，否则后到的那个会拿旧 token 被拒，再把会话清掉。
 */
function withRefreshLock(task) {
  const locks = globalThis.navigator?.locks
  return locks ? locks.request('wuyao-ai-token-refresh', task) : task()
}

/**
 * 返回 true：本地已有可用的新 token，可以重放；false：会话确实失效。
 * 网络错误直接抛出，不当作会话失效。
 */
async function doRefresh(failedToken) {
  if (!refreshing) {
    refreshing = withRefreshLock(async () => {
      const tokens = readTokens()
      if (!tokens?.refreshToken) return false
      // 排队期间别的标签页（或本页更早的请求）已经换过 token，直接重放。
      if (tokens.accessToken !== failedToken) return true

      let body
      try {
        const res = await fetch(`${API_BASE_URL}/api/auth/refresh`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: tokens.refreshToken }),
        })
        body = await res.json()
      } catch (error) {
        throw new ApiError(error.message || '网络请求失败，请检查后端是否已启动', 'NETWORK_ERROR', 0)
      }

      if (body?.code !== 200 || !body.data) {
        // 不支持 Web Locks 的浏览器里，别的标签页可能抢先轮换了 refresh token。
        const latest = readTokens()?.refreshToken
        return Boolean(latest) && latest !== tokens.refreshToken
      }
      writeTokens({
        accessToken: body.data.accessToken,
        refreshToken: body.data.refreshToken,
        expiresIn: body.data.expiresIn,
        expiresAt: Date.now() + body.data.expiresIn * 1000,
      })
      authExpiredNotified = false
      return true
    }).finally(() => {
      refreshing = null
    })
  }
  return refreshing
}

export async function request(endpoint, options = {}, allowRetry = true) {
  const token = accessToken()

  let response
  try {
    response = await fetch(`${API_BASE_URL}${endpoint}`, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        ...(token && { Authorization: `Bearer ${token}` }),
        ...options.headers,
      },
    })
  } catch (error) {
    if (error.name === 'TimeoutError') {
      throw new ApiError('连接创作服务超时，请检查后端服务后重试', 'NETWORK_ERROR', 0)
    }
    throw new ApiError(error.message || '网络请求失败，请检查后端是否已启动', 'NETWORK_ERROR', 0)
  }

  const body = await response.json().catch(() => null)
  if (!body || typeof body.code !== 'number') {
    if (!response.ok) {
      throw new ApiError(`请求失败: ${response.status} ${response.statusText}`, 'HTTP_ERROR', response.status)
    }
    throw new ApiError('响应格式不符合约定', 'BAD_ENVELOPE', response.status)
  }

  if (body.code === 200 && response.ok) {
    return body.data
  }

  // access token 过期：换一次再重放。刷新接口自己不参与重试，避免递归。
  if (body.code === 1401 && allowRetry && !endpoint.includes('/api/auth/refresh')) {
    const ok = await doRefresh(token)
    if (ok) return request(endpoint, options, false)
    clearAll()
    if (typeof window !== 'undefined' && !authExpiredNotified) {
      authExpiredNotified = true
      window.dispatchEvent(new Event('auth-expired'))
    }
  }

  throw new ApiError(body.message || '请求失败', body.code, response.status)
}

export function get(endpoint, params) {
  const query = params ? `?${new URLSearchParams(params)}` : ''
  return request(`${endpoint}${query}`, { method: 'GET' })
}

export function post(endpoint, data) {
  return request(endpoint, { method: 'POST', body: JSON.stringify(data ?? {}) })
}

export function put(endpoint, data) {
  return request(endpoint, { method: 'PUT', body: JSON.stringify(data ?? {}) })
}

export function patch(endpoint, data) {
  return request(endpoint, { method: 'PATCH', body: JSON.stringify(data ?? {}) })
}

export function del(endpoint) {
  return request(endpoint, { method: 'DELETE' })
}

export { ApiError }
