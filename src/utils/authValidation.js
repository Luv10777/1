export function phoneError(phone, country = '+86') {
  if (country !== '+86') return '暂仅支持中国大陆手机号'
  return /^1[3-9]\d{9}$/.test(phone.trim()) ? '' : '请输入有效的 11 位手机号'
}

export function accountError(account) {
  const value = account.trim()
  if (!value) return '请输入用户名或手机号'
  if (/^\d+$/.test(value)) return phoneError(value)
  return value.length >= 2 && value.length <= 64 && !/\s/.test(value)
    ? '' : '用户名需为 2–64 个字符，且不包含空格'
}

export function passwordError(password) {
  return password.length >= 8 && password.length <= 64 ? '' : '密码长度需为 8–64 位'
}

export function codeError(code) {
  return /^\d{6}$/.test(code) ? '' : '请输入 6 位数字验证码'
}

export function safeRedirect(value) {
  return typeof value === 'string' && /^\/(?!\/)/.test(value)
    && !/[\\\s]/.test(value) && !/^\/login(?:[/?#]|$)/.test(value)
    ? value : '/dashboard'
}
