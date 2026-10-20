<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { Dialog, DialogDescription, DialogPanel, DialogTitle } from '@headlessui/vue'
import { Plus, RefreshCw, X } from 'lucide-vue-next'
import AppSelect from '../components/AppSelect.vue'
import AllianceAccountPanel from '../components/AllianceAccountPanel.vue'
import AllianceStatementsPanel from '../components/AllianceStatementsPanel.vue'
import { allianceApi, discountLabel, offerStatuses, settlementStates, yuan } from '../services/allianceApi'
import { stores, selectedStoreId } from '../stores/merchantContext'
import '../alliance.css'

const tab = ref('offers')
const rules = ref(null)
const offers = ref([])
const settlements = ref({ items: [], total: 0 })
const loading = ref(false)
const error = ref('')
const notice = ref('')
const busyId = ref(null)
const formOpen = ref(false)
const form = reactive({ storeId: null, productId: null, quantity: 1 })
const products = ref([])
const productsLoading = ref(false)
const formError = ref('')
const submitting = ref(false)
let generation = 0

const activeStores = computed(() => stores.value.filter(store => store.status !== 'ARCHIVED'))
const product = computed(() => products.value.find(item => item.id === Number(form.productId)) || null)
const maxQuantity = computed(() => {
  if (!product.value || !rules.value) return 1
  return product.value.inventory == null ? rules.value.unlimitedInventoryCap
    : Math.floor(product.value.inventory * rules.value.inventorySharePercent / 100)
})
const faceMinor = computed(() => product.value ? Math.round(Number(product.value.price) * 100) : 0)
const date = value => value ? new Date(value).toLocaleDateString('zh-CN') : ''
const tagClass = status => ({ ACTIVE: 'is-active', PENDING_REVIEW: 'is-warning', REJECTED: 'is-error', PAYABLE: 'is-active', FROZEN: 'is-error', IN_DISPUTE_WINDOW: 'is-warning' })[status] || ''

async function load() {
  const attempt = ++generation
  loading.value = true
  error.value = ''
  try {
    const [nextRules, nextOffers, nextSettlements] = await Promise.all([allianceApi.rules(), allianceApi.offers(), allianceApi.settlements({ size: 50 })])
    if (attempt !== generation) return
    rules.value = nextRules
    offers.value = nextOffers
    settlements.value = nextSettlements
  } catch (failure) {
    if (attempt === generation) error.value = failure.message || '供券记录暂时无法读取。'
  } finally {
    if (attempt === generation) loading.value = false
  }
}

async function loadProducts(storeId) {
  products.value = []
  form.productId = null
  if (!storeId) return
  productsLoading.value = true
  try {
    const result = await allianceApi.storeProducts(storeId)
    products.value = (result.items || []).filter(item => item.status === 'ACTIVE' && Number(item.price) > 0)
  } catch (failure) {
    formError.value = failure.message || '商品暂时无法读取。'
  } finally {
    productsLoading.value = false
  }
}

function openForm() {
  formError.value = ''
  form.quantity = 1
  formOpen.value = true
  const storeId = selectedStoreId.value || activeStores.value[0]?.id || null
  // 门店没变时监听不会触发，手动读一次商品；变了交给下面的监听。
  if (form.storeId === storeId) loadProducts(storeId)
  else form.storeId = storeId
}

watch(() => form.storeId, storeId => { if (formOpen.value) loadProducts(storeId) })

async function submit() {
  if (submitting.value || !product.value) return
  submitting.value = true
  formError.value = ''
  try {
    await allianceApi.createOffer({ storeId: Number(form.storeId), productId: product.value.id, quantity: Number(form.quantity) })
    formOpen.value = false
    notice.value = '供券单已提交，平台审核通过后会出现在券库里。对应数量的库存已经预留。'
    await load()
  } catch (failure) {
    formError.value = failure.message || '提交失败，请稍后再试。'
  } finally {
    submitting.value = false
  }
}

