<script setup>
import { onMounted, ref, watch } from 'vue'
import { Store, ArrowLeft, ArrowRight, Send, FileCheck2, FilePenLine, CheckCircle2, RefreshCw } from 'lucide-vue-next'
import { selectedStoreRecord } from '../stores/merchantContext'
import { useBusinessSubmission } from '../composables/useBusinessSubmission'
import { businessCommunityService } from '../services/businessCommunity'
import { auth } from '../stores/auth'

const industries = ['餐饮 / 茶饮', '零售 / 生活服务', '家居 / 家装', '美妆 / 个护', '文旅 / 休闲', '企业服务', '其他']
const blankForm = () => ({ merchantName: selectedStoreRecord.value?.name || '', companyName: '', industry: '', region: '', creditCode: '', contactName: '', contactPhone: '', introduction: '', declarationAccepted: false })
const { form, editor, submitting, receipt, error, reset, submit, clearFieldError } = useBusinessSubmission(blankForm, businessCommunityService.applyForAlliance)
const applications = ref([])
const applicationsLoading = ref(false)
const applicationsError = ref('')
const statusLabel = { PENDING_REVIEW: '审核中', APPROVED: '已通过', REJECTED: '需补充资料', SUSPENDED: '入驻已暂停' }

async function loadApplications() {
  applicationsLoading.value = true
  applicationsError.value = ''
  try { applications.value = await businessCommunityService.applications() }
  catch (failure) { applicationsError.value = failure.message || '申请记录暂时无法读取。' }
  finally { applicationsLoading.value = false }
}

onMounted(() => { if (auth.isAuthenticated) loadApplications() })
watch(() => auth.isAuthenticated, signedIn => {
  if (signedIn) loadApplications()
  else { applications.value = []; applicationsError.value = '' }
})
</script>

