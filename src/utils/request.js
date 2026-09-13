/**
 * 统一请求封装
 *
 * 后端契约（见 backend/growth-api/README.md）：
 *   所有接口 HTTP 200，业务结果在 body 里：{ code, message, data }
 *   code 200 成功；其余为业务错误码，1401 表示未登录 / access token 过期。
 *
 * 这里做三件事：
 *   1. 自动带 Authorization
 *   2. 拆掉 {code,message,data} 信封，调用方直接拿 data
 *   3. 遇到 1401 自动用 refresh token 换一次，再重放原请求
 */

import { accessToken, readTokens, writeTokens, clearAll } from './tokenStore'

class ApiError extends Error {
  constructor(message, code, status) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || ''

/** 同一时刻只允许一个刷新请求，避免并发 401 打出一串刷新。 */
let refreshing = null

async function doRefresh() {
  const tokens = readTokens()
  if (!tokens?.refreshToken) return false

  if (!refreshing) {
    refreshing = fetch(`${API_BASE_URL}/api/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: tokens.refreshToken }),
    })
      .then((res) => res.json())
      .then((body) => {
        if (body?.code !== 200 || !body.data) return false
        writeTokens({
          accessToken: body.data.accessToken,
          refreshToken: body.data.refreshToken,
          expiresIn: body.data.expiresIn,
          expiresAt: Date.now() + body.data.expiresIn * 1000,
        })
        return true
      })
      .catch(() => false)
      .finally(() => {
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
    throw new ApiError(error.message || '网络请求失败，请检查后端是否已启动', 'NETWORK_ERROR', 0)
  }

  if (!response.ok) {
    throw new ApiError(`请求失败: ${response.status} ${response.statusText}`, 'HTTP_ERROR', response.status)
  }

  const body = await response.json().catch(() => null)
  if (!body || typeof body.code !== 'number') {
    throw new ApiError('响应格式不符合约定', 'BAD_ENVELOPE', response.status)
  }

  if (body.code === 200) {
    return body.data
  }

  // access token 过期：换一次再重放。刷新接口自己不参与重试，避免递归。
  if (body.code === 1401 && allowRetry && !endpoint.includes('/api/auth/refresh')) {
    const ok = await doRefresh()
    if (ok) return request(endpoint, options, false)
    clearAll()
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

export function del(endpoint) {
  return request(endpoint, { method: 'DELETE' })
}

export { ApiError }
