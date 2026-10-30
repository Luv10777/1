<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ChevronRight, Percent, ReceiptText, RefreshCw, Trophy, Users } from 'lucide-vue-next'
import ReferralCommissionsPanel from '../components/referral/ReferralCommissionsPanel.vue'
import ReferralPromotersPanel from '../components/referral/ReferralPromotersPanel.vue'
import ReferralRelationsPanel from '../components/referral/ReferralRelationsPanel.vue'
import { referralApi } from '../services/referralApi'
import { commissionKinds, parseRatePercent, ratePercent } from '../domain/referral'
import { managementDate } from '../domain/platformManagement'
import { auth } from '../stores/auth'
import '../platform-management.css'

// 邀请返利后台：谁是谁名下、每一笔返利、推广人排行，以及返利比例。
const tab = ref('relations')
const referrerFilter = ref(null)
const reloadKey = ref(0)
function showCommissions(userId) { referrerFilter.value = userId; tab.value = 'commissions' }
function refresh() { reloadKey.value++; load() }

// 平台返利比例：充值烟火和开通会员分开设，只影响调整之后付款的订单，已经记好的返利保留当时的比例。每次调整都要写原因，留作记录。
const rate = ref(null)
const loading = ref(false)
const error = ref('')
const kind = ref('TOPUP')
const percent = ref('')
const note = ref('')
const saving = ref(false)
const formError = ref('')
const success = ref('')

const currentBp = computed(() => rate.value ? (kind.value === 'MEMBERSHIP' ? rate.value.membershipRateBp : rate.value.topupRateBp) : 0)

async function load() {
  loading.value = true
  error.value = ''
  try {
    rate.value = await referralApi.rate()
    percent.value = String(currentBp.value / 100)
  } catch (failure) {
    error.value = failure.message || '返利比例暂时无法读取'
  } finally {
    loading.value = false
  }
}

function chooseKind(value) {
  kind.value = value
  percent.value = String(currentBp.value / 100)
  formError.value = ''
  success.value = ''
}

async function save() {
  if (saving.value) return
  const parsed = parseRatePercent(percent.value)
  if (parsed.error) { formError.value = parsed.error; return }
  if (note.value.trim().length < 5) { formError.value = '请填写至少 5 字的调整原因。'; return }
  saving.value = true
  formError.value = ''
  success.value = ''
  try {
    rate.value = await referralApi.updateRate({ kind: kind.value, rateBp: parsed.bp, note: note.value.trim() })
    note.value = ''
    success.value = `${commissionKinds[kind.value]}返利已调整为 ${ratePercent(currentBp.value)}，之后付款的订单按新比例计算。`
  } catch (failure) {
    formError.value = failure.message || '保存失败，请刷新后核对'
  } finally {
    saving.value = false
  }
}

