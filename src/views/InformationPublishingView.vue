<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { Eye, MapPin, Store, Send, Check, CheckCircle2, ExternalLink, Pencil, RefreshCw, X } from 'lucide-vue-next'
import { selectedStoreRecord } from '../stores/merchantContext'
import { useBusinessSubmission } from '../composables/useBusinessSubmission'
import { useMerchantApproval } from '../composables/useMerchantApproval'
import { businessCommunityService } from '../services/businessCommunity'
import { auth } from '../stores/auth'
import { informationDate, publicationState } from '../utils/informationPresentation'
import InformationContent from '../components/InformationContent.vue'

const categories = [
  { value: 'activity', label: '门店活动', description: '新品、到店体验与店内活动' },
  { value: 'cooperation', label: '商业合作', description: '品牌联动、渠道与资源合作' },
  { value: 'supply', label: '供求信息', description: '产品、服务与采购需求' },
  { value: 'notice', label: '商家公告', description: '营业调整与服务通知' },
]
const blankForm = () => ({ category: 'activity', title: '', merchantName: selectedStoreRecord.value?.name || '', region: '', summary: '', content: '', contactName: '', contactPhone: '', validUntil: '', declarationAccepted: false })
const editingPublication = ref(null)
const { approved } = useMerchantApproval()
const publications = ref([])
const publicationsLoading = ref(false)
const publicationsError = ref('')
const actionError = ref('')
const { form, editor, submitting, receipt, error, reset, submit, clearFieldError } = useBusinessSubmission(blankForm, values => editingPublication.value
  ? businessCommunityService.updatePublication(editingPublication.value.id, values)
  : businessCommunityService.submitInformation(values))
const previewOpen = ref(false)
const previewPanel = ref(null)
const categoryLabel = computed(() => categories.find(item => item.value === form.category)?.label || '商家信息')
const today = new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10)
let recordsVersion = 0

async function loadPublications() {
  if (!auth.isAuthenticated) return
  const attempt = ++recordsVersion
  publicationsLoading.value = true
  publicationsError.value = ''
  try {
    const result = await businessCommunityService.publications()
    if (attempt === recordsVersion) publications.value = result
  } catch (failure) {
    if (attempt === recordsVersion) publicationsError.value = failure.message || '发布记录暂时无法读取。'
  } finally {
    if (attempt === recordsVersion) publicationsLoading.value = false
  }
}

function editPublication(item) {
  editingPublication.value = item
  receipt.value = null
  error.value = ''
  Object.assign(form, {
    category: item.category, title: item.title, merchantName: item.merchantName, region: item.region,
    summary: item.summary || '', content: item.content, contactName: item.contactName || '',
    contactPhone: item.contactPhone || '', validUntil: item.validUntil || '', declarationAccepted: false,
  })
  nextTick(() => editor.value?.querySelector('input[name="information-title"]')?.focus())
}

async function takeOffline(item) {
  if (!window.confirm(item.status === 'PENDING_REVIEW' ? `撤回「${item.title}」的审核？撤回后可修改并重新提交。` : `下架「${item.title}」？下架后公开页面将无法查看。`)) return
  actionError.value = ''
  try {
    await businessCommunityService.takePublicationOffline(item.id)
    publications.value = publications.value.map(record => record.id === item.id ? { ...record, status: 'OFFLINE' } : record)
  } catch (failure) { actionError.value = failure.message || '下架失败，请重试。' }
}

watch(receipt, value => {
  if (value) loadPublications()
})
watch(() => `${auth.tenantId}:${auth.user?.id}`, () => {
  recordsVersion++
  publications.value = []
  publicationsError.value = ''
  publicationsLoading.value = false
  editingPublication.value = null
  actionError.value = ''
  loadPublications()
}, { immediate: true })
onBeforeUnmount(() => { recordsVersion++ })

async function preview() {
  previewOpen.value = !previewOpen.value
  if (previewOpen.value) {
    await nextTick()
    previewPanel.value?.focus()
  }
}

function startInformation() {
  reset()
  editingPublication.value = null
  previewOpen.value = false
}
</script>

