import test from 'node:test'
import assert from 'node:assert/strict'
import { createMockObjectStorage, createTextOverlaySpec, runVisualQa } from './media.js'

test('object storage keeps tenant-scoped private assets', async () => {
  const storage = createMockObjectStorage()
  const asset = await storage.put({ tenantId: 'tenant_a', assetId: 'asset_1', contentType: 'image/webp', size: 12 })
  assert.equal(asset.privateUrl, '/mock-assets/asset_1')
  assert.equal((await storage.get({ tenantId: 'tenant_a', assetId: 'asset_1' })).key, 'tenant_a/asset_1')
  await assert.rejects(() => storage.get({ tenantId: 'tenant_b', assetId: 'asset_1' }), /OBJECT_NOT_FOUND/)
})

test('visual QA blocks invalid ratio and missing product reference', () => {
  const report = runVisualQa({ assetId: 'asset_1', expectedRatio: '3:4', actualRatio: '16:9', hasProductReference: false })
  assert.equal(report.status, 'BLOCKED')
  assert.equal(report.blocking, true)
  assert.equal(report.issues.length, 2)
})

test('text is rendered by a deterministic overlay layer', () => {
  const spec = createTextOverlaySpec({ text: '29.9 元双人下午茶' })
  assert.equal(spec.renderMode, 'TEMPLATE_LAYER')
  assert.equal(spec.modelMustNotRenderText, true)
})
