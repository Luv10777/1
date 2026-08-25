/**
 * 统一请求封装
 */

class ApiError extends Error {
  constructor(message, code, status) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || ''
const DEMO_MODE = !API_BASE_URL || import.meta.env.VITE_AUTH_MODE === 'mock'

/**
 * 统一请求方法
 * @param {string} endpoint
 * @param {RequestInit} options
 * @returns {Promise<any>}
 */
export async function request(endpoint, options = {}) {
  if (DEMO_MODE && !endpoint.startsWith('/mock')) {
    console.warn(`[DEMO MODE] API call to ${endpoint} - using mock data`)
    throw new ApiError('API未配置，当前为演示模式', 'DEMO_MODE', 503)
  }

  const url = `${API_BASE_URL}${endpoint}`
  const token = localStorage.getItem('auth_token')

  const config = {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(token && { Authorization: `Bearer ${token}` }),
      ...options.headers,
    },
  }

  try {
    const response = await fetch(url, config)

    if (!response.ok) {
      const error = await response.json().catch(() => ({}))
      throw new ApiError(
        error.message || `请求失败: ${response.statusText}`,
        error.code || 'REQUEST_FAILED',
        response.status
      )
    }

    return await response.json()
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError(error.message || '网络请求失败', 'NETWORK_ERROR', 0)
  }
}

export function get(endpoint, params) {
  const query = params ? `?${new URLSearchParams(params)}` : ''
  return request(`${endpoint}${query}`, { method: 'GET' })
}

export function post(endpoint, data) {
  return request(endpoint, {
    method: 'POST',
    body: JSON.stringify(data),
  })
}

export function put(endpoint, data) {
  return request(endpoint, {
    method: 'PUT',
    body: JSON.stringify(data),
  })
}

export function del(endpoint) {
  return request(endpoint, { method: 'DELETE' })
}

export { ApiError, DEMO_MODE }