watch(() => auth.user?.id, load)
onMounted(load)
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><div class="management-breadcrumb"><span>平台管理</span><ChevronRight :size="13" /><span>邀请返利</span></div><h1>邀请返利</h1><p>被邀请商户充值烟火或开通会员付款后，按这里的比例给邀请人记现金返利。</p></div>
      <div class="management-heading-actions"><button class="mg-button" type="button" :disabled="loading" @click="refresh"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button></div>
    </header>

    <div class="management-tabs referral-tabs" role="tablist" aria-label="邀请返利">
      <button type="button" role="tab" :aria-selected="tab === 'relations'" :class="{ active: tab === 'relations' }" @click="tab = 'relations'"><Users :size="16" />邀请关系</button>
      <button type="button" role="tab" :aria-selected="tab === 'commissions'" :class="{ active: tab === 'commissions' }" @click="tab = 'commissions'"><ReceiptText :size="16" />返利明细</button>
      <button type="button" role="tab" :aria-selected="tab === 'promoters'" :class="{ active: tab === 'promoters' }" @click="tab = 'promoters'"><Trophy :size="16" />推广人排行</button>
      <button type="button" role="tab" :aria-selected="tab === 'rate'" :class="{ active: tab === 'rate' }" @click="tab = 'rate'"><Percent :size="16" />返利比例</button>
    </div>

    <ReferralRelationsPanel v-if="tab === 'relations'" :key="`relations-${reloadKey}`" @show-commissions="showCommissions" />
    <ReferralCommissionsPanel v-else-if="tab === 'commissions'" :key="`commissions-${reloadKey}`" :referrer-user-id="referrerFilter" @clear-referrer="referrerFilter = null" />
    <ReferralPromotersPanel v-else-if="tab === 'promoters'" :key="`promoters-${reloadKey}`" @show-commissions="showCommissions" />
    <template v-else>
      <div v-if="error" class="mg-state mg-error" role="alert"><Percent :size="30" /><h3>暂时无法读取</h3><p>{{ error }}</p><button class="mg-button" type="button" :disabled="loading" @click="load">重新读取</button></div>
      <template v-else-if="rate">
        <div class="management-stats referral-stats">
          <article><div><span>充值烟火返利</span><Percent :size="17" /></div><strong>{{ ratePercent(rate.topupRateBp) }}</strong><p>按充值的实付金额计算</p></article>
          <article><div><span>开通会员返利</span><Percent :size="17" /></div><strong>{{ ratePercent(rate.membershipRateBp) }}</strong><p>按会员订单的实付金额计算</p></article>
        </div>

        <section class="management-list referral-form" aria-labelledby="referral-rate-title">
          <div class="mg-list-caption"><h2 id="referral-rate-title">调整比例</h2><span>只影响调整之后付款的订单，只计一级</span></div>
          <form @submit.prevent="save">
            <div class="referral-kinds" role="radiogroup" aria-label="返利类型">
              <button v-for="(label, value) in commissionKinds" :key="value" class="mg-button" :class="{ 'mg-primary': kind === value }" type="button" role="radio" :aria-checked="kind === value" :disabled="saving" @click="chooseKind(value)">{{ label }}</button>
            </div>
            <label class="mg-note-label" for="referral-rate">{{ commissionKinds[kind] }}返利比例（%）<span>0–50，最多两位小数</span></label>
            <input id="referral-rate" v-model="percent" class="referral-input" inputmode="decimal" maxlength="5" :disabled="saving" />
            <label class="mg-note-label" for="referral-note">调整原因<span>必填 · 至少5字</span></label>
            <textarea id="referral-note" v-model="note" class="mg-note" rows="2" maxlength="200" :disabled="saving" placeholder="例如：十一月推广活动期间上调" />
            <p v-if="formError" class="mg-inline-error" role="alert">{{ formError }}</p>
            <p v-if="success" class="mg-muted" role="status">{{ success }}</p>
            <button class="mg-button mg-primary" type="submit" :disabled="saving">{{ saving ? '正在保存…' : '保存新比例' }}</button>
          </form>
        </section>

        <section class="management-list" aria-label="调整记录">
          <div class="mg-list-caption"><h2>调整记录</h2><span>最近 10 次</span></div>
          <div class="management-table-wrap">
            <table class="management-table">
              <thead><tr><th>时间</th><th>类型</th><th>比例</th><th>原因</th><th>操作人</th></tr></thead>
              <tbody>
                <tr v-for="item in rate.history" :key="`${item.kind}-${item.createdAt}`"><td class="mg-time">{{ managementDate(item.createdAt) }}</td><td>{{ commissionKinds[item.kind] || item.kind }}</td><td>{{ ratePercent(item.rateBp) }}</td><td>{{ item.note }}</td><td>{{ item.createdBy ? `运营人员 #${item.createdBy}` : '系统默认' }}</td></tr>
              </tbody>
            </table>
          </div>
        </section>
      </template>
      <div v-else class="mg-state" role="status"><RefreshCw :size="27" class="mg-spinning" /><p>正在读取…</p></div>
    </template>
  </div>
</template>

<style scoped>
.referral-tabs { margin-bottom: 20px; border: 1px solid var(--mg-line); border-radius: 7px; background: #fff; }
.referral-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); max-width: 640px; }
.referral-form form { display: grid; gap: 10px; max-width: 520px; padding: 16px 20px 20px; }
.referral-form form > .mg-button { justify-self: start; }
.referral-kinds { display: flex; flex-wrap: wrap; gap: 8px; }
.referral-input { min-height: 38px; max-width: 160px; padding: 0 12px; border: 1px solid var(--mg-line); border-radius: 5px; }
@media (max-width: 640px) { .referral-stats { grid-template-columns: minmax(0, 1fr); } }
</style>
