<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ArrowRight, Check, CheckCircle2, Clock3, FileCheck2, Info, LockKeyhole, RefreshCw, ShieldCheck } from 'lucide-vue-next'
import { auth } from '../stores/auth'
import { platformManagementApi as api } from '../services/platformManagement'
import { identityValidation, managementDate, managementStatuses } from '../domain/platformManagement'
import '../platform-management.css'

const application = ref(null)
const form = reactive({ legalName: '', idNumber: '', declarationAccepted: false })
const loading = ref(false)
const submitting = ref(false)
const loaded = ref(false)
const error = ref('')
const formError = ref('')
const notice = ref('')
const canSubmit = computed(() => loaded.value && !loading.value && (!application.value || application.value.status === 'REJECTED'))
let generation = 0

async function load() {
  const attempt = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = await api.myIdentity()
    if (attempt === generation) { application.value = result; loaded.value = true }
  } catch (failure) { if (attempt === generation) { error.value = failure.message || '实名状态暂时无法读取。'; loaded.value = false } }
  finally { if (attempt === generation) loading.value = false }
}

async function submit() {
  if (submitting.value || !canSubmit.value) return
  formError.value = identityValidation(form)
  if (formError.value) return
  const attempt = generation
  submitting.value = true
  notice.value = ''
  try {
    const result = await api.submitIdentity({ legalName: form.legalName.trim(), idNumber: form.idNumber.trim().toUpperCase(), declarationAccepted: form.declarationAccepted })
    if (attempt !== generation) return
    application.value = result
    Object.assign(form, { legalName: '', idNumber: '', declarationAccepted: false })
    notice.value = '申请已保存，平台将进行人工核查。你可以随时在本页查看审核进度。'
  } catch (failure) {
    if (attempt === generation) formError.value = failure.status === 0 || failure.status >= 500
      ? `${failure.message || '提交请求暂时未完成'}。请刷新实名状态确认结果后再尝试提交。`
      : failure.message || '提交失败，请检查资料后重试。'
  } finally { if (attempt === generation) submitting.value = false }
}

watch(() => [auth.user?.id, auth.tenantId], () => {
  generation++
  application.value = null
  loaded.value = false
  submitting.value = false
  formError.value = ''; notice.value = ''
  Object.assign(form, { legalName: '', idNumber: '', declarationAccepted: false })
  if (auth.isAuthenticated) load()
}, { immediate: true })
onBeforeUnmount(() => { generation++; form.legalName = ''; form.idNumber = '' })
</script>

<template>
  <div class="management-page identity-page">
    <header class="management-heading"><div><div class="management-breadcrumb"><span>账户管理</span><ArrowRight :size="13" /><span>用户实名</span></div><h1>用户实名</h1><p>提交本人身份信息，完成平台人工实名审核。</p></div><button class="mg-button" type="button" :disabled="loading || submitting" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新实名状态</button></header>
    <div class="identity-layout">
      <main class="identity-main">
        <section class="identity-status"><div class="identity-status-icon"><ShieldCheck :size="25" /></div><div><span class="mg-muted">当前实名状态</span><strong>{{ loading ? '正在查询…' : error ? '暂时无法确认' : application ? managementStatuses[application.status] : '尚未提交' }}</strong><p>{{ application?.status === 'PENDING_REVIEW' ? '资料已提交，等待平台人工核查，请勿重复提交。' : application?.status === 'VERIFIED' ? '你的实名资料已通过平台人工审核。' : application?.status === 'REJECTED' ? '请根据审核意见核对资料后重新提交。' : '审核状态与当前登录账号关联，可在本页随时查看。' }}</p></div><span v-if="application" class="mg-status" :class="`mg-${application.status.toLowerCase()}`"><i />{{ managementStatuses[application.status] }}</span></section>
        <div v-if="notice" class="mg-feedback success" role="status"><CheckCircle2 :size="18" />{{ notice }}</div>
        <div v-if="error" class="mg-feedback mg-inline-error" role="alert"><Info :size="18" />{{ error }}</div>
        <section v-if="application" class="identity-record"><div class="mg-list-caption"><h2>已提交资料</h2><span class="mg-mono">申请 #{{ application.id }}</span></div><dl class="mg-detail-grid"><div><dt>姓名</dt><dd>{{ application.legalNameMasked }}</dd></div><div><dt>居民身份证号码</dt><dd class="mg-mono">{{ application.idNumberMasked }}</dd></div><div><dt>提交时间</dt><dd>{{ managementDate(application.submittedAt) }}</dd></div><div><dt>审核时间</dt><dd>{{ managementDate(application.reviewedAt) }}</dd></div></dl><div v-if="application.reviewNote" class="identity-review-note"><strong>平台审核意见</strong><p>{{ application.reviewNote }}</p></div></section>
        <form v-if="canSubmit" class="identity-form" @submit.prevent="submit"><header><h2>{{ application ? '重新提交实名资料' : '填写本人身份资料' }}</h2><p>请核对姓名和证件号码，确保与本人有效证件一致。</p></header><div class="identity-fields"><label for="identity-legal-name">真实姓名<span>*</span><input id="identity-legal-name" v-model="form.legalName" name="legal-name" autocomplete="off" maxlength="40" placeholder="请输入证件上的姓名" required :disabled="submitting" /></label><label for="identity-id-number">居民身份证号码<span>*</span><input id="identity-id-number" v-model="form.idNumber" name="id-number" autocomplete="off" autocapitalize="characters" spellcheck="false" maxlength="18" placeholder="请输入18位居民身份证号码" required :disabled="submitting" /><small>仅用于实名资料核查，提交后默认脱敏展示。</small></label><div class="identity-account"><span>关联账号</span><strong>{{ auth.user?.name || '当前登录账号' }}</strong><small>资料仅提交至当前账号，不可代他人申请。</small></div><label class="identity-consent"><input v-model="form.declarationAccepted" type="checkbox" required :disabled="submitting" /><span>我确认以上为本人真实、准确的身份信息，并同意平台将其用于本次实名资料审核。</span></label><p v-if="formError" class="mg-inline-error" role="alert">{{ formError }}</p></div><footer><span><LockKeyhole :size="14" />敏感字段在服务端加密保存</span><button class="mg-button mg-primary" type="submit" :disabled="submitting"><RefreshCw v-if="submitting" :size="15" class="mg-spinning" /><Check v-else :size="15" />{{ submitting ? '正在提交…' : '提交实名申请' }}</button></footer></form>
      </main>
      <aside class="identity-aside"><section><h2>实名审核流程</h2><ol class="identity-steps"><li><span>01</span><div><strong>提交本人信息</strong><p>填写真实姓名与居民身份证号。</p></div></li><li><span>02</span><div><strong>平台人工核查</strong><p>平台人员核对资料，必要时联系你补充证明。</p></div></li><li><span>03</span><div><strong>查看审核结果</strong><p>本页显示处理结果；被驳回后可修正重提。</p></div></li></ol></section><section><h2><LockKeyhole :size="17" />资料如何保护</h2><ul class="identity-protection"><li><Check :size="15" />姓名、证件号服务端加密存储</li><li><Check :size="15" />默认仅展示脱敏资料</li><li><Check :size="15" />完整资料仅限平台运营核查</li><li><Check :size="15" />查阅与审核操作留存记录</li></ul><p class="identity-manual-note"><FileCheck2 :size="18" /><span>目前为平台人工审核，尚未接入第三方权威身份核验服务。</span></p></section><p class="identity-help"><Clock3 :size="15" />人工审核处理时间以实际进度为准。</p></aside>
    </div>
  </div>
</template>
