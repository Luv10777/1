<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Dialog, DialogPanel, DialogTitle, DialogDescription } from '@headlessui/vue'
import { Plus, Search, Package, Pencil, Trash2, X, ArrowUpRight } from 'lucide-vue-next'
import ProductFormModal from '../components/ProductFormModal.vue'
import ProductImage from '../components/ProductImage.vue'
import { selectedStore, selectedStoreId, storeLoading, storeError } from '../stores/merchantContext'
import { productTypes, filterProducts, getProductImages } from '../services/products'
import { productApi } from '../services/productApi'
import '../products.css'

const products = ref([])
const activeType = ref('all')
const search = ref('')
const formOpen = ref(false)
const formReadOnly = ref(false)
const editingProduct = ref(null)
const deletingProduct = ref(null)
const error = ref('')
const notice = ref('')
const loaded = ref(false)
const loading = ref(false)
const detailLoading = ref(false)
const saving = ref(false)
const deleting = ref(false)
let generation = 0
let detailRequest = 0
onBeforeUnmount(() => { generation++; detailRequest++ })

async function loadLibrary() {
  const current = ++generation
  detailRequest++
  const storeId = selectedStoreId.value
  formOpen.value = false
  deletingProduct.value = null
  editingProduct.value = null
  notice.value = ''
  error.value = ''
  search.value = ''
  activeType.value = 'all'
  products.value = []
  loaded.value = false
  loading.value = !!storeId
  detailLoading.value = false
  saving.value = false
  deleting.value = false
  if (!storeId) return
  try {
    const records = await productApi.listAll(storeId)
    if (current !== generation) return
    products.value = records
    loaded.value = true
  } catch (failure) {
    if (current === generation) error.value = failure.message || '商品数据暂时无法读取，请重试。'
  } finally {
    if (current === generation) loading.value = false
  }
}
watch(selectedStoreId, loadLibrary, { immediate: true })
const filters = computed(() => [{ value: 'all', label: '全部' }, ...productTypes].map(type => ({
  ...type, count: products.value.filter(product => type.value === 'all' || product.type === type.value).length,
})))
const visibleProducts = computed(() => filterProducts(products.value, activeType.value, search.value))
const typeLabel = type => productTypes.find(item => item.value === type)?.label
const formatDate = date => date && Number.isFinite(Date.parse(date)) ? new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(date)) : '—'
async function openForm(product = null, readOnly = false) {
  if (!selectedStoreId.value || saving.value || deleting.value) return
  const current = generation
  const request = ++detailRequest
  error.value = ''
  notice.value = ''
  detailLoading.value = !!product
  try {
    const detail = product ? await productApi.get(product.id) : null
    if (current !== generation || request !== detailRequest) return
    editingProduct.value = detail
    formReadOnly.value = readOnly
    formOpen.value = true
  } catch (failure) {
    if (current === generation && request === detailRequest) error.value = failure.message || '商品详情加载失败，请重试。'
  } finally {
    if (current === generation && request === detailRequest) detailLoading.value = false
  }
}
function closeForm() {
  if (saving.value) return
  formOpen.value = false
  error.value = ''
}
async function saveProduct(values) {
  if (saving.value || !selectedStoreId.value) return
  const current = generation
  const previous = editingProduct.value
  const storeId = selectedStoreId.value
  saving.value = true
  error.value = ''
  try {
    const product = previous
      ? await productApi.update(previous.id, { ...values, version: previous.version })
      : await productApi.create(storeId, values)
    if (current !== generation) return
    products.value = previous ? products.value.map(item => item.id === previous.id ? product : item) : [product, ...products.value]
    formOpen.value = false
    search.value = ''
    activeType.value = 'all'
    notice.value = previous ? '商品已更新' : '商品已创建'
  } catch (failure) {
    if (current === generation) error.value = failure.message || '保存失败，请重试。'
  } finally {
    if (current === generation) saving.value = false
  }
}
function closeDelete() {
  if (deleting.value) return
  deletingProduct.value = null
  error.value = ''
}
async function deleteProduct() {
  if (deleting.value || !deletingProduct.value) return
  const current = generation
  const productId = deletingProduct.value.id
  deleting.value = true
  error.value = ''
  try {
    await productApi.delete(productId)
    if (current !== generation) return
    products.value = products.value.filter(item => item.id !== productId)
    deletingProduct.value = null
    notice.value = '商品已删除'
  } catch (failure) {
    if (current === generation) error.value = failure.message || '删除失败，请重试。'
  } finally {
    if (current === generation) deleting.value = false
  }
}
</script>