<template>
  <div class="business-page information-page">
    <header class="business-heading">
      <div><h1>信息发布</h1><p>提交门店活动、商业合作与供求信息，经平台审核通过后公开展示。</p></div>
      <div class="business-heading-links"><RouterLink to="/information" class="business-link">查看信息广场</RouterLink><RouterLink :to="{ name: 'merchant-join' }" class="business-link">商家入驻</RouterLink></div>
    </header>

    <section class="business-records" aria-labelledby="my-publications-title">
      <div class="business-records-heading"><div><h2 id="my-publications-title">我的发布记录</h2></div><button type="button" class="business-record-refresh" :disabled="publicationsLoading" aria-label="刷新发布记录" @click="loadPublications"><RefreshCw :size="15" /></button></div>
      <p v-if="publicationsError" class="business-message-error" role="alert">{{ publicationsError }}</p>
      <p v-else-if="publicationsLoading && !publications.length" class="business-record-empty" role="status">正在读取发布记录…</p>
      <p v-else-if="!publications.length" class="business-record-empty">提交的信息与审核进度会显示在这里。</p>
      <ul v-else class="business-record-list">
        <li v-for="item in publications" :key="item.id">
          <div class="business-record-main">
            <span class="business-category-tag">{{ categories.find(category => category.value === item.category)?.label || '商家信息' }}</span>
            <strong>{{ item.title }}</strong>
            <small>{{ item.merchantName }} · {{ item.region }} · {{ informationDate(item.publishedAt || item.createdAt) }}</small>
            <p v-if="item.reviewNote" class="business-review-note">{{ item.status === 'REJECTED' ? '驳回原因' : '平台处理意见' }}：{{ item.reviewNote }}</p>
            <p v-if="item.platformBlocked" class="business-review-note">该信息已由平台下线，请联系平台处理。</p>
          </div>
          <span class="business-record-status" :class="publicationState(item).className">{{ publicationState(item).label }}</span>
          <div class="business-record-actions">
            <RouterLink v-if="publicationState(item).visible" :to="`/information/${item.id}`" target="_blank" class="business-record-icon" :aria-label="`打开 ${item.title} 的公开页面`" title="打开公开页面"><ExternalLink :size="15" /></RouterLink>
            <button v-if="!item.platformBlocked" type="button" class="business-record-icon" :disabled="submitting" :aria-label="`编辑 ${item.title} 并重新提交审核`" title="编辑并重新提交审核" @click="editPublication(item)"><Pencil :size="15" /></button>
            <button v-if="['PUBLISHED', 'PENDING_REVIEW'].includes(item.status)" type="button" class="business-record-icon" :disabled="submitting" :aria-label="`${item.status === 'PENDING_REVIEW' ? '撤回' : '下架'} ${item.title}`" :title="item.status === 'PENDING_REVIEW' ? '撤回审核' : '下架'" @click="takeOffline(item)"><X :size="16" /></button>
          </div>
        </li>
      </ul>
      <p v-if="actionError" class="business-message-error" role="alert">{{ actionError }}</p>
    </section>

    <section v-if="receipt" class="business-submission-result" role="status">
      <CheckCircle2 :size="36" aria-hidden="true" /><h2>{{ editingPublication ? '修改已提交审核' : '信息已提交审核' }}</h2><p>「{{ receipt.title }}」已保存，正在等待平台审核，通过后才会显示在信息广场。可在上方发布记录中查看审核结果。</p><span>提交编号：{{ receipt.id }}</span><RouterLink to="/information" class="business-link">浏览信息广场</RouterLink>
      <button class="business-button business-button-primary" type="button" @click="startInformation">继续发布信息</button>
    </section>
    <template v-else>
      <p v-if="approved === false" class="business-message-error" role="status">通过商家入驻审核后才能发布信息。<RouterLink :to="{ name: 'merchant-join' }" class="business-link">去加入商户联盟</RouterLink></p>
      <ol class="business-process" aria-label="信息发布流程"><li class="is-current" aria-current="step"><span>01</span><strong>填写并提交</strong></li><li><span>02</span><strong>平台审核</strong></li><li><span>03</span><strong>通过后公开</strong></li></ol>
      <div class="business-editor-layout">
        <form ref="editor" class="business-form" :aria-busy="submitting" @submit.prevent="submit" @input="clearFieldError">
          <fieldset class="business-form-fields" :disabled="submitting">
            <section class="business-form-section">
              <div class="business-section-title"><span>01</span><h2>发布什么信息</h2></div>
              <fieldset class="business-category-field"><legend>信息类型</legend><div class="business-categories"><label v-for="category in categories" :key="category.value" :class="{ selected: form.category === category.value }"><input v-model="form.category" type="radio" name="information-category" :value="category.value" /><span><strong>{{ category.label }}</strong><small>{{ category.description }}</small></span><Check v-if="form.category === category.value" :size="14" aria-hidden="true" /></label></div></fieldset>
              <label class="business-field"><span>信息标题<b>*</b><small>{{ form.title.length }}/60</small></span><input v-model="form.title" name="information-title" maxlength="60" required placeholder="用一句话说明活动或合作内容" /></label>
              <label class="business-field"><span>信息摘要<small>{{ form.summary.length }}/120</small></span><textarea v-model="form.summary" name="information-summary" maxlength="120" rows="2" placeholder="补充适用人群、主要内容与亮点，便于快速了解" /></label>
              <label class="business-field"><span>详细内容<b>*</b><small>{{ form.content.length }}/5000</small></span><textarea v-model="form.content" class="business-content-input" name="information-content" maxlength="5000" rows="8" required placeholder="说明具体内容、参与方式、时间安排与合作条件。请使用真实、清晰的表述。" /></label>
            </section>
            <section class="business-form-section">
              <div class="business-section-title"><span>02</span><h2>商家与联系信息</h2></div>
              <div class="business-field-grid">
                <label class="business-field"><span>商家名称<b>*</b></span><input v-model="form.merchantName" name="merchant-name" maxlength="80" required placeholder="填写对外展示的商家名称" /></label>
                <label class="business-field"><span>所在地区<b>*</b></span><input v-model="form.region" name="merchant-region" maxlength="80" required placeholder="例如：浙江省嘉兴市南湖区" /></label>
                <label class="business-field"><span>联系人</span><input v-model="form.contactName" name="contact-name" maxlength="40" autocomplete="name" placeholder="填写联系人姓名" /></label>
                <label class="business-field"><span>联系电话</span><input v-model="form.contactPhone" name="contact-phone" type="tel" inputmode="tel" autocomplete="tel" maxlength="20" pattern="[0-9+\(\)\- ]{6,20}" placeholder="手机号或固定电话" title="请填写 6 至 20 位电话号码，可包含加号、括号、空格和连字符" /></label>
              </div>
              <label class="business-field business-date-field"><span>信息有效期至</span><input v-model="form.validUntil" name="valid-until" type="date" :min="today" /><small>不填写表示长期有效。</small></label>
            </section>
            <div class="business-declaration"><label><input v-model="form.declarationAccepted" name="information-declaration" type="checkbox" required /><span>我确认发布内容真实、合法，已获得所填联系方式的公开授权，已阅读<a href="/policies/publishing" target="_blank" rel="noopener">《公开信息发布规则》</a>和<a href="/policies/privacy" target="_blank" rel="noopener">《隐私协议》</a>，并同意提交平台审核，通过后按规则公开展示。</span></label></div>
          </fieldset>
          <p v-if="error" class="business-form-feedback business-message-error" role="alert">{{ error }}</p>
          <footer class="business-form-actions"><span>{{ editingPublication ? '修改后需重新审核，审核期间不公开展示' : '提交后待审核，审核通过才会公开展示' }}</span><div><button class="business-button" type="button" :disabled="submitting" @click="preview"><Eye :size="16" aria-hidden="true" />{{ previewOpen ? '收起完整预览' : '预览信息' }}</button><button class="business-button business-button-primary" type="submit" :disabled="submitting || approved === false"><Send :size="16" aria-hidden="true" />{{ submitting ? '正在提交…' : editingPublication ? '重新提交审核' : '提交审核' }}</button></div></footer>
        </form>

        <aside class="business-preview-column">
          <section ref="previewPanel" class="business-preview" :class="{ 'is-expanded': previewOpen }" tabindex="-1" aria-label="信息预览">
            <div class="business-preview-label"><Eye :size="15" aria-hidden="true" /><span>{{ previewOpen ? '完整信息预览' : '信息预览' }}</span></div>
            <article>
              <span class="business-category-tag">{{ categoryLabel }}</span>
              <h2>{{ form.title.trim() || '信息标题' }}</h2>
              <p v-if="form.summary.trim()" class="business-preview-summary">{{ form.summary.trim() }}</p>
              <div class="business-publisher"><span class="business-publisher-mark"><Store :size="19" aria-hidden="true" /></span><div><strong>{{ form.merchantName.trim() || '商家名称' }}</strong><span><MapPin :size="12" aria-hidden="true" />{{ form.region.trim() || '所在地区' }}</span></div></div>
              <div class="business-preview-body" :class="{ 'is-collapsed': !previewOpen }"><InformationContent :content="form.content.trim() || '填写详细内容后，可在此查看展示效果。'" /></div>
              <dl class="business-preview-details"><div><dt>有效期</dt><dd>{{ form.validUntil || '长期有效' }}</dd></div><div v-if="form.contactName.trim()"><dt>联系人</dt><dd>{{ form.contactName.trim() }}</dd></div><div v-if="form.contactPhone.trim()"><dt>联系电话</dt><dd>{{ form.contactPhone.trim() }}</dd></div></dl>
            </article>
          </section>
          <section class="business-writing-guide"><h3>发布须知</h3><p>写清时间、地点和参与方式；合作信息说明资源与需求；报价、优惠和服务范围应准确。</p><p>新信息及修改后的内容均须经平台审核，通过后公开展示；被驳回后可根据原因修改并重新提交。请勿发布虚假信息或填写无关个人资料。</p></section>
        </aside>
      </div>
    </template>
  </div>
</template>
