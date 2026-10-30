<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import QRCode from 'qrcode'
import { ShieldCheck } from 'lucide-vue-next'
import { referralApi } from '../services/referralApi'
import { commissionKinds, commissionStates, inviteLink, ratePercent } from '../domain/referral'
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
            <li>提现功能即将开放，开放后用本人实名的银行卡提现，平台依法代扣个人所得税。</li>
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
            <p>提现功能即将开放，可提现的返利会一直保留。</p>
          </div>
          <span v-if="overview.realNameVerified" class="referral-badge"><ShieldCheck :size="14" aria-hidden="true" />实名核验通过</span>
        </header>
        <dl class="referral-facts">
          <div><dt>已邀请商户</dt><dd>{{ overview.inviteeCount }}</dd></div>
          <div><dt>冻结中</dt><dd>{{ yuan(overview.frozenMinor) }}</dd></div>
          <div><dt>可提现</dt><dd>{{ yuan(overview.availableMinor) }}</dd></div>
        </dl>
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
                <td><span class="referral-state" :class="`state-${item.state.toLowerCase()}`">{{ commissionStates[item.state] || item.state }}</span><small v-if="item.state === 'FROZEN'">{{ managementDate(item.availableAt) }} 后可提现</small></td>
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
.referral-table-wrap { overflow-x: auto; }
.referral-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.referral-table th, .referral-table td { padding: 10px 8px; border-bottom: 1px solid var(--color-border-subtle); text-align: left; vertical-align: top; white-space: nowrap; }
.referral-table th { color: var(--color-text-muted); font-size: 12px; font-weight: 700; }
.referral-table small { display: block; margin-top: 2px; color: var(--color-text-muted); font-size: 11px; }
.referral-state { font-weight: 700; }
.state-frozen { color: var(--color-warning); }
.state-available { color: var(--color-success); }
.state-voided { color: var(--color-text-muted); }
.referral-invitees { display: grid; gap: 0; margin: 0; padding: 0; list-style: none; font-size: 13px; }
.referral-invitees li { display: flex; justify-content: space-between; gap: 12px; padding: 10px 0; border-bottom: 1px solid var(--color-border-subtle); }
.referral-invitees time { color: var(--color-text-muted); font-size: 12px; }
@media (max-width: 640px) {
  .referral-card { padding: 18px 16px; }
  .referral-header h1 { font-size: 28px; }
  .referral-qr { margin: 0 auto; }
}
</style>
