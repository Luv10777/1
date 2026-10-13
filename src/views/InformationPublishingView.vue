<script setup>
import { computed, nextTick, ref } from 'vue'
import { FilePenLine, Eye, MapPin, Store, ArrowRight, Send, Check, CheckCircle2 } from 'lucide-vue-next'
import { selectedStoreRecord } from '../stores/merchantContext'
import { useBusinessSubmission } from '../composables/useBusinessSubmission'
import { businessCommunityService } from '../services/businessCommunity'

const categories = [
  { value: 'activity', label: '门店活动', description: '新品、到店体验与店内活动' },
  { value: 'cooperation', label: '商业合作', description: '品牌联动、渠道与资源合作' },
  { value: 'supply', label: '供求信息', description: '产品、服务与采购需求' },
  { value: 'notice', label: '商家公告', description: '营业调整与服务通知' },
]
const blankForm = () => ({ category: 'activity', title: '', merchantName: selectedStoreRecord.value?.name || '', region: '', summary: '', content: '', contactName: '', contactPhone: '', validUntil: '', declarationAccepted: false })
const { form, editor, submitting, receipt, error, reset, submit, clearFieldError } = useBusinessSubmission(blankForm, businessCommunityService.submitInformation)
const previewOpen = ref(false)
const previewPanel = ref(null)
const categoryLabel = computed(() => categories.find(item => item.value === form.category)?.label || '商家信息')
const today = new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10)

async function preview() {
  previewOpen.value = !previewOpen.value
  if (previewOpen.value) {
    await nextTick()
    previewPanel.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

function startInformation() {
  reset()
  previewOpen.value = false
}
</script>

<template>
  <div class="business-page information-page">
    <header class="business-heading">
      <div><p class="business-overline"><FilePenLine :size="15" aria-hidden="true" />商家信息服务</p><h1>信息发布</h1><p>发布门店活动、商业合作与供求信息，连接本地商家与合作机会。</p></div>
      <RouterLink to="/merchant-alliance" class="business-link">商家入驻<ArrowRight :size="15" aria-hidden="true" /></RouterLink>
    </header>

    <section v-if="receipt" class="business-submission-result" role="status">
      <CheckCircle2 :size="36" aria-hidden="true" /><h2>信息已提交审核</h2><p>审核通过后，信息将对外展示。请保持联系方式畅通。</p><span>提交编号：{{ receipt.id }}</span>
      <button class="business-button business-button-primary" type="button" @click="startInformation">继续发布信息<ArrowRight :size="15" aria-hidden="true" /></button>
    </section>
    <template v-else>
      <ol class="business-process" aria-label="信息发布流程"><li class="is-current"><span>01</span><strong>填写信息</strong></li><li><span>02</span><strong>平台审核</strong></li><li><span>03</span><strong>对外展示</strong></li></ol>
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
            <div class="business-declaration"><label><input v-model="form.declarationAccepted" name="information-declaration" type="checkbox" required /><span>我确认发布内容真实、合法，已获得所填联系方式的使用授权，并接受平台审核。</span></label></div>
          </fieldset>
          <p v-if="error" class="business-form-feedback business-message-error" role="alert">{{ error }}</p>
          <footer class="business-form-actions"><span>审核通过后对外展示</span><div><button class="business-button" type="button" :disabled="submitting" @click="preview"><Eye :size="16" aria-hidden="true" />{{ previewOpen ? '收起完整预览' : '预览信息' }}</button><button class="business-button business-button-primary" type="submit" :disabled="submitting"><Send :size="16" aria-hidden="true" />{{ submitting ? '正在提交…' : '提交审核' }}</button></div></footer>
        </form>

        <aside class="business-preview-column">
          <section ref="previewPanel" class="business-preview" :class="{ 'is-expanded': previewOpen }" aria-label="信息预览">
            <div class="business-preview-label"><Eye :size="15" aria-hidden="true" /><span>{{ previewOpen ? '完整信息预览' : '信息预览' }}</span></div>
            <article>
              <span class="business-category-tag">{{ categoryLabel }}</span>
              <h2>{{ form.title.trim() || '信息标题' }}</h2>
              <p v-if="form.summary.trim()" class="business-preview-summary">{{ form.summary.trim() }}</p>
              <div class="business-publisher"><span class="business-publisher-mark"><Store :size="19" aria-hidden="true" /></span><div><strong>{{ form.merchantName.trim() || '商家名称' }}</strong><span><MapPin :size="12" aria-hidden="true" />{{ form.region.trim() || '所在地区' }}</span></div></div>
              <div class="business-preview-body" :class="{ 'is-collapsed': !previewOpen }">{{ form.content.trim() || '填写详细内容后，可在此查看展示效果。' }}</div>
              <dl class="business-preview-details"><div><dt>有效期</dt><dd>{{ form.validUntil || '长期有效' }}</dd></div><div v-if="form.contactName.trim()"><dt>联系人</dt><dd>{{ form.contactName.trim() }}</dd></div><div v-if="form.contactPhone.trim()"><dt>联系电话</dt><dd>{{ form.contactPhone.trim() }}</dd></div></dl>
            </article>
          </section>
          <section class="business-writing-guide"><h3>发布须知</h3><p>写清时间、地点和参与方式；合作信息说明资源与需求；报价、优惠和服务范围应准确。</p><p>平台审核信息内容与展示资料。请勿发布虚假信息或填写无关个人资料。</p></section>
        </aside>
      </div>
    </template>
  </div>
</template>
