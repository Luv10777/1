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

/** 确认注销。绑了手机号的用验证码，没绑的用当前密码；还要本人勾选"已了解后果"。 */
export function closurePayload(values, byCode) {
  if (!values.confirmed) throw new Error('请先勾选确认已了解注销的后果')
  if (byCode) {
    const code = String(values.code ?? '').trim()
    if (!CODE.test(code)) throw new Error('请输入 6 位验证码')
    return { code, confirmed: true }
  }
  const currentPassword = String(values.currentPassword ?? '')
  if (!currentPassword) throw new Error('请输入当前密码')
  return { currentPassword, confirmed: true }
}

/** 改绑或绑定手机号第一步：原手机号的验证码，或者当前密码，两者只发一样。 */
export function phoneProofPayload(values, byCode) {
  if (byCode) {
    const code = String(values.code ?? '').trim()
    if (!CODE.test(code)) throw new Error('请输入 6 位验证码')
    return { code }
  }
  const currentPassword = String(values.currentPassword ?? '')
  if (!currentPassword) throw new Error('请输入当前密码')
  return { currentPassword }
}

/** 第二步：新手机号和发到新手机号的验证码。 */
export function phoneChangePayload(values, currentPhone) {
  const phone = String(values.phone ?? '').trim()
  if (!/^1[3-9]\d{9}$/.test(phone)) throw new Error('请输入有效的 11 位手机号')
  if (phone === currentPhone) throw new Error('新手机号和现在绑定的一样')
  const code = String(values.code ?? '').trim()
  if (!CODE.test(code)) throw new Error('请输入 6 位验证码')
  return { phone, code }
}

/**
 * 把服务端给的微信授权地址改成可以嵌进弹窗的二维码页（微信官方 JS 登录用的参数）。
 * 扫码确认后由微信让整个页面跳到回调地址。不是微信的地址一律不嵌。
 */
export function wechatQrFrameUrl(url) {
  let parsed
  try { parsed = new URL(url) } catch { return null }
  if (parsed.origin !== 'https://open.weixin.qq.com' || parsed.pathname !== '/connect/qrconnect') return null
  parsed.hash = ''
  parsed.searchParams.set('login_type', 'jssdk')
  parsed.searchParams.set('self_redirect', 'false')
  return parsed.href
}

export function validateAvatar(file) {
  if (!file || !['image/jpeg', 'image/png'].includes(file.type)) throw new Error('请选择 JPG 或 PNG 格式的头像')
  if (!file.size || file.size > 2 * 1024 * 1024) throw new Error('头像图片需大于 0 且不能超过 2 MB')
}

export function createAccountApi({ request = defaultRequest, fetch: uploadFetch = globalThis.fetch } = {}) {
  const put = (endpoint, body) => request(endpoint, { method: 'PUT', body: JSON.stringify(body) })
  return {
    profile: () => request('/api/auth/account', { method: 'GET' }),
    rename: name => put('/api/auth/account/name', namePayload(name)),
    async updateAvatar(file) {
      validateAvatar(file)
      const ticket = await request('/api/auth/account/avatar/upload-url', {
        method: 'POST', body: JSON.stringify({ mimeType: file.type }),
      })
      let response
      try {
        response = await uploadFetch(ticket.uploadUrl, { method: 'PUT', headers: { 'Content-Type': file.type }, body: file })
      } catch {
        throw new Error('头像上传失败，请检查网络后重试')
      }
      if (!response.ok) throw new Error('头像上传失败，请重试')
      return put('/api/auth/account/avatar', { uploadKey: ticket.uploadKey })
    },
    /** 成功后返回新的一套令牌：其他设备的登录已失效，这台要换上新的才能继续用。 */
    changePassword: (values, byCode) => put('/api/auth/account/password', passwordPayload(values, byCode)),
    /** 改绑手机号前确认是本人；通过后十分钟内可以提交新手机号。 */
    verifyPhoneChange: (values, byCode) => request('/api/auth/account/phone/verify',
      { method: 'POST', body: JSON.stringify(phoneProofPayload(values, byCode)) }),
    /** 换成新手机号，返回更新后的资料。已登录的设备不受影响。 */
    changePhone: (values, currentPhone) => put('/api/auth/account/phone', phoneChangePayload(values, currentPhone)),
    wechat: () => request('/api/auth/account/wechat', { method: 'GET' }),
    /** 返回微信授权地址；服务端同时写入一个只在回调时核对的 cookie。 */
    bindWechat: () => request('/api/auth/account/wechat/bind', { method: 'POST' }),
    /** 注销前的说明：只注销自己还是连商户一起关闭、会作废什么、现在能不能注销。 */
    closurePreview: () => request('/api/auth/account/closure', { method: 'GET' }),
    /** 立即生效、不能恢复；成功后这台设备的令牌也已失效，调用方要清掉本地登录。 */
    close: (values, byCode) => request('/api/auth/account/closure',
      { method: 'POST', body: JSON.stringify(closurePayload(values, byCode)) }),
  }
}

export const accountApi = createAccountApi()
