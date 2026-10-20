<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import QRCode from 'qrcode'
import { Dialog, DialogDescription, DialogPanel, DialogTitle } from '@headlessui/vue'
import { Clock, MapPin, Phone, QrCode, RefreshCw, Store, Ticket, X } from 'lucide-vue-next'
import { allianceApi, voucherStatuses, yuan } from '../services/allianceApi'
import '../alliance.css'

const PAGE_SIZE = 20
const feed = ref({ items: [], total: 0, totalPages: 0 })
const page = ref(0)
const filter = ref('ACTIVE')
const loading = ref(false)
const error = ref('')
const notice = ref('')
const showing = ref(null)
const qr = ref('')
const codeLoading = ref(false)
const codeError = ref('')
const disputing = ref(null)
const reason = ref('')
const submitting = ref(false)
let generation = 0

const filters = [
  { value: 'ACTIVE', label: '可使用' },
  { value: 'REDEEMED', label: '已核销' },
  { value: 'ALL', label: '全部' },
]
const visible = computed(() => filter.value === 'ALL' ? feed.value.items
  : feed.value.items.filter(item => filter.value === 'REDEEMED' ? ['REDEEMED', 'DISPUTED'].includes(item.status) : item.status === filter.value))
const pages = computed(() => Math.max(1, feed.value.totalPages || 1))
const date = value => value ? new Date(value).toLocaleString('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }) : ''
const tagClass = status => ({ ACTIVE: 'is-active', DISPUTED: 'is-warning', CLAIM_FAILED: 'is-error', CLAIMING: 'is-warning' })[status] || ''

async function load() {
  const attempt = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = await allianceApi.vouchers({ page: page.value, size: PAGE_SIZE })
    if (attempt === generation) feed.value = result
  } catch (failure) {
    if (attempt === generation) error.value = failure.message || '联盟券暂时无法读取。'
  } finally {
    if (attempt === generation) loading.value = false
  }
}

async function showCode(item) {
  showing.value = item
  qr.value = ''
  codeError.value = ''
  codeLoading.value = true
  try {
    const detail = await allianceApi.voucher(item.ref)
    showing.value = detail
    if (!detail.code) { codeError.value = '这张券现在不能使用。'; return }
    try { qr.value = await QRCode.toDataURL(detail.code, { width: 220, margin: 2, errorCorrectionLevel: 'M' }) } catch { /* 画不出二维码时还有数字券码 */ }
  } catch (failure) {
    codeError.value = failure.message || '券码暂时无法读取。'
  } finally {
    codeLoading.value = false
  }
}

function closeCode() { showing.value = null; qr.value = '' }

function openDispute(item) { disputing.value = item; reason.value = '' }

async function submitDispute() {
  if (submitting.value || !reason.value.trim()) return
  submitting.value = true
  error.value = ''
  try {
    await allianceApi.dispute(disputing.value.ref, reason.value.trim())
    notice.value = '申诉已提交，平台会尽快核实。处理完成前，这笔核销不会结算给对方。'
    disputing.value = null
    await load()
  } catch (failure) {
    error.value = failure.message || '申诉提交失败，请稍后再试。'
  } finally {
    submitting.value = false
  }
}

function go(next) { page.value = next; return load() }

