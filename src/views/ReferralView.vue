<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import QRCode from 'qrcode'
import { ShieldCheck } from 'lucide-vue-next'
import { referralApi } from '../services/referralApi'
import { commissionKinds, commissionStates, formatCardNumber, inviteLink, ratePercent, withdrawable, withdrawalStatuses } from '../domain/referral'
import { LEGAL_ENTITY } from '../domain/legalPolicies'
import { managementDate } from '../domain/platformManagement'
import { yuan } from '../stores/billing'
import { auth } from '../stores/auth'

// 我的邀请：实名核验通过后领专属邀请码，新商户用邀请链接注册就归属到自己名下，对方每次充值烟火或开通会员按比例记现金返利。
const overview = ref(null)
const loading = ref(true)
const error = ref('')
const agreed = ref(false)
const taking = ref(false)
const takeError = ref('')
const notice = ref('')
const qr = ref('')

const link = computed(() => overview.value?.code ? inviteLink(overview.value.code, window.location.origin) : '')

async function load() {
  loading.value = true
  error.value = ''
  try {
    overview.value = await referralApi.me()
  } catch (failure) {
    error.value = failure.message || '邀请数据暂时无法读取，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function takeCode() {
  if (taking.value) return
  if (!agreed.value) { takeError.value = '请先阅读并同意《推广协议》。'; return }
  taking.value = true
  takeError.value = ''
  try {
    overview.value = await referralApi.takeCode()
  } catch (failure) {
    takeError.value = failure.message || '邀请码暂时无法生成，请稍后重试'
  } finally {
    taking.value = false
  }
}

async function copy(text, label) {
  try {
    await navigator.clipboard.writeText(text)
    notice.value = `${label}已复制。`
  } catch {
    notice.value = `浏览器不允许自动复制，请手动选中${label}复制。`
  }
}

// 提现：先绑本人银行卡（四要素核验，不做人工审核），再把全部可提现返利一次提走；平台审核后转账到这张卡。
const cardOpen = ref(false)
const card = ref({ bankName: '', cardNumber: '', mobile: '', declarationAccepted: false })
const binding = ref(false)
const cardError = ref('')
const cardMismatch = ref(false)
const confirming = ref(false)
const withdrawAgreed = ref(false)
const withdrawing = ref(false)
const withdrawError = ref('')
const withdrawNotice = ref('')

const amount = computed(() => overview.value ? withdrawable(overview.value) : 0)
const pending = computed(() => overview.value?.withdrawals?.some(w => w.status === 'PENDING'))
const blocker = computed(() => {
  const value = overview.value
  if (!value) return ''
  if (pending.value) return '有一笔提现在审核中，处理完才能再申请。'
  if (!value.bankCard) return '绑定本人银行卡后才能申请提现。'
  if (amount.value < value.withdrawMinimumMinor) return `还差 ${yuan(value.withdrawMinimumMinor - amount.value)} 满 ${yuan(value.withdrawMinimumMinor)} 可申请提现。`
  return ''
})

function openCard() {
  card.value = { bankName: '', cardNumber: '', mobile: '', declarationAccepted: false }
  cardError.value = ''
  cardMismatch.value = false
  cardOpen.value = true
}

async function bindCard() {
  if (binding.value) return
  const values = card.value
  if (values.bankName.trim().length < 2) { cardError.value = '请填写开户银行，例如：中国工商银行。'; return }
  if (!/^\d{12,19}$/.test(values.cardNumber.replace(/ /g, ''))) { cardError.value = '请输入 12–19 位银行卡号。'; return }
  if (!/^1\d{10}$/.test(values.mobile.trim())) { cardError.value = '请输入银行预留的 11 位手机号。'; return }
  if (!values.declarationAccepted) { cardError.value = '请确认这是本人名下的银行卡。'; return }
  binding.value = true
  cardError.value = ''
  cardMismatch.value = false
  try {
    const result = await referralApi.bindCard({ ...values, bankName: values.bankName.trim(), mobile: values.mobile.trim() })
    if (!result.matched) {
      cardMismatch.value = true
      return
    }
    overview.value = { ...overview.value, bankCard: result.card }
    cardOpen.value = false
    withdrawNotice.value = `已绑定 ${result.card.bankName} 尾号 ${result.card.cardLast4} 的银行卡。`
  } catch (failure) {
    cardError.value = failure.message || '银行卡暂时无法核验，请稍后重试'
  } finally {
    binding.value = false
  }
}

async function withdraw() {
  if (withdrawing.value) return
  if (!withdrawAgreed.value) { withdrawError.value = '请先阅读并同意《推广协议》中的提现规则。'; return }
  withdrawing.value = true
  withdrawError.value = ''
  try {
    const result = await referralApi.withdraw()
    confirming.value = false
    withdrawNotice.value = `已申请提现 ${yuan(result.amountMinor)}，平台审核后会转到尾号 ${result.cardLast4} 的银行卡。`
    await load()
  } catch (failure) {
    withdrawError.value = failure.message || '提现暂时无法申请，请稍后重试'
  } finally {
    withdrawing.value = false
  }
}

watch(link, async value => {
  qr.value = ''
  if (!value) return
  try { qr.value = await QRCode.toDataURL(value, { width: 200, margin: 1, errorCorrectionLevel: 'M' }) } catch { /* 二维码画不出来时还有链接和邀请码 */ }
})
watch(() => auth.user?.id, load)
onMounted(load)
</script>

<template>
  <div class="referral-page">
    <header class="referral-header">
      <h1>我的邀请</h1>
      <p>新商户通过你的邀请链接注册一方志，就归属到你名下；对方每次充值烟火或开通会员，你按比例获得现金返利。只计一级，成为推广人不收任何费用。</p>
    </header>

    <p v-if="loading" class="referral-muted" role="status">正在读取邀请数据…</p>
    <div v-else-if="error" class="referral-card" role="alert">
      <p class="referral-error">{{ error }}</p>
      <button class="ghost-button" type="button" @click="load">重新读取</button>
    </div>

    <template v-else-if="overview">
      <section class="referral-card" aria-labelledby="referral-code-title">
        <header class="referral-card-header">
          <div>
            <h2 id="referral-code-title">专属邀请码</h2>
            <p>被邀请商户充值烟火返 {{ ratePercent(overview.topupRateBp) }}，开通会员返 {{ ratePercent(overview.membershipRateBp) }}，都按实付金额计算。</p>
          </div>
        </header>

        <div v-if="!overview.realNameVerified" class="referral-gate">
          <p>完成实名核验后才能获取专属邀请码。返利以现金结算，需要核对收款人身份。</p>
          <RouterLink class="primary-button" :to="{ name: 'settings', hash: '#identity' }">去实名核验</RouterLink>
        </div>

        <div v-else-if="!overview.code" class="referral-gate">
          <ul class="referral-rules">
            <li>新商户用你的链接或邀请码首次注册即归属你，归属不再变更；已注册的账号不受影响。</li>
            <li>被邀请商户每次充值烟火或开通会员付款，按付款时的比例记一笔返利，冻结 {{ overview.freezeDays }} 天后转为可提现，冻结期内退款则作废。</li>
            <li>可提现金额满 {{ yuan(overview.withdrawMinimumMinor) }} 才能申请提现，用本人实名的银行卡收款，平台依法代扣个人所得税后实发。</li>
          </ul>
          <label class="referral-consent">
            <input v-model="agreed" type="checkbox" :disabled="taking" @change="takeError = ''" />
            <span>我已阅读并同意<a href="/policies/referral" target="_blank" rel="noopener noreferrer">《推广协议》</a>，会如实介绍一方志，不虚假宣传、不刷单。</span>
          </label>
          <p v-if="takeError" class="referral-error" role="alert">{{ takeError }}</p>
          <button class="primary-button" type="button" :disabled="taking" @click="takeCode">{{ taking ? '正在生成…' : '获取邀请码' }}</button>
        </div>

        <div v-else class="referral-share">
          <div class="referral-share-copy">
            <span class="referral-label">邀请码</span>
            <strong class="referral-code">{{ overview.code }}</strong>
            <span class="referral-label">邀请链接</span>
            <input class="referral-link" :value="link" readonly aria-label="邀请链接" @focus="$event.target.select()" />
            <div class="referral-actions">
              <button class="primary-button" type="button" @click="copy(link, '邀请链接')">复制邀请链接</button>
              <button class="ghost-button" type="button" @click="copy(overview.code, '邀请码')">复制邀请码</button>
            </div>
            <p v-if="notice" class="referral-notice" role="status">{{ notice }}</p>
          </div>
          <img v-if="qr" class="referral-qr" :src="qr" alt="邀请链接二维码" width="160" height="160" />
        </div>
      </section>

      <section class="referral-card" aria-labelledby="referral-summary-title">
        <header class="referral-card-header">
          <div>
            <h2 id="referral-summary-title">推广数据</h2>
            <p>返利冻结 {{ overview.freezeDays }} 天后转为可提现，可提现满 {{ yuan(overview.withdrawMinimumMinor) }} 才能申请提现，可提现的返利会一直保留。</p>
          </div>
          <span v-if="overview.realNameVerified" class="referral-badge"><ShieldCheck :size="14" aria-hidden="true" />实名核验通过</span>
        </header>
        <dl class="referral-facts">
          <div><dt>已邀请商户</dt><dd>{{ overview.inviteeCount }}</dd></div>
          <div><dt>冻结中</dt><dd>{{ yuan(overview.frozenMinor) }}</dd></div>
          <div><dt>可提现</dt><dd>{{ yuan(overview.availableMinor) }}</dd></div>
        </dl>
      </section>

      <section v-if="overview.code" class="referral-card" aria-labelledby="referral-withdraw-title">
        <header class="referral-card-header">
          <div>
            <h2 id="referral-withdraw-title">提现</h2>
            <p>每次把全部可提现返利一起提走。平台审核后由财务转账到你本人的银行卡，依法代扣个人所得税后实发，到账后这里会显示代扣税额和实发金额。</p>
          </div>
        </header>
        <dl class="referral-facts">
          <div><dt>本次可提现</dt><dd>{{ yuan(amount) }}</dd></div>
          <div v-if="overview.owedMinor"><dt>待扣回</dt><dd>{{ yuan(overview.owedMinor) }}</dd></div>
        </dl>
        <p v-if="overview.owedMinor" class="referral-muted referral-owed">已提现的返利因退款等原因作废后，会从下一次提现里扣回。</p>

        <div class="referral-bank">
          <template v-if="overview.bankCard">
            <span>收款卡：<strong>{{ overview.bankCard.bankName }} 尾号 {{ overview.bankCard.cardLast4 }}</strong><small>户名为本人实名，已通过银行卡核验</small></span>
            <button v-if="!cardOpen" class="ghost-button" type="button" @click="openCard">更换银行卡</button>
          </template>
          <template v-else>
            <span>还没有绑定收款银行卡。</span>
            <button v-if="!cardOpen" class="ghost-button" type="button" @click="openCard">绑定本人银行卡</button>
          </template>
        </div>

        <form v-if="cardOpen" class="referral-card-form" @submit.prevent="bindCard">
          <p class="referral-muted">持卡人必须是你本人：姓名和身份证号取自你的实名，会和卡号、银行预留手机号一起向银行核验。卡号加密保存，只显示后 4 位；预留手机号只用于这次核验，不保存。</p>
          <label>开户银行<input v-model="card.bankName" maxlength="40" autocomplete="off" placeholder="例如：中国工商银行" :disabled="binding" /></label>
          <label>银行卡号<input :value="card.cardNumber" inputmode="numeric" autocomplete="off" placeholder="本人名下的储蓄卡" :disabled="binding" @input="card.cardNumber = formatCardNumber($event.target.value); $event.target.value = card.cardNumber" /></label>
          <label>银行预留手机号<input v-model="card.mobile" inputmode="numeric" maxlength="11" autocomplete="off" :disabled="binding" /></label>
          <label class="referral-consent"><input v-model="card.declarationAccepted" type="checkbox" :disabled="binding" /><span>我确认这是本人名下的银行卡，并单独同意平台把我实名的姓名、身份证号码和这张卡的卡号、银行预留手机号，经阿里云实人认证服务（客服电话 95187）提交银行核验是否一致，仅用于返利提现收款。</span></label>
          <p v-if="cardMismatch" class="referral-error" role="alert">银行卡与本人实名信息或银行预留手机号核验不一致，请核对后重试，或换一张本人银行卡。如有疑问请拨打人工客服：{{ LEGAL_ENTITY.phone }}</p>
          <p v-if="cardError" class="referral-error" role="alert">{{ cardError }}</p>
          <div class="referral-actions">
            <button class="primary-button" type="submit" :disabled="binding">{{ binding ? '正在核验…' : '核验并绑定' }}</button>
            <button class="ghost-button" type="button" :disabled="binding" @click="cardOpen = false">取消</button>
          </div>
        </form>

        <div class="referral-withdraw">
          <template v-if="confirming">
            <p>将提现 <strong>{{ yuan(amount) }}</strong> 到 {{ overview.bankCard.bankName }} 尾号 {{ overview.bankCard.cardLast4 }}，确认申请？</p>
            <label class="referral-consent"><input v-model="withdrawAgreed" type="checkbox" :disabled="withdrawing" @change="withdrawError = ''" /><span>我已阅读并同意<a href="/policies/referral" target="_blank" rel="noopener noreferrer">《推广协议》</a>中的提现规则：平台依法代扣个人所得税后实发；已提现的返利因退款或违规作废的，从下一次提现中扣回。</span></label>
            <div class="referral-actions">
              <button class="primary-button" type="button" :disabled="withdrawing" @click="withdraw">{{ withdrawing ? '正在提交…' : '确认申请' }}</button>
              <button class="ghost-button" type="button" :disabled="withdrawing" @click="confirming = false">取消</button>
            </div>
          </template>
          <template v-else>
            <button class="primary-button" type="button" :disabled="Boolean(blocker)" @click="confirming = true; withdrawAgreed = false; withdrawError = ''; withdrawNotice = ''">申请提现</button>
            <p v-if="blocker" class="referral-muted">{{ blocker }}</p>
          </template>
          <p v-if="withdrawError" class="referral-error" role="alert">{{ withdrawError }}</p>
          <p v-if="withdrawNotice" class="referral-notice" role="status">{{ withdrawNotice }}</p>
        </div>

        <div v-if="overview.withdrawals.length" class="referral-table-wrap">
          <table class="referral-table">
            <thead><tr><th>申请时间</th><th>提现金额</th><th>收款卡</th><th>状态</th><th>代扣税</th><th>实发</th><th>说明</th></tr></thead>
            <tbody>
              <tr v-for="item in overview.withdrawals" :key="item.id">
                <td>{{ managementDate(item.createdAt) }}</td>
                <td><strong>{{ yuan(item.amountMinor) }}</strong><small v-if="item.clawbackMinor">返利 {{ yuan(item.commissionMinor) }}，扣回 {{ yuan(item.clawbackMinor) }}</small></td>
                <td>{{ item.bankName }} 尾号 {{ item.cardLast4 }}</td>
                <td><span class="referral-state" :class="`withdrawal-${item.status.toLowerCase()}`">{{ withdrawalStatuses[item.status] || item.status }}</span></td>
                <td>{{ item.taxMinor == null ? '—' : yuan(item.taxMinor) }}</td>
                <td>{{ item.netMinor == null ? '—' : yuan(item.netMinor) }}</td>
                <td class="referral-wrap">{{ item.status === 'PAID' ? `${item.paidDate} 打款` : item.reviewNote || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="referral-card" aria-labelledby="referral-commissions-title">
        <header class="referral-card-header"><div><h2 id="referral-commissions-title">返利明细</h2><p>最近 100 笔，按到账时间倒序。</p></div></header>
        <p v-if="!overview.commissions.length" class="referral-muted">还没有返利。被邀请商户充值烟火或开通会员后会出现在这里。</p>
        <div v-else class="referral-table-wrap">
          <table class="referral-table">
            <thead><tr><th>到账时间</th><th>被邀请商户</th><th>类型</th><th>实付金额</th><th>比例</th><th>返利</th><th>状态</th></tr></thead>
            <tbody>
              <tr v-for="item in overview.commissions" :key="item.id">
                <td>{{ managementDate(item.createdAt) }}</td>
                <td>{{ item.inviteePhoneMasked }}</td>
                <td>{{ commissionKinds[item.kind] || '充值烟火' }}</td>
                <td>{{ yuan(item.paidMinor) }}</td>
                <td>{{ ratePercent(item.rateBp) }}</td>
                <td><strong>{{ yuan(item.amountMinor) }}</strong></td>
                <td><span class="referral-state" :class="`state-${item.state.toLowerCase()}`">{{ commissionStates[item.state] || item.state }}</span><small v-if="item.state === 'FROZEN'">{{ managementDate(item.availableAt) }} 后可提现</small><small v-else-if="item.state === 'VOIDED' && item.voidNote">{{ item.voidNote }}</small></td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="referral-card" aria-labelledby="referral-invitees-title">
        <header class="referral-card-header"><div><h2 id="referral-invitees-title">邀请的商户</h2><p>只显示打码后的手机号，最近 100 个。</p></div></header>
        <p v-if="!overview.invitees.length" class="referral-muted">还没有商户通过你的邀请注册。</p>
        <ul v-else class="referral-invitees">
          <li v-for="item in overview.invitees" :key="`${item.phoneMasked}-${item.registeredAt}`"><span>{{ item.phoneMasked }}</span><time>{{ managementDate(item.registeredAt) }} 注册</time></li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>
.referral-page { padding-bottom: 72px; font-family: var(--font-sans); color: var(--color-primary); }
.referral-header { margin-bottom: 28px; }
.referral-header h1 { margin: 8px 0 6px; font-family: var(--font-serif); font-size: 36px; font-weight: 750; line-height: 1.18; }
.referral-header p, .referral-muted { margin: 0; color: var(--color-text-muted); font-size: 13px; line-height: 1.75; }
.referral-card { margin-bottom: 20px; padding: 22px 24px; border: 1px solid var(--color-border-subtle); border-radius: 8px; background: var(--color-bg-surface); }
.referral-card-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 18px; }
.referral-card-header h2 { margin: 0 0 4px; font-family: var(--font-serif); font-size: 20px; font-weight: 750; }
.referral-card-header p { margin: 0; color: var(--color-text-muted); font-size: 12px; line-height: 1.7; }
.primary-button, .ghost-button { display: inline-flex; align-items: center; justify-content: center; border-radius: 4px; padding: 10px 17px; font: 700 13px var(--font-sans); cursor: pointer; text-decoration: none; }
.primary-button { border: 1px solid var(--color-accent); color: var(--color-on-accent); background: var(--color-accent); }
.primary-button:hover:not(:disabled) { background: var(--color-accent-hover); }
.ghost-button { border: 1px solid var(--color-border-subtle); color: var(--color-primary); background: transparent; }
.ghost-button:hover:not(:disabled) { background: var(--color-bg-subtle); }
.primary-button:disabled, .ghost-button:disabled { opacity: .55; cursor: default; }
.referral-gate { display: grid; gap: 14px; justify-items: start; font-size: 13px; line-height: 1.8; }
.referral-gate > p { margin: 0; color: var(--color-text-muted); }
.referral-rules { margin: 0; padding-left: 18px; color: var(--color-text-muted); }
.referral-consent { display: flex; gap: 8px; align-items: flex-start; color: var(--color-text-muted); font-size: 12px; }
.referral-consent input { margin-top: 5px; accent-color: var(--color-accent); }
.referral-consent a { color: var(--color-accent-text); }
.referral-error { margin: 0; color: var(--color-error); font-size: 12px; }
.referral-notice { margin: 0; color: var(--color-success); font-size: 12px; }
.referral-share { display: flex; flex-wrap: wrap; gap: 24px; align-items: flex-start; justify-content: space-between; }
.referral-share-copy { display: grid; gap: 8px; flex: 1 1 320px; min-width: 0; }
.referral-label { color: var(--color-text-muted); font-size: 12px; font-weight: 700; }
.referral-code { margin-bottom: 6px; font: 700 28px/1.2 var(--font-mono, ui-monospace, monospace); letter-spacing: .12em; }
.referral-link { width: 100%; min-height: 40px; padding: 0 12px; border: 1px solid var(--color-border-subtle); border-radius: 4px; background: var(--color-bg-subtle); color: var(--color-primary); font: 13px var(--font-sans); }
.referral-actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 4px; }
.referral-qr { flex: none; border: 1px solid var(--color-border-subtle); border-radius: 6px; background: #fff; }
.referral-badge { display: inline-flex; align-items: center; gap: 4px; padding: 3px 8px; border-radius: 4px; color: var(--color-success); background: color-mix(in srgb, var(--color-success) 10%, var(--color-bg-surface)); font-size: 12px; font-weight: 700; }
.referral-facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 14px; margin: 0; }
.referral-facts dt { color: var(--color-text-muted); font-size: 12px; font-weight: 700; }
.referral-facts dd { margin: 4px 0 0; font-size: 22px; font-variant-numeric: tabular-nums; }
.referral-owed { margin-top: 8px; font-size: 12px; }
.referral-bank { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; margin-top: 18px; padding: 14px 0; border-top: 1px solid var(--color-border-subtle); font-size: 13px; }
.referral-bank small { display: block; margin-top: 2px; color: var(--color-text-muted); font-size: 12px; }
.referral-card-form { display: grid; gap: 12px; max-width: 460px; margin-bottom: 8px; font-size: 13px; }
.referral-card-form > p { margin: 0; font-size: 12px; }
.referral-card-form label:not(.referral-consent) { display: grid; gap: 6px; color: var(--color-text-muted); font-size: 12px; font-weight: 700; }
.referral-card-form input:not([type="checkbox"]) { min-height: 40px; padding: 0 12px; border: 1px solid var(--color-border-subtle); border-radius: 4px; background: var(--color-bg-surface); color: var(--color-primary); font: 14px var(--font-sans); }
.referral-withdraw { display: grid; gap: 10px; justify-items: start; padding: 14px 0 4px; border-top: 1px solid var(--color-border-subtle); font-size: 13px; }
.referral-withdraw p { margin: 0; }
.referral-withdraw + .referral-table-wrap { margin-top: 16px; }
.referral-wrap { white-space: normal !important; min-width: 160px; }
.referral-table-wrap { overflow-x: auto; }
.referral-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.referral-table th, .referral-table td { padding: 10px 8px; border-bottom: 1px solid var(--color-border-subtle); text-align: left; vertical-align: top; white-space: nowrap; }
.referral-table th { color: var(--color-text-muted); font-size: 12px; font-weight: 700; }
.referral-table small { display: block; margin-top: 2px; color: var(--color-text-muted); font-size: 11px; }
.referral-state { font-weight: 700; }
.state-frozen { color: var(--color-warning); }
.state-available { color: var(--color-success); }
.state-voided { color: var(--color-text-muted); }
.state-withdrawing, .withdrawal-pending { color: var(--color-warning); }
.state-withdrawn, .withdrawal-paid { color: var(--color-success); }
.withdrawal-rejected { color: var(--color-error); }
.referral-invitees { display: grid; gap: 0; margin: 0; padding: 0; list-style: none; font-size: 13px; }
.referral-invitees li { display: flex; justify-content: space-between; gap: 12px; padding: 10px 0; border-bottom: 1px solid var(--color-border-subtle); }
.referral-invitees time { color: var(--color-text-muted); font-size: 12px; }
@media (max-width: 640px) {
  .referral-card { padding: 18px 16px; }
  .referral-header h1 { font-size: 28px; }
  .referral-qr { margin: 0 auto; }
}
</style>
