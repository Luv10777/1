import test from 'node:test'
import assert from 'node:assert/strict'
import { brandPayload, createBrandApi } from './brandApi.js'

const saved = (overrides = {}) => ({
  id: 4, name: '青岚茶事', defaultBrand: true, status: 'ACTIVE', storeCount: 2, industry: '餐饮 / 茶饮', slogan: '一杯好茶',
  intro: null, website: null, positioning: null, targetAudience: null, languageStyle: '温和、雅致', primaryColor: '#2d5016',
  viGuidelines: null, logoAssetId: 11, miniProgramQrAssetId: null, wechatQrAssetId: 12, history: null, brandStory: null,
  coreTeam: null, culture: null, version: 3, ...overrides,
})

test('lists brands with a preview for each saved image and keeps a brand whose preview cannot be signed', async () => {
  const calls = []
  const api = createBrandApi({ request: async endpoint => {
    calls.push(endpoint)
    if (endpoint === '/api/brands') return [saved(), saved({ id: 5, name: '王记烧烤', defaultBrand: false, logoAssetId: 13, wechatQrAssetId: null })]
    if (endpoint === '/api/assets/13/download-url') throw Error('storage unavailable')
    return { downloadUrl: `https://assets.test${endpoint}` }
  } })
  const [tea, grill] = await api.list()
  assert.deepEqual(tea.logo, { assetId: 11, url: 'https://assets.test/api/assets/11/download-url' })
  assert.equal(tea.miniProgramQr, null)
  assert.equal(tea.wechatQr.assetId, 12)
  assert.equal(tea.storeCount, 2)
  assert.deepEqual(grill.logo, { assetId: 13, url: '', previewUnavailable: true })

  calls.length = 0
  assert.deepEqual(await api.names(), [{ id: 4, name: '青岚茶事', defaultBrand: true }, { id: 5, name: '王记烧烤', defaultBrand: false }])
  assert.deepEqual(calls, ['/api/brands'], 'a picker needs no image previews')
})

test('sends the whole profile so a cleared field is cleared, and never a signed URL in place of an asset', async () => {
  const api = createBrandApi({ request: async endpoint => endpoint.includes('/assets/') ? { downloadUrl: 'signed' } : saved() })
  const form = await api.get(4)
  form.slogan = '  '
  form.wechatQr = null
  const payload = brandPayload(form, true)
  assert.equal(payload.name, '青岚茶事')
  assert.equal(payload.slogan, '')
  assert.equal(payload.intro, '')
  assert.equal(payload.languageStyle, '温和、雅致')
  assert.equal(payload.primaryColor, '#2d5016')
  assert.equal(payload.logoAssetId, 11)
  assert.equal(payload.wechatQrAssetId, null)
  assert.equal(payload.version, 3)
  assert.equal('logo' in payload, false)
  assert.equal(brandPayload(form).version, undefined)
  assert.throws(() => brandPayload({ ...form, logo: { url: 'blob:local-preview' } }), /尚未上传/)
})

test('creates with POST, edits with PUT and the expected version, and uses dedicated calls for default and removal', async () => {
  const writes = []
  const api = createBrandApi({ request: async (endpoint, options) => {
    if (endpoint.includes('/assets/')) return { downloadUrl: 'signed' }
    writes.push({ endpoint, method: options.method, body: options.body && JSON.parse(options.body) })
    return saved()
  } })
  await api.create({ name: ' 青岚茶事 ', primaryColor: '#2D5016' })
  await api.update(4, { name: '青岚茶事', version: 3 })
  await api.makeDefault(4)
  await api.remove(4)
  assert.deepEqual(writes.map(write => `${write.method} ${write.endpoint}`),
    ['POST /api/brands', 'PUT /api/brands/4', 'POST /api/brands/4/default', 'DELETE /api/brands/4'])
  assert.equal(writes[0].body.name, '青岚茶事')
  assert.equal(writes[0].body.version, undefined)
  assert.equal(writes[1].body.version, 3)
  assert.equal(writes[2].body, undefined)
})

test('uploads the image bytes, confirms them, and only then offers the image to the form', async () => {
  const calls = []
  const file = { name: 'logo.png', type: 'image/png', size: 10 }
  const api = createBrandApi({
    request: async (endpoint, options) => {
      calls.push(endpoint)
      if (endpoint === '/api/assets/upload-url') {
        assert.deepEqual(JSON.parse(options.body), { name: 'logo.png', type: 'IMAGE', mimeType: 'image/png' })
        return { assetId: 11, uploadUrl: 'https://storage.test/upload' }
      }
      if (endpoint.endsWith('/confirm')) { assert.deepEqual(JSON.parse(options.body), { sizeBytes: 10 }); return { status: 'READY' } }
      return { downloadUrl: 'https://storage.test/download' }
    },
    fetch: async (url, options) => {
      calls.push(url)
      assert.equal(options.body, file)
      assert.equal(options.method, 'PUT')
      return { ok: true }
    },
  })
  assert.deepEqual(await api.uploadImage(file), { assetId: 11, url: 'https://storage.test/download' })
  assert.deepEqual(calls, ['/api/assets/upload-url', 'https://storage.test/upload', '/api/assets/11/confirm', '/api/assets/11/download-url'])

  const failing = createBrandApi({ request: async () => ({ assetId: 11, uploadUrl: 'u' }), fetch: async () => ({ ok: false }) })
  await assert.rejects(failing.uploadImage(file), /上传失败/)
  await assert.rejects(failing.uploadImage({ name: 'logo.svg', type: 'image/svg+xml', size: 1 }), /JPG 或 PNG/)
})