<template>
  <section class="products-page">
    <header class="products-header">
      <div><p class="products-eyebrow">ASSET CENTER / PRODUCTS</p><h1>商品库</h1><p class="products-subtitle">沉淀门店爆款、团购券与实物资产，为 AI 直播讲解与内容创作提供商品事实库。</p></div>
      <button type="button" class="product-button product-button-primary" :disabled="!loaded || detailLoading || saving" @click="openForm()"><Plus :size="17" />新建商品</button>
    </header>

    <div class="products-toolbar">
      <div class="products-filters" role="group" aria-label="商品类型筛选">
        <button v-for="filter in filters" :key="filter.value" type="button" :class="{ active: activeType === filter.value }" :aria-pressed="activeType === filter.value" @click="activeType = filter.value">{{ filter.label }} <span>({{ filter.count }})</span></button>
      </div>
      <label class="products-search"><Search :size="16" /><input v-model="search" type="search" aria-label="搜索商品名称、分类、价格" placeholder="搜索商品名称、分类、价格"></label>
    </div>
    <div class="products-list-heading"><span>{{ selectedStore }}</span><span>共 {{ visibleProducts.length }} 件商品</span></div>
    <p v-if="notice" class="products-notice" role="status">{{ notice }}</p>
    <p v-if="error && !formOpen && !deletingProduct" class="product-error" role="alert">{{ error }} <button v-if="!loaded && selectedStoreId" type="button" class="product-button" @click="loadLibrary">重新加载</button></p>
    <p v-if="loading || storeLoading" class="products-notice" role="status">正在加载商品库…</p>
    <p v-else-if="!selectedStoreId" class="products-notice" role="status">{{ storeError || '请先在顶部创建或选择门店，再管理商品。' }}</p>
    <p v-if="detailLoading" class="products-notice" role="status">正在读取商品详情…</p>
    <div v-if="visibleProducts.length" class="products-grid">
      <article v-for="product in visibleProducts" :key="product.id" class="product-card" role="button" tabindex="0" :aria-label="`查看 ${product.name} 详情`" @click="openForm(product, true)" @keydown.enter.self="openForm(product, true)" @keydown.space.self.prevent="openForm(product, true)">
        <div class="product-card-top">
          <div class="product-card-image">
            <ProductImage :src="getProductImages(product)[0]" :name="product.name" />
            <span v-if="getProductImages(product).length > 1" class="product-image-count">{{ getProductImages(product).length }} 张</span>
          </div>
          <div class="product-card-tags"><span v-if="product.sample" class="product-sample">示例</span><span class="product-category">{{ product.category }}</span></div>
        </div>
        <p class="product-type-caption">{{ typeLabel(product.type) }}</p>
        <h2>{{ product.name }}</h2>
        <div class="product-price"><span class="product-currency">¥</span><strong>{{ product.price }}</strong><span>/ {{ product.unit }}</span></div>
        <p class="product-points">{{ product.points }}</p>
        <div class="product-card-footer">
          <time :datetime="product.updatedAt">更新于 {{ formatDate(product.updatedAt) }}</time>
          <div class="product-card-actions" @click.stop>
            <button type="button" :aria-label="`编辑 ${product.name}`" @click="openForm(product)"><Pencil :size="14" />编辑</button>
            <button type="button" :aria-label="`删除 ${product.name}`" @click="deletingProduct = product; error = ''; notice = ''"><Trash2 :size="14" />删除</button>
          </div>
        </div>
      </article>
    </div>
    <div v-else-if="loaded" class="products-empty">
      <Package :size="36" :stroke-width="1.2" />
      <h2>{{ products.length ? '没有找到匹配的商品' : '把第一件好物收入商品库' }}</h2>
      <p>{{ products.length ? '试试其他关键词，或查看全部商品。' : '录入商品信息与卖点，让每一次讲解都有据可循。' }}</p>
      <button v-if="products.length" type="button" class="product-button" @click="search = ''; activeType = 'all'">清除筛选<ArrowUpRight :size="15" /></button>
      <button v-else type="button" class="product-button product-button-primary" @click="openForm()"><Plus :size="16" />新建商品</button>
    </div>

    <ProductFormModal v-if="formOpen" :product="editingProduct" :read-only="formReadOnly" :error="error" :busy="saving" @save="saveProduct" @close="closeForm" />
    <Dialog :open="!!deletingProduct" class="product-dialog" @close="closeDelete">
      <div class="product-overlay" aria-hidden="true" />
      <div class="product-dialog-position">
        <DialogPanel class="product-modal product-delete-modal">
          <header class="product-modal-header"><DialogTitle>删除商品</DialogTitle><button type="button" class="product-icon-button" aria-label="关闭删除确认" :disabled="deleting" @click="closeDelete"><X :size="18" /></button></header>
          <DialogDescription class="product-delete-description">确定删除「{{ deletingProduct?.name }}」吗？删除后将无法恢复。</DialogDescription>
          <p v-if="error" class="product-error" role="alert">{{ error }}</p>
          <footer class="product-modal-footer"><button type="button" class="product-button" :disabled="deleting" @click="closeDelete">取消</button><button type="button" class="product-button product-button-primary" :disabled="deleting" @click="deleteProduct">{{ deleting ? '删除中…' : '确认删除' }}</button></footer>
        </DialogPanel>
      </div>
    </Dialog>
  </section>
</template>
