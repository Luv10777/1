import { request as defaultRequest } from '../utils/request.js'

/** 品牌档案里的文字字段，与后端 BrandDtos 一一对应。 */
export const BRAND_TEXT_FIELDS = ['name', 'industry', 'slogan', 'intro', 'website', 'positioning', 'targetAudience', 'languageStyle', 'viGuidelines', 'history', 'brandStory', 'coreTeam', 'culture']
/** 表单里的图片槽位 → 后端保存的素材 ID 字段。 */
export const BRAND_IMAGE_FIELDS = { logo: 'logoAssetId', miniProgramQr: 'miniProgramQrAssetId', wechatQr: 'wechatQrAssetId' }

// 后端按整份资料保存：留空的字段就是清空，所以每个字段都要带上。
export function brandPayload(values, update = false) {
  const payload = {}
  for (const field of BRAND_TEXT_FIELDS) payload[field] = String(values[field] ?? '').trim()
  payload.primaryColor = values.primaryColor || ''
  for (const [slot, field] of Object.entries(BRAND_IMAGE_FIELDS)) {
    const image = values[slot]
    if (image && !image.assetId) throw new Error('图片尚未上传完成，请重新上传。')
    payload[field] = image?.assetId ?? null
  }
  if (update) payload.version = values.version
  return payload
}

export function createBrandApi({ request = defaultRequest, fetch: uploadFetch = (...args) => globalThis.fetch(...args) } = {}) {
  const read = endpoint => request(endpoint, { method: 'GET' })
  const write = (endpoint, method, value) => request(endpoint, { method, ...(value === undefined ? {} : { body: JSON.stringify(value) }) })
  const imageUrl = async assetId => (await read(`/api/assets/${assetId}/download-url`)).downloadUrl

  async function image(assetId) {
    if (!assetId) return null
    // 预览地址取不到不代表品牌没保存，图片仍然记在档案里。
    try { return { assetId, url: await imageUrl(assetId) } }
    catch { return { assetId, url: '', previewUnavailable: true } }
  }

  async function fromApi(brand) {
    const [logo, miniProgramQr, wechatQr] = await Promise.all(Object.values(BRAND_IMAGE_FIELDS).map(field => image(brand[field])))
    return { ...brand, logo, miniProgramQr, wechatQr }
  }

  return {
    async list() { return Promise.all((await read('/api/brands')).map(fromApi)) },
    /** 只要名字和默认标记时用这个，不去取图片地址。 */
    names: async () => (await read('/api/brands')).map(({ id, name, defaultBrand }) => ({ id, name, defaultBrand })),
    async get(id) { return fromApi(await read(`/api/brands/${id}`)) },
    async create(values) { return fromApi(await write('/api/brands', 'POST', brandPayload(values))) },
    async update(id, values) { return fromApi(await write(`/api/brands/${id}`, 'PUT', brandPayload(values, true))) },
    async makeDefault(id) { return fromApi(await write(`/api/brands/${id}/default`, 'POST')) },
    remove: id => request(`/api/brands/${id}`, { method: 'DELETE' }),
    async uploadImage(file) {
      // 后端会真的解码图片，只认 PNG 和 JPEG。
      if (!['image/jpeg', 'image/png'].includes(file.type)) throw new Error('请选择 JPG 或 PNG 格式的图片。')
      if (file.size > 5 * 1024 * 1024) throw new Error('图片不能超过 5MB，请选择较小的图片。')
      const ticket = await write('/api/assets/upload-url', 'POST', { name: file.name, type: 'IMAGE', mimeType: file.type })
      let response
      try {
        response = await uploadFetch(ticket.uploadUrl, { method: 'PUT', headers: { 'Content-Type': file.type }, body: file })
      } catch {
        throw new Error('图片上传失败，请检查网络后重试。')
      }
      if (!response.ok) throw new Error('图片上传失败，请重新选择图片后重试。')
      await write(`/api/assets/${ticket.assetId}/confirm`, 'POST', { sizeBytes: file.size })
      return image(ticket.assetId)
    },
  }
}

export const brandApi = createBrandApi()
