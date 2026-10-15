import { request as defaultRequest } from '../utils/request.js'

const CODE = /^\d{6}$/

export function namePayload(value) {
  const name = String(value ?? '').trim()
  if (!name) throw new Error('请填写姓名')
  if (name.length > 80) throw new Error('姓名最多 80 个字')
  return { name }
}

/**
 * 设置或修改密码。byCode 为真时用短信验证码，否则用当前密码；两者只发一样。
 * 服务端只看密码前 72 个字节，所以按字节数再拦一次，免得后半截其实没用上。
 */
export function passwordPayload(values, byCode) {
  const newPassword = String(values.newPassword ?? '')
  if (newPassword.length < 8 || newPassword.length > 64) throw new Error('新密码需为 8–64 位')
  if (new TextEncoder().encode(newPassword).length > 72) throw new Error('新密码太长，请少用一些中文或符号')
  if (newPassword !== values.confirmPassword) throw new Error('两次输入的新密码不一致')
  if (byCode) {
    const code = String(values.code ?? '').trim()
    if (!CODE.test(code)) throw new Error('请输入 6 位验证码')
    return { code, newPassword }
  }
  const currentPassword = String(values.currentPassword ?? '')
  if (!currentPassword) throw new Error('请输入当前密码')
  return { currentPassword, newPassword }
}

export function createAccountApi({ request = defaultRequest } = {}) {
  const put = (endpoint, body) => request(endpoint, { method: 'PUT', body: JSON.stringify(body) })
  return {
    profile: () => request('/api/auth/account', { method: 'GET' }),
    rename: name => put('/api/auth/account/name', namePayload(name)),
    /** 成功后返回新的一套令牌：其他设备的登录已失效，这台要换上新的才能继续用。 */
    changePassword: (values, byCode) => put('/api/auth/account/password', passwordPayload(values, byCode)),
  }
}

export const accountApi = createAccountApi()
