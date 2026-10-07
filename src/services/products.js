export const productTypes = [
  { value: 'physical', label: '实物商品' },
  { value: 'voucher', label: '团购卡券' },
]

export const productCategories = ['餐饮美食', '丽人美业', '休闲娱乐', '食品生鲜', '日用百货', '生活服务', '其他']

export function emptyProduct() {
  return { name: '', type: 'physical', category: '', price: '', unit: '', specifications: '', promotion: '', points: '', images: [], faqs: [] }
}

// An explicit empty gallery means the legacy image was removed.
export function getProductImages(product) {
  if (Array.isArray(product?.images)) return product.images.filter(image =>
    typeof image === 'string' ? image.trim() : image && typeof image === 'object' && image.assetId,
  ).map(image => typeof image === 'object' ? { ...image } : image)
  return typeof product?.image === 'string' && product.image ? [product.image] : []
}

export function getProductFaqs(product) {
  if (!Array.isArray(product?.faqs)) return []
  return product.faqs
    .filter(pair => pair && typeof pair.question === 'string' && typeof pair.answer === 'string')
    .map((pair, index) => ({ id: pair.id || `${product.id || 'product'}-faq-${index}`, question: pair.question.trim(), answer: pair.answer.trim(), status: pair.status || 'active' }))
    .filter(pair => pair.question && pair.answer && pair.status === 'active')
}

export function validateProduct(product) {
  const errors = {}
  for (const [key, label] of [['name', '商品名称'], ['category', '分类'], ['unit', '售卖单位'], ['points', '核心卖点']]) {
    if (!String(product[key] ?? '').trim()) errors[key] = `请填写${label}`
  }
  if (errors.category) errors.category = '请选择分类'
  if (!productTypes.some(type => type.value === product.type)) errors.type = '请选择商品类型'
  if (!/^\d+(\.\d{1,2})?$/.test(String(product.price)) || !Number.isFinite(Number(product.price))) {
    errors.price = '请填写大于等于 0 的售价，最多两位小数'
  }
  return errors
}

export function normalizeProduct(product) {
  return Object.fromEntries(Object.keys(emptyProduct()).map(key => [key,
    key === 'images' ? getProductImages(product) : key === 'faqs' ? (Array.isArray(product.faqs) ? product.faqs.map(pair => ({ id: pair.id || `faq-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`, question: String(pair.question || '').trim(), answer: String(pair.answer || '').trim(), status: pair.status || 'active' })).filter(pair => pair.question && pair.answer) : []) : key === 'price' ? Number(product[key]) : String(product[key] ?? '').trim(),
  ]))
}

export function filterProducts(products, type, query) {
  const terms = query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  return products.filter(product => (type === 'all' || product.type === type) && terms.every(term =>
    `${product.name} ${product.category} ${product.price} ${Number(product.price).toFixed(2)} / ${product.unit}`.toLocaleLowerCase().includes(term),
  ))
}

export function productStorageKey(account, store) {
  return `wuyao-products-v1:${encodeURIComponent(account)}:${encodeURIComponent(store)}`
}

function sampleProducts() {
  return [
    { id: 'sample-sesame', name: '手工现磨芝麻丸', type: 'physical', category: '食品', price: 69, unit: '罐', specifications: '120g / 罐', promotion: '两罐包邮', points: '现磨现做，芝麻浓香，小罐装方便随身携带。', faqs: [{ id: 'sample-sesame-faq-1', question: '保质期多久？怎么保存？', answer: '常温密封保质期 90 天，开封后建议置于阴凉干燥处。', status: 'active' }, { id: 'sample-sesame-faq-2', question: '糖尿病人可以吃吗？', answer: '配方采用 0 蔗糖古法熬制，具体食用建议结合个人情况咨询专业人士。', status: 'active' }] },
    { id: 'sample-pot', name: '车间直发不锈钢锅', type: 'physical', category: '厨具', price: 199, unit: '口', specifications: '直径 28cm，含锅盖', promotion: '单口包邮', points: '不锈钢锅身，受热均匀，适合家庭日常烹饪。' },
    { id: 'sample-tea', name: '门店现调茶饮券', type: 'voucher', category: '到店核销', price: 19.9, unit: '张', specifications: '指定茶饮任选一杯', promotion: '有效期 30 天，到店核销使用，不与其他优惠同享', points: '到店现点现做，可选冷饮或热饮，一张券轻松享用。' },
  ].map(product => ({ ...product, sample: true, updatedAt: '2026-09-27T00:00:00.000Z' }))
}

export function loadProducts(storage, key) {
  const raw = storage.getItem(key)
  if (raw === null) return sampleProducts()
  const products = JSON.parse(raw)
  if (!Array.isArray(products) || products.some(product => !product || typeof product.id !== 'string' ||
      Object.keys(validateProduct(product)).length || !Number.isFinite(Date.parse(product.updatedAt)))) {
    throw new Error('商品数据格式无效')
  }
  return products
}

export function saveProducts(storage, key, products) {
  storage.setItem(key, JSON.stringify(products))
}

export function saveProductFaq(storage, key, productId, pair) {
  const products = loadProducts(storage, key)
  const product = products.find(item => item.id === productId)
  if (!product) throw new Error('该商品尚未保存到商品库，请先在商品库创建商品后再同步问答。')
  const faq = { id: pair.id, question: pair.question.trim(), answer: pair.answer.trim(), status: 'active' }
  const faqs = [...(product.faqs || [])]
  const index = faqs.findIndex(item => item.id === faq.id)
  if (index >= 0) faqs.splice(index, 1, faq)
  else faqs.push(faq)
  const updated = { ...product, faqs, updatedAt: new Date().toISOString() }
  saveProducts(storage, key, products.map(item => item.id === productId ? updated : item))
  return updated
}
