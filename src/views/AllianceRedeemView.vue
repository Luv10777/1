<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { CheckCircle2, RefreshCw, ScanLine } from 'lucide-vue-next'
import { allianceApi, yuan } from '../services/allianceApi'
import { selectedStore, selectedStoreId, storeLoading } from '../stores/merchantContext'
import '../alliance.css'

const code = ref('')
const preview = ref(null)
const checking = ref(false)
const redeeming = ref(false)
const error = ref('')
const done = ref(null)
const records = ref([])
const recordsLoading = ref(false)
let generation = 0

const digits = computed(() => code.value.replace(/\D/g, '').slice(0, 8))
const date = value => value ? new Date(value).toLocaleString('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }) : ''

watch(code, () => {
  if (code.value !== digits.value) code.value = digits.value
  preview.value = null
})

async function loadRecords() {
  const storeId = selectedStoreId.value
  const attempt = ++generation
  records.value = []
  if (!storeId) return
  recordsLoading.value = true
  try {
    const result = await allianceApi.redemptions(storeId)
    if (attempt === generation) records.value = result
  } catch (failure) {
    if (attempt === generation) error.value = failure.message || '核销记录暂时无法读取。'
  } finally {
    if (attempt === generation) recordsLoading.value = false
  }
}

async function check() {
  if (checking.value || digits.value.length !== 8 || !selectedStoreId.value) return
  checking.value = true
  error.value = ''
  done.value = null
  try { preview.value = await allianceApi.previewRedemption(selectedStoreId.value, digits.value) }
  catch (failure) { error.value = failure.message || '券码核对失败，请稍后再试。' }
  finally { checking.value = false }
}

async function redeem() {
  if (redeeming.value || !preview.value) return
  redeeming.value = true
  error.value = ''
  try {
    done.value = await allianceApi.redeem(selectedStoreId.value, digits.value)
    code.value = ''
    await loadRecords()
  } catch (failure) {
    error.value = failure.message || '核销失败，请稍后再试。'
  } finally {
    redeeming.value = false
  }
}

watch(selectedStoreId, () => { code.value = ''; done.value = null; error.value = ''; loadRecords() }, { immediate: true })
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <div class="business-page alliance-redeem-page">
    <header class="business-heading">
      <div><h1>门店核销</h1><p>顾客出示联盟券的数字券码，核对商品和领券商户无误后再核销，并当场交付。</p></div>
      <div class="business-heading-links"><RouterLink :to="{ name: 'alliance-rules', hash: '#for-suppliers' }" class="business-link">联盟券规则</RouterLink><RouterLink :to="{ name: 'alliance-offers' }" class="business-link">供券管理</RouterLink></div>
    </header>

    <div v-if="!selectedStoreId" class="alliance-state"><h3>{{ storeLoading ? '正在读取门店…' : '请先在页面顶部选择门店' }}</h3><p>联盟券只能在券上写的那家门店核销。</p></div>
    <div v-else class="alliance-redeem">
      <section class="alliance-panel" aria-labelledby="alliance-redeem-title">
        <div class="alliance-panel-heading"><h2 id="alliance-redeem-title">核销到「{{ selectedStore?.name }}」</h2></div>
        <form class="alliance-form" @submit.prevent="check">
          <label>券码<input v-model="code" class="alliance-code-input" inputmode="numeric" autocomplete="off" maxlength="8" placeholder="8 位数字" aria-describedby="alliance-code-hint" /></label>
          <small id="alliance-code-hint">可用扫码枪扫顾客的二维码，或手动输入数字。连续输错多次会暂停核销一段时间。</small>
          <button type="submit" class="business-button" :disabled="checking || digits.length !== 8"><ScanLine :size="15" aria-hidden="true" />{{ checking ? '核对中…' : '核对券码' }}</button>
        </form>
        <p v-if="error" class="alliance-error alliance-spaced" role="alert">{{ error }}</p>
        <div v-if="preview" class="alliance-preview" role="status">
          <span class="alliance-muted">请核对后再核销</span>
          <strong>{{ preview.productName }}</strong>
          <span>领券商户：{{ preview.holderName }}</span>
          <span>面值 ¥{{ yuan(preview.faceValueMinor) }} · 有效期至 {{ date(preview.expiresAt) }}</span>
          <button type="button" class="business-button business-button-primary" :disabled="redeeming" @click="redeem">{{ redeeming ? '核销中…' : `确认核销，交付 1 ${preview.saleUnit}` }}</button>
        </div>
        <p v-if="done" class="alliance-notice alliance-spaced" role="status"><CheckCircle2 :size="14" aria-hidden="true" /> 已核销「{{ done.productName }}」，请把商品交给顾客。</p>
      </section>

      <section class="alliance-panel" aria-labelledby="alliance-records-title">
        <div class="alliance-panel-heading"><h2 id="alliance-records-title">本店核销记录</h2><button type="button" class="business-link" :disabled="recordsLoading" aria-label="刷新核销记录" @click="loadRecords"><RefreshCw :size="14" /></button></div>
        <p v-if="recordsLoading && !records.length" class="alliance-muted" role="status">正在读取…</p>
        <p v-else-if="!records.length" class="alliance-muted">还没有核销记录。</p>
        <ul v-else class="business-record-list">
          <li v-for="item in records" :key="`${item.redeemedAt}-${item.productName}`">
            <div class="business-record-main"><strong>{{ item.productName }} · ¥{{ yuan(item.faceValueMinor) }}</strong><small>{{ item.holderName }} · {{ date(item.redeemedAt) }}</small></div>
          </li>
        </ul>
      </section>
    </div>
  </div>
</template>
