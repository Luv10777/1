<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Dialog, DialogDescription, DialogPanel, DialogTitle } from '@headlessui/vue'
import { ArrowRight, Check, Minus, Package, Plus, RefreshCw, ShoppingBag, Store, X } from 'lucide-vue-next'
import { commerceApi } from '../services/commerceApi'

const router = useRouter()
const product = ref(null)
const store = ref(null)
const images = ref([])
const selectedImage = ref(0)
const loading = ref(true)
const loadError = ref('')
const checkoutOpen = ref(false)
const quantity = ref(1)
const submitting = ref(false)
const orderError = ref('')
const orderEditor = ref(null)
const orderReceipt = ref(null)
const buyer = reactive({ name: '', phone: '', address: '', note: '' })
const maxQuantity = computed(() => Math.min(product.value?.inventory ?? 99, 99))
const available = computed(() => Boolean(product.value) && maxQuantity.value > 0)
const formatMoney = value => Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

async function loadProduct() {
  loading.value = true
  loadError.value = ''
  product.value = null
  store.value = null
  images.value = []
  selectedImage.value = 0
  try {
    const listing = await commerceApi.featuredListing()
    const [profile, products] = await Promise.all([
      commerceApi.publicStore(listing.storeId), commerceApi.publicProducts(listing.storeId),
    ])
    const item = products.find(record => record.id === listing.productId && record.type === 'PHYSICAL' && record.onlineStatus === 'PUBLISHED')
    if (!item) throw new Error('这件商品暂时不可售，请稍后再来。')
    const photos = await Promise.all((item.images || []).map(async photo => {
      const ticket = await commerceApi.publicImageUrl(photo.assetId)
      return ticket.downloadUrl
    }))
    product.value = item
    store.value = profile
    images.value = photos
    quantity.value = 1
  } catch (failure) {
    loadError.value = failure.message || '商品暂时无法读取，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function openCheckout() {
  if (!available.value && !orderReceipt.value) return
  if (!orderReceipt.value) quantity.value = Math.max(1, Math.min(quantity.value, maxQuantity.value))
  orderError.value = ''
  checkoutOpen.value = true
}

async function goToOrder() {
  const failure = await router.push({ name: 'public-order', params: { token: orderReceipt.value.lookupToken } })
  if (!failure) checkoutOpen.value = false
}

async function submitOrder() {
  if (submitting.value || !available.value || orderReceipt.value) return
  for (const field of orderEditor.value?.querySelectorAll('input[required], textarea[required]') || []) {
    field.setCustomValidity(field.value.trim() ? '' : '请填写此项。')
  }
  if (!orderEditor.value?.reportValidity()) return
  submitting.value = true
  orderError.value = ''
  try {
    const receipt = await commerceApi.placeOrder({
      items: [{ productId: product.value.id, quantity: quantity.value }],
      buyerName: buyer.name.trim(), buyerPhone: buyer.phone.trim(),
      shippingAddress: buyer.address.trim(), buyerNote: buyer.note.trim(),
    })
    if (!receipt?.lookupToken) throw new Error('未收到订单查询凭证，请联系商家确认提交结果。')
    orderReceipt.value = receipt
    await goToOrder()
  } catch (failure) {
    orderError.value = failure.message || '订单提交未完成，请稍后重试。'
  } finally {
    submitting.value = false
  }
}

onMounted(loadProduct)
</script>

<template>
  <section id="merchant-goods" class="merchant-goods" aria-labelledby="merchant-goods-title" :aria-busy="loading">
    <div class="merchant-goods-heading"><div><span class="merchant-section-index">01 / 精选实物</span><h2 id="merchant-goods-title">好生意，从一件好物开始。</h2></div><span class="merchant-goods-caption"><Package :size="15" aria-hidden="true" />实体商品 · 商家直售</span></div>
    <div v-if="loading" class="merchant-goods-state" role="status"><ShoppingBag :size="26" aria-hidden="true" /><p>正在读取商品…</p></div>
    <div v-else-if="loadError" class="merchant-goods-state" role="alert"><Package :size="26" aria-hidden="true" /><p>{{ loadError }}</p><button type="button" class="business-button" @click="loadProduct"><RefreshCw :size="14" aria-hidden="true" />重新加载</button></div>
    <article v-else-if="product" class="merchant-featured-product">
      <div class="merchant-product-gallery">
        <div class="merchant-product-photo"><img v-if="images.length" :src="images[selectedImage]" :alt="`${product.name} · ${selectedImage === 0 ? '商品实物' : '商品与使用场景'}`" width="1600" height="1600" fetchpriority="high" /><span v-else class="merchant-photo-empty">商品图片暂不可用</span><span class="merchant-photo-label">{{ selectedImage === 0 ? '商品实物' : '细节与场景' }}</span></div>
        <div v-if="images.length > 1" class="merchant-product-thumbnails" aria-label="商品图片"><button v-for="(url, index) in images" :key="url" type="button" :aria-label="`查看第 ${index + 1} 张商品图片`" :aria-pressed="selectedImage === index" @click="selectedImage = index"><img :src="url" :alt="`商品图 ${index + 1}`" width="64" height="64" /></button><span>{{ String(selectedImage + 1).padStart(2, '0') }} / {{ String(images.length).padStart(2, '0') }}</span></div>
      </div>
      <div class="merchant-product-copy">
        <div class="merchant-product-kicker"><span>{{ product.category }}</span><span>实物商品</span></div>
        <h3>{{ product.name }}</h3>
        <p class="merchant-product-description">{{ product.coreSellingPoints }}</p>
        <p class="merchant-product-specification">{{ product.specification }}</p>
        <div class="merchant-product-price"><span>¥</span><strong>{{ formatMoney(product.price) }}</strong><small>/ {{ product.saleUnit }}</small></div>
        <dl class="merchant-product-facts"><div><dt>销售商家</dt><dd><Store :size="14" aria-hidden="true" />{{ store.name }}</dd></div><div><dt>交付方式</dt><dd>实物交付，商家确认订单后安排</dd></div><div><dt>商品状态</dt><dd>{{ available ? '可提交订单' : '暂时售罄' }}</dd></div></dl>
        <div class="merchant-product-purchase"><div class="merchant-quantity" aria-label="购买数量"><button type="button" aria-label="减少购买数量" :disabled="quantity <= 1 || !available || !!orderReceipt" @click="quantity--"><Minus :size="15" /></button><output aria-live="polite">{{ quantity }}</output><button type="button" aria-label="增加购买数量" :disabled="quantity >= maxQuantity || !available || !!orderReceipt" @click="quantity++"><Plus :size="15" /></button></div><button type="button" class="business-button business-button-primary merchant-buy-button" :disabled="!available && !orderReceipt" @click="openCheckout"><ShoppingBag :size="17" aria-hidden="true" />{{ orderReceipt ? '查看本次订单' : available ? '立即购买' : '暂时售罄' }}<ArrowRight :size="16" aria-hidden="true" /></button></div>
        <p class="merchant-product-order-note"><Check :size="14" aria-hidden="true" />填写收货信息 · 提交订单 · 商家确认</p>
        <RouterLink :to="`/store/${store.id}`" class="business-link merchant-shop-link">进入店铺<ArrowRight :size="14" aria-hidden="true" /></RouterLink>
      </div>
    </article>
  </section>

  <Dialog :open="checkoutOpen" class="merchant-checkout-dialog" @close="!submitting && (checkoutOpen = false)">
    <div class="merchant-checkout-backdrop" aria-hidden="true" />
    <div class="merchant-checkout-position">
      <DialogPanel class="merchant-checkout-panel">
        <header><div><span class="merchant-section-index">{{ orderReceipt ? '等待商家确认' : '填写收货信息' }}</span><DialogTitle as="h2">{{ orderReceipt ? '订单已提交' : '确认商品订单' }}</DialogTitle></div><button type="button" aria-label="关闭订单窗口" :disabled="submitting" @click="checkoutOpen = false"><X :size="20" /></button></header>
        <DialogDescription class="merchant-checkout-description">提交后由商家确认供货与交付安排，当前不收取线上付款。</DialogDescription>
        <div v-if="product" class="merchant-checkout-item"><img v-if="images.length" :src="images[0]" :alt="product.name" width="64" height="64" /><div><strong>{{ product.name }}</strong><span>¥{{ formatMoney(product.price) }} × {{ quantity }} {{ product.saleUnit }}</span></div><strong>¥{{ formatMoney(product.price * quantity) }}</strong></div>
        <div v-if="orderReceipt" class="merchant-checkout-result" role="status"><p>订单编号：{{ orderReceipt.orderNumber }}</p><button type="button" class="business-button business-button-primary" @click="goToOrder">查看订单进度<ArrowRight :size="16" aria-hidden="true" /></button><p>订单已保存，可处理尚未提交的入驻资料后再查看。</p></div>
        <form v-else ref="orderEditor" :aria-busy="submitting" @submit.prevent="submitOrder" @input="$event.target.setCustomValidity?.('')">
          <fieldset :disabled="submitting" class="business-form-fields">
            <div class="business-field-grid"><label class="business-field"><span>收货人<b>*</b></span><input v-model.trim="buyer.name" name="buyer-name" required maxlength="80" autocomplete="name" pattern=".*\S.*" placeholder="填写收货人姓名" /></label><label class="business-field"><span>联系电话<b>*</b></span><input v-model.trim="buyer.phone" name="buyer-phone" required type="tel" inputmode="tel" autocomplete="tel" maxlength="20" pattern="[0-9+\(\)\- ]{6,20}" placeholder="填写可联系的电话" /></label></div>
            <label class="business-field"><span>收货地址<b>*</b></span><textarea v-model.trim="buyer.address" name="shipping-address" required autocomplete="street-address" maxlength="500" rows="2" placeholder="省、市、区、街道及门牌号" /></label>
            <label class="business-field"><span>订单备注</span><textarea v-model.trim="buyer.note" name="buyer-note" maxlength="500" rows="2" placeholder="选填，可说明设备型号或交付需求" /></label>
          </fieldset>
          <p v-if="orderError" class="business-message-error merchant-checkout-error" role="alert">{{ orderError }}</p>
          <footer><span>商品合计<strong v-if="product">¥{{ formatMoney(product.price * quantity) }}</strong></span><button type="submit" class="business-button business-button-primary" :disabled="submitting"><ShoppingBag :size="16" aria-hidden="true" />{{ submitting ? '正在提交…' : '提交订单' }}</button></footer>
        </form>
      </DialogPanel>
    </div>
  </Dialog>
</template>
