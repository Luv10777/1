/**
 * Mock品牌服务
 */

// 模拟品牌数据库
const mockBrands = new Map([
  ['brand_1', {
    id: 'brand_1',
    tenantId: 'tenant_1',
    merchantId: 'merchant_1',
    code: 'B1724577600000',
    name: '青岚茶事品牌规范',
    industry: '餐饮 / 茶饮',
    slogan: '一杯好茶，见天地',
    intro: '新中式茶饮品牌，传承东方美学，融入现代生活方式。',
    website: 'https://qinglan.example.com',
    viGuidelines: '主色：青黛绿；字体：思源黑体；留白充足，避免高饱和装饰。',
    history: '2021 年成立\n2023 年推出首家线下店',
    coreTeam: '林知夏｜创始人\n周予安｜品牌设计',
    brandStory: '从一盏茶开始，让忙碌的人重新感知四季。',
    culture: '真诚、克制、长期主义',
    positioning: '新中式茶饮品牌，传承东方美学，融入现代生活方式',
    targetAudience: '25-40岁都市白领，追求品质生活，注重健康养生',
    languageStyle: '温和、雅致、有文化底蕴。避免过于口语化，保持品牌调性',
    primaryColor: '#2D5016',
    logoAssets: JSON.stringify({
      primary: '/assets/logos/qinglan-primary.svg',
      white: '/assets/logos/qinglan-white.svg',
      black: '/assets/logos/qinglan-black.svg'
    }),
    platformStyles: JSON.stringify({
      xiaohongshu: '生活分享式，强调场景和氛围',
      douyin: '轻松活泼，结合热点话题',
      wechat: '专业严谨，强调产品品质'
    }),
    version: 1,
    status: 'ACTIVE',
    createdAt: '2024-08-20T10:00:00Z',
    updatedAt: '2024-08-25T10:00:00Z'
  }]
])

export async function mockGetBrandList(merchantId, params = {}) {
  await delay(300)

  const { page = 0, size = 20 } = params
  const brands = Array.from(mockBrands.values())
    .filter(b => b.merchantId === merchantId && b.status !== 'DELETED')

  const start = page * size
  const end = start + size
  const items = brands.slice(start, end)

  return {
    success: true,
    data: {
      items,
      total: brands.length,
      page,
      size
    }
  }
}

export async function mockGetBrand(id) {
  await delay(200)

  const brand = mockBrands.get(id)
  if (!brand || brand.status === 'DELETED') {
    throw new Error('品牌不存在')
  }

  return {
    success: true,
    data: brand
  }
}

export async function mockCreateBrand(merchantId, data) {
  await delay(500)

  const id = `brand_${Date.now()}`
  const brand = {
    id,
    tenantId: 'tenant_1',
    merchantId,
    code: `B${Date.now()}`,
    name: data.name,
    positioning: data.positioning || null,
    targetAudience: data.targetAudience || null,
    languageStyle: data.languageStyle || null,
    industry: data.industry || null,
    slogan: data.slogan || null,
    intro: data.intro || null,
    website: data.website || null,
    viGuidelines: data.viGuidelines || null,
    miniProgramQr: data.miniProgramQr || null,
    wechatQr: data.wechatQr || null,
    history: data.history || null,
    coreTeam: data.coreTeam || null,
    brandStory: data.brandStory || null,
    culture: data.culture || null,
    primaryColor: data.primaryColor || null,
    logoAssets: data.logoAssets || null,
    platformStyles: data.platformStyles || null,
    version: 1,
    status: 'ACTIVE',
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  }

  mockBrands.set(id, brand)

  return {
    success: true,
    data: brand,
    message: '品牌创建成功'
  }
}

export async function mockUpdateBrand(id, data) {
  await delay(400)

  const brand = mockBrands.get(id)
  if (!brand || brand.status === 'DELETED') {
    throw new Error('品牌不存在')
  }

  Object.assign(brand, {
    name: data.name,
    positioning: data.positioning || brand.positioning,
    targetAudience: data.targetAudience || brand.targetAudience,
    languageStyle: data.languageStyle || brand.languageStyle,
    industry: data.industry || brand.industry,
    slogan: data.slogan || brand.slogan,
    intro: data.intro || brand.intro,
    website: data.website || brand.website,
    viGuidelines: data.viGuidelines || brand.viGuidelines,
    miniProgramQr: data.miniProgramQr || brand.miniProgramQr,
    wechatQr: data.wechatQr || brand.wechatQr,
    history: data.history || brand.history,
    coreTeam: data.coreTeam || brand.coreTeam,
    brandStory: data.brandStory || brand.brandStory,
    culture: data.culture || brand.culture,
    primaryColor: data.primaryColor || brand.primaryColor,
    logoAssets: data.logoAssets || brand.logoAssets,
    platformStyles: data.platformStyles || brand.platformStyles,
    version: brand.version + 1,
    updatedAt: new Date().toISOString()
  })

  return {
    success: true,
    data: brand,
    message: '品牌更新成功'
  }
}

export async function mockDeleteBrand(id) {
  await delay(300)

  const brand = mockBrands.get(id)
  if (!brand || brand.status === 'DELETED') {
    throw new Error('品牌不存在')
  }

  brand.status = 'DELETED'
  brand.deletedAt = new Date().toISOString()

  return {
    success: true,
    message: '品牌删除成功'
  }
}

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}