load()
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <div class="business-page alliance-vouchers-page">
    <header class="business-heading">
      <div><h1>我的联盟券</h1><p>到券上的门店出示券码，由店员核销后当场交付。</p></div>
      <div class="business-heading-links">
        <RouterLink :to="{ name: 'alliance-rules' }" class="business-link">联盟券规则</RouterLink>
        <RouterLink :to="{ name: 'alliance-pool' }" class="business-button business-button-primary"><Ticket :size="15" aria-hidden="true" />去领券中心</RouterLink>
      </div>
    </header>
    <nav class="alliance-tabs" aria-label="按状态筛选">
      <button v-for="item in filters" :key="item.value" type="button" :class="{ active: filter === item.value }" :aria-pressed="filter === item.value" @click="filter = item.value">{{ item.label }}</button>
    </nav>
    <p v-if="notice" class="alliance-notice" role="status">{{ notice }}</p>
    <p v-if="error" class="alliance-error" role="alert">{{ error }}</p>

    <p v-if="loading && !feed.items.length" class="alliance-state" role="status">正在读取联盟券…</p>
    <div v-else-if="!visible.length" class="alliance-state">
      <Ticket :size="30" aria-hidden="true" />
      <h3>{{ filter === 'ACTIVE' ? '还没有可使用的联盟券' : '这里还没有记录' }}</h3>
      <p>会员期内可以到领券中心，用额度领取其他联盟商户的券。</p>
      <button type="button" class="business-button" @click="load"><RefreshCw :size="14" aria-hidden="true" />刷新</button>
    </div>
    <ul v-else class="alliance-card-grid">
      <li v-for="item in visible" :key="item.ref">
        <article class="alliance-voucher-card">
          <header><h3>{{ item.productName }}</h3><span class="alliance-face">¥{{ yuan(item.faceValueMinor) }}</span></header>
          <ul class="alliance-facts">
            <li><Store :size="13" aria-hidden="true" />{{ item.storeName }}</li>
            <li v-if="item.storeAddress"><MapPin :size="13" aria-hidden="true" />{{ item.storeAddress }}</li>
            <li v-if="item.storeHours"><Clock :size="13" aria-hidden="true" />{{ item.storeHours }}</li>
            <li v-if="item.status === 'ACTIVE'">有效期至 {{ date(item.expiresAt) }}</li>
            <li v-else-if="item.redeemedAt">核销于 {{ date(item.redeemedAt) }}</li>
            <li v-if="item.disputeReason">申诉：{{ item.disputeReason }}</li>
            <li v-if="item.disputeResult">平台处理结果：{{ item.disputeResult === 'VALID' ? '核销有效' : '核销无效，券已恢复' }}{{ item.disputeNote ? `（${item.disputeNote}）` : '' }}</li>
          </ul>
          <footer>
            <span class="alliance-tag" :class="tagClass(item.status)">{{ voucherStatuses[item.status] || item.status }}</span>
            <div class="alliance-row-actions">
              <button v-if="item.canDispute" type="button" class="business-link" @click="openDispute(item)">没拿到商品？申诉</button>
              <button v-if="item.status === 'ACTIVE'" type="button" class="business-button business-button-primary" @click="showCode(item)"><QrCode :size="15" aria-hidden="true" />出示券码</button>
            </div>
          </footer>
        </article>
      </li>
    </ul>
    <nav v-if="pages > 1" class="alliance-pager" aria-label="联盟券分页">
      <button type="button" :disabled="page <= 0 || loading" @click="go(page - 1)">上一页</button>
      <span>{{ page + 1 }} / {{ pages }}</span>
      <button type="button" :disabled="page + 1 >= pages || loading" @click="go(page + 1)">下一页</button>
    </nav>

    <Dialog :open="Boolean(showing)" class="alliance-dialog" @close="closeCode">
      <div class="alliance-dialog-backdrop" aria-hidden="true" />
      <div class="alliance-dialog-position">
        <DialogPanel class="alliance-dialog-panel">
          <header><div><DialogTitle as="h2">{{ showing?.productName }}</DialogTitle><DialogDescription class="alliance-muted">请向「{{ showing?.storeName }}」的店员出示，扫码或输入数字码核销。</DialogDescription></div><button type="button" class="alliance-dialog-close" aria-label="关闭" @click="closeCode"><X :size="16" /></button></header>
          <p v-if="codeLoading" class="alliance-muted" role="status">正在读取券码…</p>
          <p v-else-if="codeError" class="alliance-error" role="alert">{{ codeError }}</p>
          <div v-else-if="showing?.code" class="alliance-code">
            <img v-if="qr" :src="qr" alt="联盟券二维码" width="200" height="200" />
            <strong>{{ showing.code.slice(0, 4) }} {{ showing.code.slice(4) }}</strong>
            <span class="alliance-muted">券码等同于券，请勿截图转给他人</span>
          </div>
          <ul v-if="showing" class="alliance-facts">
            <li v-if="showing.storeAddress"><MapPin :size="13" aria-hidden="true" />{{ showing.storeAddress }}</li>
            <li v-if="showing.storeHours"><Clock :size="13" aria-hidden="true" />营业时间 {{ showing.storeHours }}</li>
            <li v-if="showing.storePhone"><Phone :size="13" aria-hidden="true" /><a :href="`tel:${showing.storePhone}`">{{ showing.storePhone }}</a></li>
            <li>面值 ¥{{ yuan(showing.faceValueMinor) }} · 有效期至 {{ date(showing.expiresAt) }}</li>
          </ul>
        </DialogPanel>
      </div>
    </Dialog>

    <Dialog :open="Boolean(disputing)" class="alliance-dialog" @close="!submitting && (disputing = null)">
      <div class="alliance-dialog-backdrop" aria-hidden="true" />
      <div class="alliance-dialog-position">
        <DialogPanel class="alliance-dialog-panel">
          <header><div><DialogTitle as="h2">申诉这次核销</DialogTitle><DialogDescription class="alliance-muted">平台核实前，这笔核销不会结算给对方。</DialogDescription></div><button type="button" class="alliance-dialog-close" aria-label="关闭" :disabled="submitting" @click="disputing = null"><X :size="16" /></button></header>
          <form class="alliance-form" @submit.prevent="submitDispute">
            <label>发生了什么<textarea v-model="reason" rows="4" maxlength="300" required placeholder="例如：券被核销了，但没有拿到商品" /></label>
            <div class="alliance-dialog-actions">
              <button type="button" class="business-button" :disabled="submitting" @click="disputing = null">取消</button>
              <button type="submit" class="business-button business-button-primary" :disabled="submitting || !reason.trim()">{{ submitting ? '提交中…' : '提交申诉' }}</button>
            </div>
          </form>
        </DialogPanel>
      </div>
    </Dialog>
  </div>
</template>