<template>
  <div class="business-page merchant-alliance-page merchant-join-page">
    <header class="business-heading"><div><p class="business-overline"><Store :size="15" aria-hidden="true" />一方志 · 商户联盟</p><h1>成为一方商家</h1><p>提交真实经营资料，入驻后管理商品、接收订单与发布商家信息。</p></div><RouterLink to="/merchant-alliance" class="business-link"><ArrowLeft :size="15" aria-hidden="true" />返回商户联盟</RouterLink></header>
    <section v-if="auth.isAuthenticated" class="business-records" aria-labelledby="merchant-applications-title">
      <div class="business-records-heading"><div><span>APPLICATION STATUS</span><h2 id="merchant-applications-title">我的入驻申请</h2></div><button type="button" class="business-record-refresh" :disabled="applicationsLoading" aria-label="刷新申请状态" @click="loadApplications"><RefreshCw :size="15" /></button></div>
      <p v-if="applicationsError" class="business-message-error" role="alert">{{ applicationsError }}</p>
      <p v-else-if="applicationsLoading && !applications.length" class="business-record-empty" role="status">正在读取申请记录…</p>
      <p v-else-if="!applications.length" class="business-record-empty">提交申请后，可在这里查看平台审核状态和意见。</p>
      <ul v-else class="business-record-list"><li v-for="application in applications" :key="application.id"><div class="business-record-main"><strong>{{ application.merchantName }} · {{ application.companyName }}</strong><small>{{ application.industry }} · {{ application.region }} · {{ new Date(application.createdAt).toLocaleDateString('zh-CN') }}</small><small v-if="application.reviewNote" class="business-review-note">平台意见：{{ application.reviewNote }}</small></div><span class="business-record-status" :class="`status-${application.status.toLowerCase()}`">{{ statusLabel[application.status] || application.status }}</span></li></ul>
    </section>
    <section v-if="receipt" class="business-submission-result" role="status"><CheckCircle2 :size="36" aria-hidden="true" /><h2>入驻申请已提交</h2><p>平台将审核经营主体与商家资料。请保持联系人电话畅通。</p><span>申请编号：{{ receipt.id }}</span><RouterLink to="/information-publishing" class="business-button business-button-primary">发布商家信息<ArrowRight :size="15" aria-hidden="true" /></RouterLink><button class="business-link" type="button" @click="reset">申请其他商家入驻</button></section>
    <template v-else>
      <ol class="business-process" aria-label="商家入驻流程"><li class="is-current"><span>01</span><strong>填写申请</strong></li><li><span>02</span><strong>资料审核</strong></li><li><span>03</span><strong>入驻联盟</strong></li></ol>
      <div class="business-editor-layout">
        <section v-if="!auth.isAuthenticated" class="merchant-application-login"><span class="merchant-alliance-seal"><img src="/images/brand/yifangzhi-mark.png" alt="" /></span><h3>为每一方商家立传</h3><p>登录后填写经营主体与联系人资料，提交入驻申请并查看审核进度。</p><RouterLink :to="{ name: 'login', query: { redirect: '/merchant-alliance' } }" class="business-button business-button-primary">登录并申请入驻<ArrowRight :size="16" aria-hidden="true" /></RouterLink><span>购买上方商品无需登录</span></section>
        <form v-else ref="editor" class="business-form" :aria-busy="submitting" @submit.prevent="submit" @input="clearFieldError">
          <fieldset class="business-form-fields" :disabled="submitting">
            <section class="business-form-section">
              <div class="business-section-title"><span>01</span><h2>商家基本资料</h2></div>
              <div class="business-field-grid">
                <label class="business-field"><span>商家名称<b>*</b></span><input v-model="form.merchantName" name="merchant-name" maxlength="80" required placeholder="门店或品牌的对外名称" /></label>
                <label class="business-field"><span>主体名称<b>*</b></span><input v-model="form.companyName" name="company-name" maxlength="120" required placeholder="营业执照上的企业或个体户名称" /></label>
                <label class="business-field"><span>经营行业<b>*</b></span><select v-model="form.industry" name="merchant-industry" required><option value="" disabled>请选择经营行业</option><option v-for="industry in industries" :key="industry" :value="industry">{{ industry }}</option></select></label>
                <label class="business-field"><span>经营地区<b>*</b></span><input v-model="form.region" name="merchant-region" maxlength="80" required placeholder="省、市、区及主要经营范围" /></label>
              </div>
              <label class="business-field"><span>统一社会信用代码<b>*</b></span><input v-model.trim="form.creditCode" name="credit-code" minlength="18" maxlength="18" required pattern="[0-9ABCDEFGHJKLMNPQRTUWXY]{18}" placeholder="填写营业执照上的 18 位代码" title="请输入 18 位统一社会信用代码" @input="form.creditCode = form.creditCode.toUpperCase()" /><small>请与营业执照上的主体名称和统一社会信用代码保持一致。</small></label>
              <label class="business-field"><span>商家介绍<b>*</b><small>{{ form.introduction.length }}/1000</small></span><textarea v-model="form.introduction" name="merchant-introduction" maxlength="1000" rows="4" required placeholder="介绍主营产品、服务特色与希望开展的合作" /></label>
            </section>
            <section class="business-form-section"><div class="business-section-title"><span>02</span><h2>入驻联系人</h2></div><div class="business-field-grid"><label class="business-field"><span>联系人姓名<b>*</b></span><input v-model="form.contactName" name="contact-name" autocomplete="name" maxlength="40" required placeholder="负责人或授权联系人" /></label><label class="business-field"><span>联系电话<b>*</b></span><input v-model="form.contactPhone" name="contact-phone" type="tel" inputmode="tel" autocomplete="tel" maxlength="20" required pattern="[0-9+\(\)\- ]{6,20}" placeholder="手机号或固定电话" title="请填写 6 至 20 位电话号码，可包含加号、括号、空格和连字符" /></label></div></section>
            <div class="business-declaration"><label><input v-model="form.declarationAccepted" name="merchant-declaration" type="checkbox" required /><span>我确认申请资料真实、准确，并已获得经营主体的授权。</span></label></div>
          </fieldset>
          <p v-if="error" class="business-form-feedback business-message-error" role="alert">{{ error }}</p>
          <footer class="business-form-actions"><span>带 * 的项目为必填项</span><div><button class="business-button business-button-primary" type="submit" :disabled="submitting"><Send :size="16" aria-hidden="true" />{{ submitting ? '正在提交…' : '提交入驻申请' }}</button></div></footer>
        </form>
        <aside class="business-preview-column">
          <section class="merchant-preparation"><div class="business-section-title"><FileCheck2 :size="19" aria-hidden="true" /><h2>入驻流程</h2></div><ol><li><span>1</span><div><strong>填写商家资料</strong><p>填写经营主体、统一社会信用代码与商家介绍。</p></div></li><li><span>2</span><div><strong>平台审核</strong><p>核对经营主体与申请资料，必要时联系申请人补充材料。</p></div></li><li><span>3</span><div><strong>入驻商家联盟</strong><p>审核通过后建立商家档案，开展信息展示与合作交流。</p></div></li></ol><p class="business-footnote">请确保商家资料与实际经营情况一致，联系电话可正常接通。</p></section>
          <RouterLink to="/information" class="merchant-next-step"><FilePenLine :size="23" aria-hidden="true" /><div><strong>逛逛商家信息广场</strong><p>了解门店活动、合作机会与供求需求。</p></div><ArrowRight :size="17" aria-hidden="true" /></RouterLink>
        </aside>
      </div>
    </template>
  </div>
</template>
