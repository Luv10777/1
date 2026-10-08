import { request as defaultRequest } from '../utils/request.js'

const faqFromApi = faq => ({ ...faq, status: String(faq.status || 'ACTIVE').toLowerCase() })

export function productPayload(values, update = false) {
  const payload = {
    name: values.name,
    type: String(values.type).toUpperCase(),
    category: values.category,
    price: Number(values.price),
    saleUnit: values.unit,
    specification: values.specifications || '',
    promotionRule: values.promotion || '',
    coreSellingPoints: values.points || '',
    images: (values.images || []).map((image, sortOrder) => {
      if (!image?.assetId) throw new Error('商品图片尚未上传完成，请重新上传。')
      return { assetId: image.assetId, sortOrder }
    }),
    faqs: (values.faqs || []).map((faq, sortOrder) => ({ question: faq.question, answer: faq.answer, sortOrder })),
  }
  if (update) payload.version = values.version
  return payload
}

async function mapConcurrent(items, mapper) {
  const result = new Array(items.length)
  let cursor = 0
  await Promise.all(Array.from({ length: Math.min(6, items.length) }, async () => {
    while (cursor < items.length) {
      const index = cursor++
      result[index] = await mapper(items[index])
    }
  }))
  return result
}

export function createProductApi({ request = defaultRequest, fetch: uploadFetch = (...args) => globalThis.fetch(...args) } = {}) {
  const read = endpoint => request(endpoint, { method: 'GET' })
  const write = (endpoint, method, value) => request(endpoint, { method, body: JSON.stringify(value) })
  const remove = endpoint => request(endpoint, { method: 'DELETE' })
  const imageUrl = async assetId => (await read(`/api/assets/${assetId}/download-url`)).downloadUrl

  async function fromApi(product) {
    return {
      ...product,
      type: String(product.type).toLowerCase(),
      unit: product.saleUnit || '',
      specifications: product.specification || '',
      promotion: product.promotionRule || '',
      points: product.coreSellingPoints || '',
      faqs: (product.faqs || []).map(faqFromApi),
      images: await Promise.all((product.images || []).map(async image => {
        // A preview failure must not report a committed product save as failed.
        try { return { ...image, url: await imageUrl(image.assetId) } }
        catch { return { ...image, url: '', previewUnavailable: true } }
      })),
    }
  }

  const api = {
    async listAll(storeId) {
      if (!storeId) return []
      const records = []
      let page = 0
      let totalPages = 1
      while (page < totalPages) {
        const result = await read(`/api/stores/${storeId}/products?page=${page}&size=100`)
        records.push(...result.items)
        totalPages = result.totalPages
        page++
      }
      // List responses deliberately omit FAQs; details preserve them on edit and in live setup.
      return mapConcurrent(records, product => api.get(product.id))
    },
    async get(id) { return fromApi(await read(`/api/products/${id}`)) },
    async create(storeId, values) {
      return fromApi(await write(`/api/stores/${storeId}/products`, 'POST', productPayload(values)))
    },
    async update(id, values) {
      return fromApi(await write(`/api/products/${id}`, 'PATCH', productPayload(values, true)))
    },
    delete: id => remove(`/api/products/${id}`),
    async createFaq(productId, values) {
      return faqFromApi(await write(`/api/products/${productId}/faqs`, 'POST', values))
    },
    async updateFaq(productId, faqId, values) {
      return faqFromApi(await write(`/api/products/${productId}/faqs/${faqId}`, 'PATCH', values))
    },
    deleteFaq: (productId, faqId) => remove(`/api/products/${productId}/faqs/${faqId}`),
    imageUrl,
    async uploadImage(file) {
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
      return { assetId: ticket.assetId, url: await imageUrl(ticket.assetId) }
    },
  }
  return api
}

export const productApi = createProductApi()
