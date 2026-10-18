<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, Minus, Plus, RefreshCw, ShoppingBag, Store } from 'lucide-vue-next'
import { commerceApi } from '../services/commerceApi'
import '../public-commerce.css'

const route = useRoute()
const router = useRouter()
const store = ref(null)
const products = ref([])
const quantities = reactive({})
const buyer = reactive({ name: '', phone: '', address: '', note: '', legalAccepted: false })
const loading = ref(false)
const submitting = ref(false)
const error = ref('')
const orderError = ref('')
let generation = 0

const storeId = computed(() => Number(route.params.storeId))
const cartItems = computed(() => products.value
  .filter(product => Number(quantities[product.id]) > 0)
  .map(product => ({ ...product, quantity: Number(quantities[product.id]) })))
const cartTotal = computed(() => cartItems.value.reduce((sum, item) => sum + Number(item.price) * item.quantity, 0))
const formatMoney = value => Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

async function loadStorefront() {
  const current = ++generation
  loading.value = true
  error.value = ''
  orderError.value = ''
  buyer.legalAccepted = false
  store.value = null
  products.value = []
  Object.keys(quantities).forEach(id => delete quantities[id])
  try {
    const [profile, records] = await Promise.all([
      commerceApi.publicStore(storeId.value),
      commerceApi.publicProducts(storeId.value),
    ])
    if (current !== generation) return
    store.value = profile
    products.value = await Promise.all(records.map(async product => ({
      ...product,
      unit: product.saleUnit || '',
      imageUrl: product.images?.[0]?.assetId
        ? (await commerceApi.publicImageUrl(product.images[0].assetId).catch(() => null))?.downloadUrl || ''
        : '',
    })))
  } catch (failure) {
    if (current === generation) error.value = failure.message || '店铺暂时无法打开。'
  } finally {
    if (current === generation) loading.value = false
  }
}

function setQuantity(product, amount) {
  const next = Math.max(0, Math.min(product.inventory == null ? 99 : product.inventory, amount))
  quantities[product.id] = next
}

async function submitOrder() {
  if (submitting.value || cartItems.value.length === 0) return
  if (!buyer.legalAccepted) {
    orderError.value = '提交前请阅读并同意交易与售后规则及订单信息处理说明。'
    return
  }
  orderError.value = ''
  submitting.value = true
  try {
    const receipt = await commerceApi.placeOrder({
      items: cartItems.value.map(item => ({ productId: item.id, quantity: item.quantity })),
      buyerName: buyer.name.trim(),
      buyerPhone: buyer.phone.trim(),
      shippingAddress: buyer.address.trim(),
      buyerNote: buyer.note.trim(),
    })
    await router.push({ name: 'public-order', params: { token: receipt.lookupToken } })
  } catch (failure) {
    orderError.value = failure.message || '订单尚未提交，请稍后重试。'
  } finally {
    submitting.value = false
  }
}

watch(() => route.params.storeId, loadStorefront, { immediate: true })
</script>

