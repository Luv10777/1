/**
 * Mock认证服务
 * 模拟后端API响应
 */

// 模拟用户数据库
const mockUsers = new Map()
const mockVerificationCodes = new Map()

// 模拟Token存储
const tokenStorage = {
  accessToken: null,
  refreshToken: null
}

/**
 * 模拟发送验证码
 */
export async function mockSendCode(phone) {
  // 模拟网络延迟
  await delay(500)

  // 生成6位验证码
  const code = Math.floor(100000 + Math.random() * 900000).toString()

  // 存储验证码（5分钟过期）
  mockVerificationCodes.set(phone, {
    code,
    expiresAt: Date.now() + 5 * 60 * 1000
  })

  console.log('=================================================')
  console.log('【Mock环境】验证码已生成')
  console.log('手机号:', phone)
  console.log('验证码:', code)
  console.log('有效期: 5分钟')
  console.log('=================================================')

  return {
    success: true,
    message: '验证码已发送'
  }
}

/**
 * 模拟登录
 */
export async function mockLogin(phone, code) {
  await delay(800)

  // 验证验证码
  const stored = mockVerificationCodes.get(phone)
  if (!stored) {
    throw new Error('验证码错误或已过期')
  }

  if (stored.expiresAt < Date.now()) {
    mockVerificationCodes.delete(phone)
    throw new Error('验证码已过期')
  }

  if (stored.code !== code) {
    throw new Error('验证码错误')
  }

  // 验证成功，删除验证码
  mockVerificationCodes.delete(phone)

  // 查找或创建用户
  let user = mockUsers.get(phone)
  if (!user) {
    user = {
      id: `user_${Date.now()}`,
      phone,
      name: `用户${phone.slice(-4)}`,
      avatarUrl: null,
      tenantId: `tenant_${Date.now()}`,
      tenantName: '个人租户',
      createdAt: new Date().toISOString()
    }
    mockUsers.set(phone, user)
  }

  // 生成Token
  tokenStorage.accessToken = `mock_access_${Date.now()}_${Math.random().toString(36).slice(2)}`
  tokenStorage.refreshToken = `mock_refresh_${Date.now()}_${Math.random().toString(36).slice(2)}`

  return {
    success: true,
    data: {
      accessToken: tokenStorage.accessToken,
      refreshToken: tokenStorage.refreshToken,
      expiresIn: 3600,
      user: {
        id: user.id,
        phone: user.phone,
        name: user.name,
        avatarUrl: user.avatarUrl
      }
    }
  }
}

/**
 * 模拟刷新Token
 */
export async function mockRefreshToken(refreshToken) {
  await delay(300)

  if (refreshToken !== tokenStorage.refreshToken) {
    throw new Error('Refresh Token无效')
  }

  // 生成新Token
  tokenStorage.accessToken = `mock_access_${Date.now()}_${Math.random().toString(36).slice(2)}`
  tokenStorage.refreshToken = `mock_refresh_${Date.now()}_${Math.random().toString(36).slice(2)}`

  return {
    success: true,
    data: {
      accessToken: tokenStorage.accessToken,
      refreshToken: tokenStorage.refreshToken,
      expiresIn: 3600
    }
  }
}

/**
 * 模拟登出
 */
export async function mockLogout() {
  await delay(200)
  tokenStorage.accessToken = null
  tokenStorage.refreshToken = null
  return { success: true }
}

/**
 * 模拟获取当前用户信息
 */
export async function mockGetCurrentUser(token) {
  await delay(300)

  if (token !== tokenStorage.accessToken) {
    throw new Error('Token无效')
  }

  // 返回第一个用户（演示用）
  const user = Array.from(mockUsers.values())[0]
  if (!user) {
    throw new Error('用户不存在')
  }

  return {
    success: true,
    data: user
  }
}

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}
