<script setup>
import { onMounted, ref, watch } from 'vue'
import { ChevronRight, Percent, RefreshCw } from 'lucide-vue-next'
import { referralApi } from '../services/referralApi'
import { parseRatePercent, ratePercent } from '../domain/referral'
import { managementDate } from '../domain/platformManagement'
import { auth } from '../stores/auth'
import '../platform-management.css'

// 平台返利比例：只影响调整之后到账的充值，已经记好的返利保留当时的比例。每次调整都要写原因，留作记录。
const rate = ref(null)
const loading = ref(false)
const error = ref('')
const percent = ref('')
const note = ref('')
const saving = ref(false)
const formError = ref('')
const success = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    rate.value = await referralApi.rate()
    percent.value = String(rate.value.rateBp / 100)
  } catch (failure) {
    error.value = failure.message || '返利比例暂时无法读取'
  } finally {
    loading.value = false
  }
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
    rate.value = await referralApi.updateRate({ rateBp: parsed.bp, note: note.value.trim() })
    note.value = ''
    success.value = `返利比例已调整为 ${ratePercent(rate.value.rateBp)}，之后到账的充值按新比例计算。`
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
      <div><div class="management-breadcrumb"><span>平台管理</span><ChevronRight :size="13" /><span>邀请返利</span></div><h1>邀请返利</h1><p>被邀请商户充值烟火到账后，按这里的比例给邀请人记现金返利。</p></div>
      <div class="management-heading-actions"><button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button></div>
    </header>

    <div v-if="error" class="mg-state mg-error" role="alert"><Percent :size="30" /><h3>暂时无法读取</h3><p>{{ error }}</p><button class="mg-button" type="button" :disabled="loading" @click="load">重新读取</button></div>
    <template v-else-if="rate">
      <div class="management-stats referral-stats"><article><div><span>当前返利比例</span><Percent :size="17" /></div><strong>{{ ratePercent(rate.rateBp) }}</strong><p>按被邀请商户充值的实付金额计算，只计一级</p></article></div>

      <section class="management-list referral-form" aria-labelledby="referral-rate-title">
        <div class="mg-list-caption"><h2 id="referral-rate-title">调整比例</h2><span>只影响调整之后到账的充值</span></div>
        <form @submit.prevent="save">
          <label class="mg-note-label" for="referral-rate">返利比例（%）<span>0–50，最多两位小数</span></label>
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
            <thead><tr><th>时间</th><th>比例</th><th>原因</th><th>操作人</th></tr></thead>
            <tbody>
              <tr v-for="item in rate.history" :key="item.createdAt"><td class="mg-time">{{ managementDate(item.createdAt) }}</td><td>{{ ratePercent(item.rateBp) }}</td><td>{{ item.note }}</td><td>{{ item.createdBy ? `运营人员 #${item.createdBy}` : '系统默认' }}</td></tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
    <div v-else class="mg-state" role="status"><RefreshCw :size="27" class="mg-spinning" /><p>正在读取…</p></div>
  </div>
</template>

<style scoped>
.referral-stats { grid-template-columns: minmax(0, 1fr); max-width: 360px; }
.referral-form form { display: grid; gap: 10px; max-width: 520px; padding: 16px 20px 20px; }
.referral-form .mg-button { justify-self: start; }
.referral-input { min-height: 38px; max-width: 160px; padding: 0 12px; border: 1px solid var(--mg-line); border-radius: 5px; }
</style>