<template>
  <main class="public-commerce">
    <header class="public-commerce-topbar">
      <RouterLink to="/information" class="public-brand"><span class="public-brand-seal"><img src="/images/brand/yifangzhi-mark.png" alt="" /></span><span><strong>一方志</strong><small>本地商家与商品</small></span></RouterLink>
      <nav aria-label="公开服务"><RouterLink to="/information">商家信息</RouterLink><RouterLink to="/merchant-alliance">商家入驻</RouterLink></nav>
    </header>

    <div class="public-commerce-content">
      <div class="public-breadcrumb"><RouterLink to="/information"><ArrowLeft :size="14" />信息广场</RouterLink><span>/</span><span>实体商品店铺</span></div>
      <p v-if="loading" class="public-state" role="status">正在读取店铺资料…</p>
      <section v-else-if="error" class="public-empty" role="alert"><Store :size="28" /><h1>店铺暂时无法打开</h1><p>{{ error }}</p><button type="button" class="public-button" @click="loadStorefront"><RefreshCw :size="15" />重新加载</button></section>
      <template v-else-if="store">
        <header class="public-store-heading">
          <div class="public-store-mark"><Store :size="24" /></div>
          <div class="public-store-title"><p>一方志 · 实体商品售卖</p><h1>{{ store.name }}</h1><div class="public-store-meta"><span v-if="store.address">{{ store.address }}</span><span v-if="store.businessHours">营业时间：{{ store.businessHours }}</span><a v-if="store.phone" :href="`tel:${store.phone}`">{{ store.phone }}</a></div></div>
          <RouterLink class="public-text-link" to="/information">浏览商家信息<ArrowRight :size="14" /></RouterLink>
        </header>

        <div class="public-store-layout">
          <section class="public-product-section" aria-labelledby="public-products-title">
            <div class="public-section-heading"><div><p>STORE GOODS</p><h2 id="public-products-title">店铺商品</h2></div><span>{{ products.length }} 件在售</span></div>
            <p v-if="!products.length" class="public-inline-empty">商家尚未上架实体商品。</p>
            <div v-else class="public-product-list">
              <article v-for="product in products" :key="product.id" class="public-product">
                <div class="public-product-image">
                  <img v-if="product.imageUrl" :src="product.imageUrl" :alt="`${product.name}商品图`" loading="lazy" />
                  <span v-else>{{ product.name.slice(0, 1) }}</span>
                </div>
                <div class="public-product-info"><span class="public-product-category">{{ product.category }}</span><h3>{{ product.name }}</h3><p v-if="product.specification" class="public-product-spec">{{ product.specification }}</p><p v-if="product.coreSellingPoints" class="public-product-description">{{ product.coreSellingPoints }}</p><p v-if="product.promotionRule" class="public-product-promotion">{{ product.promotionRule }}</p><div class="public-product-price"><strong>¥{{ formatMoney(product.price) }}</strong><span>/ {{ product.saleUnit }}</span><small>{{ product.inventory == null ? '库存不限' : `余量 ${product.inventory}` }}</small></div></div>
                <div class="public-quantity" :aria-label="`${product.name}购买数量`"><button type="button" :aria-label="`减少${product.name}数量`" :disabled="!quantities[product.id]" @click="setQuantity(product, (quantities[product.id] || 0) - 1)"><Minus :size="14" /></button><span>{{ quantities[product.id] || 0 }}</span><button type="button" :aria-label="`增加${product.name}数量`" :disabled="product.inventory === 0 || quantities[product.id] >= (product.inventory ?? 99)" @click="setQuantity(product, (quantities[product.id] || 0) + 1)"><Plus :size="14" /></button></div>
              </article>
            </div>
            <RouterLink to="/information" class="public-store-footer-link">了解本地商家活动与合作信息<ArrowRight :size="14" /></RouterLink>
          </section>

          <aside class="public-checkout" aria-labelledby="checkout-title">
            <div class="public-section-heading"><div><p>YOUR ORDER</p><h2 id="checkout-title"><ShoppingBag :size="19" />提交订单</h2></div><span>{{ cartItems.reduce((sum, item) => sum + item.quantity, 0) }} 件</span></div>
            <div v-if="cartItems.length" class="public-cart-lines"><div v-for="item in cartItems" :key="item.id"><span>{{ item.name }} × {{ item.quantity }}</span><strong>¥{{ formatMoney(item.price * item.quantity) }}</strong></div><div class="public-cart-total"><span>合计</span><strong>¥{{ formatMoney(cartTotal) }}</strong></div></div>
            <p v-else class="public-cart-empty">选择商品后填写收货信息。</p>
            <form class="public-order-form" @submit.prevent="submitOrder">
              <label><span>收货人</span><input v-model.trim="buyer.name" required maxlength="80" autocomplete="name" placeholder="姓名" /></label>
              <label><span>联系电话</span><input v-model.trim="buyer.phone" required maxlength="20" type="tel" inputmode="tel" autocomplete="tel" pattern="[0-9+()\- ]{6,20}" placeholder="可联系到您的电话" /></label>
              <label><span>收货地址</span><textarea v-model.trim="buyer.address" required maxlength="500" autocomplete="street-address" rows="2" placeholder="省市区、街道和门牌号" /></label>
              <label><span>备注</span><textarea v-model.trim="buyer.note" maxlength="500" rows="2" placeholder="选填" /></label>
              <label class="public-order-terms"><input v-model="buyer.legalAccepted" type="checkbox" required :disabled="submitting" /><span>我已阅读并同意<a href="/policies/terms" target="_blank" rel="noopener noreferrer">《用户注册与服务协议》</a>、<a href="/policies/aftersales" target="_blank" rel="noopener noreferrer">《售后与退款规则》</a>及<a href="/policies/privacy" target="_blank" rel="noopener noreferrer">《隐私协议》</a>，同意将本订单的收货人、电话、地址与备注提供给{{ store.name }}，用于履约及售后。</span></label>
              <p v-if="orderError" class="public-error" role="alert">{{ orderError }}</p>
              <button class="public-button public-button-primary" type="submit" :disabled="submitting || !cartItems.length"><ShoppingBag :size="16" />{{ submitting ? '正在提交…' : '提交订单' }}</button>
              <p class="public-payment-note">订单提交后由商家确认，当前不收取线上付款。</p>
            </form>
          </aside>
        </div>
      </template>
    </div>
  </main>
</template>
