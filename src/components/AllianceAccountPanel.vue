<script setup>
import { reactive, ref } from 'vue'
import AppSelect from './AppSelect.vue'
import { accountStatuses, accountTypes, allianceApi } from '../services/allianceApi'

const emit = defineEmits(['saved'])
const account = ref(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const form = reactive({ accountType: 'CORPORATE', accountName: '', bankName: '', branchName: '', accountNumber: '', financeContactName: '', financeContactPhone: '' })

function fill(value) {
  account.value = value || null
  Object.assign(form, {
    accountType: value?.accountType || 'CORPORATE',
    accountName: value?.accountName || '',
    bankName: value?.bankName || '',
    branchName: value?.branchName || '',
    accountNumber: '',
    financeContactName: value?.financeContactName || '',
    financeContactPhone: value?.financeContactPhone || '',
  })
}

async function load() {
  loading.value = true
  error.value = ''
  try { fill(await allianceApi.account()) }
  catch (failure) { error.value = failure.message || '收款账户暂时无法读取。' }
  finally { loading.value = false }
}

async function save() {
  if (saving.value) return
  if (account.value?.status === 'VERIFIED' && !window.confirm('修改后需要平台重新核对，核对完成前不能确认新的对账单。已经确认的对账单仍按原账户打款。确定修改吗？')) return
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    fill(await allianceApi.saveAccount({ ...form, accountNumber: form.accountNumber.replace(/\s/g, '') || null }))
    notice.value = '已保存，等待平台核对。'
    emit('saved')
  } catch (failure) {
    error.value = failure.message || '保存失败，请稍后再试。'
  } finally {
    saving.value = false
  }
}

load()
</script>

<template>
  <section class="alliance-panel" aria-labelledby="alliance-account-title">
    <div class="alliance-panel-heading">
      <div><h2 id="alliance-account-title">收款账户</h2><p class="alliance-muted">平台按月汇总结算，线下打款到这个账户。户名须与入驻时的主体名称一致；个体户可以用营业执照上的经营者本人账户。</p></div>
      <span v-if="account" class="alliance-tag" :class="{ 'is-active': account.status === 'VERIFIED', 'is-warning': account.status === 'PENDING_VERIFY', 'is-error': account.status === 'REJECTED' }">{{ accountStatuses[account.status] }}</span>
    </div>
    <p v-if="account?.status === 'REJECTED' && account.reviewNote" class="alliance-error" role="alert">平台意见：{{ account.reviewNote }}</p>
    <p v-if="notice" class="alliance-notice" role="status">{{ notice }}</p>
    <p v-if="error" class="alliance-error" role="alert">{{ error }}</p>
    <p v-if="loading && !account" class="alliance-muted" role="status">正在读取…</p>
    <form v-else class="alliance-form alliance-account-form" @submit.prevent="save">
      <label>账户类型<AppSelect v-model="form.accountType" name="alliance-account-type" required><option v-for="(label, value) in accountTypes" :key="value" :value="value">{{ label }}</option></AppSelect></label>
      <label>户名<input v-model.trim="form.accountName" maxlength="120" required :placeholder="form.accountType === 'CORPORATE' ? '与营业执照上的主体名称一致' : '营业执照上的经营者本人姓名'" /></label>
      <label>开户银行<input v-model.trim="form.bankName" maxlength="80" required placeholder="例如 中国工商银行" /></label>
      <label>开户支行<input v-model.trim="form.branchName" maxlength="120" required placeholder="例如 嘉兴南湖支行" /></label>
      <label>银行账号<input v-model="form.accountNumber" inputmode="numeric" autocomplete="off" maxlength="40" :required="!account" :placeholder="account ? `已保存尾号 ${account.accountNumberLast4}，不改请留空` : '只填数字'" /><small>账号加密保存，页面上只显示后 4 位。</small></label>
      <label>财务联系人<input v-model.trim="form.financeContactName" maxlength="40" required /></label>
      <label>联系电话<input v-model.trim="form.financeContactPhone" maxlength="20" required inputmode="tel" /></label>
      <div class="alliance-dialog-actions"><button type="submit" class="business-button business-button-primary" :disabled="saving">{{ saving ? '保存中…' : account ? '保存修改' : '保存' }}</button></div>
    </form>
  </section>
</template>
