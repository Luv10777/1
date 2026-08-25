export const MEDIA_QA_CODES = Object.freeze({
  PRODUCT_MISMATCH: 'PRODUCT_MISMATCH',
  TEXT_OVERFLOW: 'TEXT_OVERFLOW',
  INVALID_RATIO: 'INVALID_RATIO',
  MISSING_ASSET: 'MISSING_ASSET',
  SAFE: 'SAFE',
})

export function createMockObjectStorage({ baseUrl = '/mock-assets' } = {}) {
  const objects = new Map()
  return {
    async put(input) {
      if (!input?.assetId || !input?.contentType) throw new Error('OBJECT_METADATA_REQUIRED')
      const key = `${input.tenantId || 'tenant_demo_001'}/${input.assetId}`
      const object = { key, assetId: input.assetId, contentType: input.contentType, size: input.size || 0, checksum: input.checksum || `mock_${input.assetId}`, privateUrl: `${baseUrl}/${input.assetId}`, createdAt: new Date().toISOString() }
      objects.set(key, object)
      return object
    },
    async get(input) {
      const object = objects.get(`${input.tenantId || 'tenant_demo_001'}/${input.assetId}`)
      if (!object) throw new Error('OBJECT_NOT_FOUND')
      return object
    },
    list() { return [...objects.values()] },
  }
}

export function runVisualQa({ assetId, expectedRatio = '3:4', actualRatio = expectedRatio, hasProductReference = true, overlayText = '' } = {}) {
  const issues = []
  if (!assetId) issues.push({ code: MEDIA_QA_CODES.MISSING_ASSET, severity: 'BLOCKING', message: '生成结果没有可持久化的资产 ID' })
  if (actualRatio !== expectedRatio) issues.push({ code: MEDIA_QA_CODES.INVALID_RATIO, severity: 'BLOCKING', message: `输出比例 ${actualRatio} 与渠道要求 ${expectedRatio} 不一致` })
  if (!hasProductReference) issues.push({ code: MEDIA_QA_CODES.PRODUCT_MISMATCH, severity: 'BLOCKING', message: '缺少商品参考素材，无法确认产品一致性' })
  if (overlayText.length > 80) issues.push({ code: MEDIA_QA_CODES.TEXT_OVERFLOW, severity: 'WARNING', message: '叠加文字可能超出安全区域' })
  return { version: 'visual-qa.v1', assetId: assetId || null, score: issues.some((issue) => issue.severity === 'BLOCKING') ? 0 : issues.length ? 0.82 : 0.98, issues, blocking: issues.some((issue) => issue.severity === 'BLOCKING'), status: issues.some((issue) => issue.severity === 'BLOCKING') ? 'BLOCKED' : 'PASSED', checkedAt: new Date().toISOString() }
}

export function createTextOverlaySpec({ text = '', ratio = '3:4', safeArea = 0.82 } = {}) {
  return { version: 'text-overlay.v1', text, ratio, safeArea, renderMode: 'TEMPLATE_LAYER', modelMustNotRenderText: true }
}
