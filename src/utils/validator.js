/**
 * 表单验证规则
 */

export const validators = {
  required: (message = '此字段为必填项') => ({
    validator: (value) => !isEmpty(value),
    message
  }),

  phone: (message = '请输入有效的手机号') => ({
    validator: (value) => /^1[3-9]\d{9}$/.test(value),
    message
  }),

  email: (message = '请输入有效的邮箱地址') => ({
    validator: (value) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value),
    message
  }),

  minLength: (min, message) => ({
    validator: (value) => String(value).length >= min,
    message: message || `最少需要 ${min} 个字符`
  }),

  maxLength: (max, message) => ({
    validator: (value) => String(value).length <= max,
    message: message || `最多允许 ${max} 个字符`
  }),

  pattern: (pattern, message = '格式不正确') => ({
    validator: (value) => pattern.test(value),
    message
  }),

  url: (message = '请输入有效的URL') => ({
    validator: (value) => {
      try {
        new URL(value)
        return true
      } catch {
        return false
      }
    },
    message
  })
}

function isEmpty(value) {
  if (value === null || value === undefined) return true
  if (typeof value === 'string') return value.trim() === ''
  if (Array.isArray(value)) return value.length === 0
  return false
}

/**
 * 验证字段
 * @param {any} value
 * @param {Array} rules
 * @returns {string|null}
 */
export function validateField(value, rules = []) {
  for (const rule of rules) {
    if (!rule.validator(value)) {
      return rule.message
    }
  }
  return null
}

/**
 * 验证表单
 * @param {Object} formData
 * @param {Object} rulesMap
 * @returns {Object}
 */
export function validateForm(formData, rulesMap) {
  const errors = {}

  for (const [field, rules] of Object.entries(rulesMap)) {
    const error = validateField(formData[field], rules)
    if (error) {
      errors[field] = error
    }
  }

  return {
    valid: Object.keys(errors).length === 0,
    errors
  }
}