async function withdraw(offer) {
  if (busyId.value) return
  if (!window.confirm('撤回后，还没被领走的券会下架，对应库存还给商品。已经领出去的券仍需在门店照常核销。确定撤回吗？')) return
  busyId.value = offer.id
  error.value = ''
  try {
    await allianceApi.withdrawOffer(offer.id)
    notice.value = '供券单已撤回。'
    await load()
  } catch (failure) {
    error.value = failure.message || '撤回失败，请稍后再试。'
  } finally {
    busyId.value = null
  }
}

load()
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <div class="business-page alliance-offers-page">
    <header class="business-heading">
      <div><h1>供券管理</h1><p>拿自家门店的一件商品提供联盟券，会员商户领取后到店核销。<template v-if="rules">核销满 {{ rules.disputeDays }} 天、无申诉后，平台按与你约定的折扣结算。</template></p></div>
      <div class="business-heading-links">
        <RouterLink :to="{ name: 'alliance-rules' }" class="business-link">联盟券规则</RouterLink>
        <RouterLink :to="{ name: 'alliance-redeem' }" class="business-link">去门店核销</RouterLink>
        <button type="button" class="business-button business-button-primary" @click="openForm"><Plus :size="15" aria-hidden="true" />提供联盟券</button>
      </div>
    </header>
    <nav class="alliance-tabs" aria-label="供券管理">
      <button type="button" :class="{ active: tab === 'offers' }" :aria-pressed="tab === 'offers'" @click="tab = 'offers'">供券单<small>{{ offers.length }}</small></button>
      <button type="button" :class="{ active: tab === 'settlements' }" :aria-pressed="tab === 'settlements'" @click="tab = 'settlements'">结算明细<small>{{ settlements.total }}</small></button>
      <button type="button" :class="{ active: tab === 'statements' }" :aria-pressed="tab === 'statements'" @click="tab = 'statements'">对账单</button>
      <button type="button" :class="{ active: tab === 'account' }" :aria-pressed="tab === 'account'" @click="tab = 'account'">收款账户</button>
    </nav>
    <p v-if="notice" class="alliance-notice" role="status">{{ notice }}</p>
    <p v-if="error" class="alliance-error" role="alert">{{ error }}</p>

    <AllianceStatementsPanel v-if="tab === 'statements'" :confirm-days="rules?.statementConfirmDays ?? null" />
    <AllianceAccountPanel v-else-if="tab === 'account'" />
    <p v-else-if="loading && !offers.length" class="alliance-state" role="status">正在读取…</p>
    <template v-else-if="tab === 'offers'">
      <div v-if="!offers.length" class="alliance-state">
        <h3>还没有提供过联盟券</h3>
        <p>需要先通过商家入驻审核。提交后由平台审核定价和数量。</p>
        <button type="button" class="business-button" @click="load"><RefreshCw :size="14" aria-hidden="true" />刷新</button>
      </div>
      <div v-else class="alliance-panel alliance-table-wrap">
        <table class="alliance-table">
          <thead><tr><th>商品 / 门店</th><th>面值</th><th>约定结算价</th><th>已领 / 总数</th><th>已核销</th><th>状态</th><th>提交时间</th><th /></tr></thead>
          <tbody>
            <tr v-for="offer in offers" :key="offer.id">
              <td><strong>{{ offer.productName }}</strong><br /><span class="alliance-muted">{{ offer.storeName }}</span></td>
              <td class="num">¥{{ yuan(offer.faceValueMinor) }}</td>
              <td class="num"><template v-if="offer.settleAmountMinor != null">¥{{ yuan(offer.settleAmountMinor) }}<br /><span class="alliance-muted">约定 {{ discountLabel(offer.settleRateBp) }}</span></template><span v-else-if="offer.status === 'PENDING_REVIEW'" class="alliance-muted">审核时约定</span><span v-else class="alliance-muted">—</span></td>
              <td class="num">{{ offer.claimedQty }} / {{ offer.totalQty }}</td>
              <td class="num">{{ offer.redeemedQty }}</td>
              <td><span class="alliance-tag" :class="tagClass(offer.status)">{{ offerStatuses[offer.status] || offer.status }}</span><p v-if="offer.reviewNote" class="alliance-muted">{{ offer.reviewNote }}</p></td>
              <td>{{ date(offer.createdAt) }}</td>
              <td><button v-if="['PENDING_REVIEW', 'ACTIVE'].includes(offer.status)" type="button" class="business-link" :disabled="busyId === offer.id" @click="withdraw(offer)">撤回</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
    <template v-else>
      <p class="alliance-muted alliance-statements-intro">每核销一张券记一笔结算。过了申诉期的，会在下月 1 日汇总进「对账单」，按对账单打款。</p>
      <div v-if="!settlements.items.length" class="alliance-state"><h3>还没有结算明细</h3><p>你的门店核销联盟券后，会在这里记一笔。</p></div>
      <div v-else class="alliance-panel alliance-table-wrap">
        <table class="alliance-table">
          <thead><tr><th>商品</th><th>面值</th><th>结算金额</th><th>状态</th><th>申诉期到</th><th>打款</th></tr></thead>
          <tbody>
            <tr v-for="item in settlements.items" :key="item.id">
              <td>{{ item.productName }}</td>
              <td class="num">¥{{ yuan(item.faceValueMinor) }}</td>
              <td class="num">¥{{ yuan(item.settleAmountMinor) }}</td>
              <td><span class="alliance-tag" :class="tagClass(item.state)">{{ settlementStates[item.state] || item.state }}</span></td>
              <td>{{ date(item.payableAfter) }}</td>
              <td>{{ item.paidAt ? `${date(item.paidAt)} · ${item.paidReference}` : '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <Dialog :open="formOpen" class="alliance-dialog" @close="!submitting && (formOpen = false)">
      <div class="alliance-dialog-backdrop" aria-hidden="true" />
      <div class="alliance-dialog-position">
        <DialogPanel class="alliance-dialog-panel">
          <header><div><DialogTitle as="h2">提供联盟券</DialogTitle><DialogDescription class="alliance-muted">提交后对应数量的库存会先预留；平台驳回或你撤回时自动还回。</DialogDescription></div><button type="button" class="alliance-dialog-close" aria-label="关闭" :disabled="submitting" @click="formOpen = false"><X :size="16" /></button></header>
          <form class="alliance-form" @submit.prevent="submit">
            <label>门店<AppSelect v-model="form.storeId" name="alliance-store" required><option :value="null" disabled>请选择门店</option><option v-for="store in activeStores" :key="store.id" :value="store.id">{{ store.name }}</option></AppSelect></label>
            <label>商品<AppSelect v-model="form.productId" name="alliance-product" required :disabled="productsLoading || !products.length"><option :value="null" disabled>{{ productsLoading ? '正在读取商品…' : products.length ? '请选择商品' : '这家门店还没有可用的商品' }}</option><option v-for="item in products" :key="item.id" :value="item.id">{{ item.name }} · ¥{{ Number(item.price).toFixed(2) }} / {{ item.saleUnit }}</option></AppSelect></label>
            <label>供券数量<input v-model.number="form.quantity" type="number" min="1" :max="maxQuantity" step="1" required /><small v-if="product && rules">{{ product.inventory == null ? `库存不限的商品，一次最多 ${maxQuantity} 张。` : `当前库存 ${product.inventory}，最多可提供 ${maxQuantity} 张（库存的 ${rules.inventorySharePercent}%）。` }}每张面值 ¥{{ yuan(faceMinor) }}，结算折扣在平台审核时与你约定。每月供券总面值不超过 ¥{{ yuan(rules.monthlyFaceCapMinor) }}。</small></label>
            <p v-if="formError" class="alliance-error" role="alert">{{ formError }}</p>
            <div class="alliance-dialog-actions">
              <button type="button" class="business-button" :disabled="submitting" @click="formOpen = false">取消</button>
              <button type="submit" class="business-button business-button-primary" :disabled="submitting || !product || maxQuantity < 1">{{ submitting ? '提交中…' : '提交审核' }}</button>
            </div>
          </form>
        </DialogPanel>
      </div>
    </Dialog>
  </div>
</template>
