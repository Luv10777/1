/**
 * Mock门店服务
 */

// 模拟门店数据库
const mockStores = new Map([
  ['store_1', {
    id: 'store_1',
    tenantId: 'tenant_1',
    merchantId: 'merchant_1',
    code: 'S1724577600000',
    name: '杭州城西店',
    address: '西湖区文三路',
    city: '杭州',
    province: '浙江',
    latitude: 30.2741,
    longitude: 120.1551,
    contactPhone: '0571-88888888',
    businessHours: JSON.stringify({
      monday: '09:00-22:00',
      tuesday: '09:00-22:00',
      wednesday: '09:00-22:00',
      thursday: '09:00-22:00',
      friday: '09:00-22:00',
      saturday: '09:00-23:00',
      sunday: '09:00-23:00'
    }),
    status: 'ACTIVE',
    createdAt: '2024-08-20T10:00:00Z',
    updatedAt: '2024-08-25T10:00:00Z'
  }],
  ['store_2', {
    id: 'store_2',
    tenantId: 'tenant_1',
    merchantId: 'merchant_1',
    code: 'S1724577601000',
    name: '杭州湖滨店',
    address: '上城区湖滨路',
    city: '杭州',
    province: '浙江',
    latitude: 30.2597,
    longitude: 120.1619,
    contactPhone: '0571-87777777',
    businessHours: JSON.stringify({
      monday: '10:00-22:00',
      tuesday: '10:00-22:00',
      wednesday: '10:00-22:00',
      thursday: '10:00-22:00',
      friday: '10:00-22:00',
      saturday: '10:00-23:00',
      sunday: '10:00-23:00'
    }),
    status: 'ACTIVE',
    createdAt: '2024-08-22T10:00:00Z',
    updatedAt: '2024-08-25T10:00:00Z'
  }]
])

/**
 * 获取门店列表
 */
export async function mockGetStoreList(merchantId, params = {}) {
  await delay(300)

  const { page = 0, size = 20 } = params
  const stores = Array.from(mockStores.values())
    .filter(s => s.merchantId === merchantId && s.status !== 'DELETED')

  const start = page * size
  const end = start + size
  const items = stores.slice(start, end)

  return {
    success: true,
    data: {
      items,
      total: stores.length,
      page,
      size
    }
  }
}

/**
 * 获取门店详情
 */
export async function mockGetStore(id) {
  await delay(200)

  const store = mockStores.get(id)
  if (!store || store.status === 'DELETED') {
    throw new Error('门店不存在')
  }

  return {
    success: true,
    data: store
  }
}

/**
 * 创建门店
 */
export async function mockCreateStore(merchantId, data) {
  await delay(500)

  const id = `store_${Date.now()}`
  const store = {
    id,
    tenantId: 'tenant_1',
    merchantId,
    code: `S${Date.now()}`,
    name: data.name,
    address: data.address || null,
    city: data.city || null,
    province: data.province || null,
    latitude: data.latitude || null,
    longitude: data.longitude || null,
    contactPhone: data.contactPhone || null,
    businessHours: data.businessHours || null,
    status: 'ACTIVE',
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  }

  mockStores.set(id, store)

  return {
    success: true,
    data: store,
    message: '门店创建成功'
  }
}

/**
 * 更新门店
 */
export async function mockUpdateStore(id, data) {
  await delay(400)

  const store = mockStores.get(id)
  if (!store || store.status === 'DELETED') {
    throw new Error('门店不存在')
  }

  Object.assign(store, {
    name: data.name,
    address: data.address || store.address,
    city: data.city || store.city,
    province: data.province || store.province,
    latitude: data.latitude || store.latitude,
    longitude: data.longitude || store.longitude,
    contactPhone: data.contactPhone || store.contactPhone,
    businessHours: data.businessHours || store.businessHours,
    updatedAt: new Date().toISOString()
  })

  return {
    success: true,
    data: store,
    message: '门店更新成功'
  }
}

/**
 * 删除门店
 */
export async function mockDeleteStore(id) {
  await delay(300)

  const store = mockStores.get(id)
  if (!store || store.status === 'DELETED') {
    throw new Error('门店不存在')
  }

  store.status = 'DELETED'
  store.deletedAt = new Date().toISOString()

  return {
    success: true,
    message: '门店删除成功'
  }
}

/**
 * 启停门店
 */
export async function mockToggleStoreStatus(id, enabled) {
  await delay(300)

  const store = mockStores.get(id)
  if (!store || store.status === 'DELETED') {
    throw new Error('门店不存在')
  }

  store.status = enabled ? 'ACTIVE' : 'SUSPENDED'
  store.updatedAt = new Date().toISOString()

  return {
    success: true,
    message: '门店状态更新成功'
  }
}

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}
