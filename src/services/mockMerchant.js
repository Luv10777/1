/**
 * Mock商家服务
 */

// 模拟商家数据库
const mockMerchants = new Map([
  ['merchant_1', {
    id: 'merchant_1',
    tenantId: 'tenant_1',
    code: 'M1724577600000',
    name: '青岚茶事',
    industry: '餐饮',
    logoUrl: null,
    contactName: '林知夏',
    contactPhone: '13800138000',
    contactEmail: 'contact@qinglan.com',
    completeness: 80,
    status: 'ACTIVE',
    createdAt: '2024-08-20T10:00:00Z',
    updatedAt: '2024-08-25T10:00:00Z'
  }],
  ['merchant_2', {
    id: 'merchant_2',
    tenantId: 'tenant_1',
    code: 'M1724577601000',
    name: '山止咖啡',
    industry: '餐饮',
    logoUrl: null,
    contactName: '陈晨',
    contactPhone: '13900139000',
    contactEmail: null,
    completeness: 60,
    status: 'ACTIVE',
    createdAt: '2024-08-22T10:00:00Z',
    updatedAt: '2024-08-25T10:00:00Z'
  }]
])

/**
 * 获取商家列表
 */
export async function mockGetMerchantList(params = {}) {
  await delay(300)

  const { page = 0, size = 20 } = params
  const merchants = Array.from(mockMerchants.values())
    .filter(m => m.status !== 'DELETED')

  const start = page * size
  const end = start + size
  const items = merchants.slice(start, end)

  return {
    success: true,
    data: {
      items,
      total: merchants.length,
      page,
      size
    }
  }
}

/**
 * 获取商家详情
 */
export async function mockGetMerchant(id) {
  await delay(200)

  const merchant = mockMerchants.get(id)
  if (!merchant || merchant.status === 'DELETED') {
    throw new Error('商家不存在')
  }

  return {
    success: true,
    data: merchant
  }
}

/**
 * 创建商家
 */
export async function mockCreateMerchant(data) {
  await delay(500)

  const id = `merchant_${Date.now()}`
  const merchant = {
    id,
    tenantId: 'tenant_1',
    code: `M${Date.now()}`,
    name: data.name,
    industry: data.industry || null,
    logoUrl: data.logoUrl || null,
    contactName: data.contactName || null,
    contactPhone: data.contactPhone || null,
    contactEmail: data.contactEmail || null,
    completeness: calculateCompleteness(data),
    status: 'ACTIVE',
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  }

  mockMerchants.set(id, merchant)

  return {
    success: true,
    data: merchant,
    message: '商家创建成功'
  }
}

/**
 * 更新商家
 */
export async function mockUpdateMerchant(id, data) {
  await delay(400)

  const merchant = mockMerchants.get(id)
  if (!merchant || merchant.status === 'DELETED') {
    throw new Error('商家不存在')
  }

  Object.assign(merchant, {
    name: data.name,
    industry: data.industry || merchant.industry,
    logoUrl: data.logoUrl || merchant.logoUrl,
    contactName: data.contactName || merchant.contactName,
    contactPhone: data.contactPhone || merchant.contactPhone,
    contactEmail: data.contactEmail || merchant.contactEmail,
    completeness: calculateCompleteness(data),
    updatedAt: new Date().toISOString()
  })

  return {
    success: true,
    data: merchant,
    message: '商家更新成功'
  }
}

/**
 * 删除商家
 */
export async function mockDeleteMerchant(id) {
  await delay(300)

  const merchant = mockMerchants.get(id)
  if (!merchant || merchant.status === 'DELETED') {
    throw new Error('商家不存在')
  }

  merchant.status = 'DELETED'
  merchant.deletedAt = new Date().toISOString()

  return {
    success: true,
    message: '商家删除成功'
  }
}

/**
 * 启停商家
 */
export async function mockToggleMerchantStatus(id, enabled) {
  await delay(300)

  const merchant = mockMerchants.get(id)
  if (!merchant || merchant.status === 'DELETED') {
    throw new Error('商家不存在')
  }

  merchant.status = enabled ? 'ACTIVE' : 'SUSPENDED'
  merchant.updatedAt = new Date().toISOString()

  return {
    success: true,
    message: '商家状态更新成功'
  }
}

function calculateCompleteness(data) {
  let score = 0
  if (data.name) score += 20
  if (data.industry) score += 20
  if (data.logoUrl) score += 20
  if (data.contactName) score += 20
  if (data.contactPhone) score += 20
  return score
}

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}
