<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Check, RefreshCw, Wallet, X } from 'lucide-vue-next'
import QRCode from 'qrcode'
import { billing, billingApi, dateTime, loadBillingPlans, number, refreshBilling, yuan } from '../stores/billing'
import BillingCheckoutTerms from '../components/BillingCheckoutTerms.vue'
import YanhuoIcon from '../components/YanhuoIcon.vue'
import { auth } from '../stores/auth'
import { isMobilePayment, openBillingCashier } from '../utils/billingPayment'

const route = useRoute()
const router = useRouter()
const mobilePayment = isMobilePayment()
const account = computed(() => billing.summary)
const returnTo = computed(() => typeof route.query.redirect === 'string'
  && route.query.redirect.startsWith('/') && !route.query.redirect.startsWith('//')
  ? route.query.redirect : '/dashboard')
const amount = ref(500)
const purchaseConsent = ref(false)
const planTerms = ref(null)
const topupTerms = ref(null)
watch(() => [auth.user?.id, auth.tenantId], () => { purchaseConsent.value = false }, { flush: 'sync' })
const MIN_TOPUP_YUAN = 500
const preset = [500, 1000, 2000, 5000]
const validAmount = computed(() => Number.isInteger(amount.value) && amount.value >= MIN_TOPUP_YUAN && amount.value <= 100000)
const topupCredits = computed(() => validAmount.value ? amount.value * (account.value?.creditsPerYuan || 10) : 0)
const busy = ref(false)
const error = ref('')
// The checkout block where the last purchase error happened, so the message shows beside that button.
const errorAt = ref('')
const plansError = ref('')
const payment = ref(null)
const qr = ref('')
const paymentError = ref('')
const paymentLoading = ref(false)
const paymentDialog = ref(null)
const planDialog = ref(null)
const successDialog = ref(null)
// Balance just before a top-up was paid, so the success popup can show before → added → now from real readings.
let balanceBefore = null
const topupResult = ref(null)
const topupDialog = ref(null)
const pendingPlan = ref(null)
const refreshButton = ref(null)
let paymentTimer
let balanceTimer
let disposed = false
let qrVersion = 0
const keys = new Map()
const labels = { PENDING_PAYMENT: '待支付', PAID: '已到账', CANCELLED: '已取消', EXPIRED: '已过期' }
const membershipLabel = computed(() => account.value?.memberActive ? '会员生效中' : account.value?.plan ? '会员已到期，账号未激活' : '账号未激活')
// 会员有效期内可以续订同一套餐，或换成功能相同、只是时长不同的套餐（月卡续年卡），规则同后端。
const canSwitchTo = plan => {
  const current = account.value?.plan
  if (!account.value?.memberActive || !current || current.code === plan.code) return true
  return current.imageEnabled === plan.imageEnabled && current.videoEnabled === plan.videoEnabled && current.liveEnabled === plan.liveEnabled
}
const canPay = computed(() => account.value?.canManage && account.value?.payment.available && !billing.error && !busy.value)
const keyFor = (key) => {
  if (!keys.has(key)) keys.set(key, 'billing-' + crypto.randomUUID())
  return keys.get(key)
}
const reloadPlans = async () => {
  try { await loadBillingPlans(); plansError.value = '' }
  catch (e) { plansError.value = e.message || '套餐暂时无法读取' }
}
const refresh = async () => { await refreshBilling(); await reloadPlans() }
// The top bar's balance chip lands here with ?topup=1; the query is dropped on close so the next click opens it again.
const openTopup = async () => { await nextTick(); if (topupDialog.value && !topupDialog.value.open) topupDialog.value.showModal() }
const closeTopup = () => {
  topupDialog.value?.close()
  if (route.query.topup) router.replace({ query: { ...route.query, topup: undefined } })
}
watch(() => route.query.topup, value => { if (value && account.value) openTopup() })
const choosePlan = async (plan) => { pendingPlan.value = plan; await nextTick(); planDialog.value?.showModal() }
const closePlan = () => { planDialog.value?.close(); pendingPlan.value = null }
const closeSuccess = () => { successDialog.value?.close(); topupResult.value = null }
const closePayment = () => {
  clearTimeout(paymentTimer); paymentDialog.value?.close(); payment.value = null; qr.value = ''; qrVersion += 1; refreshButton.value?.focus()
  if (route.query.paymentOrder) router.replace({ query: { ...route.query, paymentOrder: undefined } })
}
const applyPayment = async (order) => {
  if (disposed || !payment.value) return
  payment.value = order
  if (['PAID', 'CANCELLED', 'EXPIRED'].includes(order.state)) keys.delete(order.kind === 'TOPUP' ? 'topup:' + order.amountMinor / 100 : 'plan:' + order.plan?.id)
  if (order.state === 'PAID') {
    keys.clear()
    qr.value = ''
    await refreshBilling()
    if (order.kind === 'TOPUP' && !disposed) {
      topupResult.value = { before: balanceBefore, credits: order.credits, now: billing.summary?.available ?? null, paidAt: order.paidAt }
      closePayment()
      await nextTick()
      successDialog.value?.showModal()
    }
  } else if (order.paymentCodeUrl) {
    const version = ++qrVersion
    const image = await QRCode.toDataURL(order.paymentCodeUrl, { width: 280, margin: 2, color: { dark: '#20282b', light: '#ffffff' } })
    if (version === qrVersion) qr.value = image
  }
}
const pollPayment = async (remote = false) => {
  if (disposed || !payment.value || paymentLoading.value) return
  paymentLoading.value = true
  try {
    const id = payment.value.id
    const result = await (remote ? billingApi.refresh(id) : billingApi.order(id))
    if (payment.value?.id === id) { await applyPayment(result); paymentError.value = '' }
  } catch (e) { paymentError.value = e.message || '支付状态暂时无法读取' }
  finally {
    paymentLoading.value = false
    clearTimeout(paymentTimer)
    if (!disposed && payment.value?.state === 'PENDING_PAYMENT') paymentTimer = setTimeout(pollPayment, 4000)
  }
}
// Coming back from the Alipay tab: ask the provider at once instead of waiting for its notification or the next poll.
const checkOnReturn = () => { if (document.visibilityState === 'visible' && payment.value?.state === 'PENDING_PAYMENT') pollPayment(true) }
const openPayment = async (order) => {
  if (disposed) return
  balanceBefore = order.state === 'PENDING_PAYMENT' ? billing.summary?.available ?? null : null
  payment.value = order
  paymentError.value = ''
  qr.value = ''
  await nextTick()
  if (disposed) return
  paymentDialog.value?.showModal()
  await applyPayment(order)
  if (order.state === 'PENDING_PAYMENT') paymentTimer = setTimeout(pollPayment, 4000)
}
// Keep the order in the URL so browser back and the cashier return can recover it.
const openAlipay = async () => {
  if (payment.value?.state !== 'PENDING_PAYMENT' || paymentLoading.value) return
  const id = payment.value.id
  paymentLoading.value = true
  try {
    await openBillingCashier(mobile => billingApi.alipay(id, mobile), {
      mobile: mobilePayment,
      beforeNavigate: async () => {
        if (disposed || payment.value?.id !== id || payment.value.state !== 'PENDING_PAYMENT') throw new Error('订单已关闭，请重新查看支付状态')
        if (mobilePayment) await router.replace({ query: { ...route.query, topup: undefined, paymentOrder: id } })
      },
    })
    paymentError.value = ''
  }
  catch (e) { paymentError.value = e.message || '支付宝收银台暂时无法打开' }
  finally { paymentLoading.value = false }
}
const checkPurchaseConsent = (message, terms, at) => {
  if (purchaseConsent.value) return true
  error.value = message; errorAt.value = at
  nextTick(() => terms.value?.focus())
  return false
}
const topUp = async () => {
  if (!validAmount.value || !canPay.value) return
  if (!checkPurchaseConsent('充值前请阅读并同意用户协议、隐私协议和售后服务规则。', topupTerms, 'topup')) return
  busy.value = true; error.value = ''; errorAt.value = 'topup'
  try {
    const order = await billingApi.topUp({ requestKey: keyFor('topup:' + amount.value), amountMinor: amount.value * 100 })
    closeTopup()
    await openPayment(order)
  }
  catch (e) { error.value = e.message || '充值订单创建失败' }
  finally { busy.value = false }
}
const redeemCode = ref('')
const redeeming = ref(false)
const redeemResult = ref(null)
const redeemError = ref('')
const redeem = async () => {
  if (!redeemCode.value.trim() || redeeming.value) return
  redeeming.value = true; redeemError.value = ''; redeemResult.value = null
  try {
    redeemResult.value = await billingApi.redeem(redeemCode.value.trim())
    redeemCode.value = ''
    await refreshBilling()
  }
  catch (e) { redeemError.value = e.message || '兑换失败，请稍后重试' }
  finally { redeeming.value = false }
}
const subscribe = async (plan) => {
  if (!canPay.value) return
  if (!checkPurchaseConsent('订阅前请阅读并同意用户协议、隐私协议和售后服务规则。', planTerms, 'plan')) return
  busy.value = true; error.value = ''; errorAt.value = 'plan'
  try {
    const order = await billingApi.subscribe({ requestKey: keyFor('plan:' + plan.id), planId: plan.id })
    closePlan()
    await openPayment(order)
  }
  catch (e) { error.value = e.message || '订阅订单创建失败' }
  finally { busy.value = false }
}
const cancelPayment = async () => {
  if (!payment.value || paymentLoading.value) return
  paymentLoading.value = true
  try { await applyPayment(await billingApi.cancel(payment.value.id)); paymentError.value = '' }
  catch (e) { paymentError.value = e.message || '订单取消失败，请刷新支付状态' }
  finally { paymentLoading.value = false }
}
onMounted(async () => {
  await refresh()
  if (disposed) return
  const returningOrder = route.query.paymentOrder
  if (typeof returningOrder === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(returningOrder)) {
    try { await openPayment(await billingApi.order(returningOrder)); await pollPayment(true) }
    catch (e) { errorAt.value = 'payment'; error.value = e.message || '支付订单暂时无法读取，请刷新后重试' }
  }
  if (disposed) return
  if (route.query.topup && account.value) openTopup()
  balanceTimer = setInterval(() => { if (document.visibilityState === 'visible') refreshBilling() }, 30000)
  globalThis.window?.addEventListener('focus', checkOnReturn)
  globalThis.document?.addEventListener?.('visibilitychange', checkOnReturn)
})
onBeforeUnmount(() => { disposed = true; clearTimeout(paymentTimer); clearInterval(balanceTimer); globalThis.window?.removeEventListener('focus', checkOnReturn); globalThis.document?.removeEventListener?.('visibilitychange', checkOnReturn); qrVersion += 1 })
</script>

<template>
  <div class="yanhuo-page">
    <header class="yanhuo-heading">
      <div>
        <h1>套餐与权益</h1>
        <p class="yanhuo-intro">图片、视频、文案等功能不用开会员，充值烟火就能用；AI 实景直播需要开通会员，开通时赠送烟火，赠送的烟火优先使用。</p>
      </div>
      <button ref="refreshButton" class="yanhuo-icon" type="button" title="刷新账户" aria-label="刷新账户" :disabled="billing.loading" @click="refresh"><RefreshCw :size="18" :class="{ spinning: billing.loading }" /></button>
    </header>
    <div v-if="route.query.membership && !account?.memberActive" class="yanhuo-alert" role="status">{{ route.query.membership === 'unavailable' ? '暂时无法确认会员状态，请重新读取账户后继续。' : `使用${route.query.module || '平台功能'}需要先开通会员。充值烟火不会自动开通会员。` }}</div>
    <RouterLink v-if="route.query.membership && account?.memberActive" :to="returnTo" class="generation-billing-link">会员已生效，继续使用{{ route.query.module || '平台功能' }}</RouterLink>
    <div v-if="billing.error" class="yanhuo-alert" role="alert">{{ billing.error }}<button type="button" @click="refresh">重新读取</button></div>
    <div v-else-if="!account" class="yanhuo-empty" role="status">正在读取烟火账户...</div>
    <div v-if="errorAt === 'payment' && error" class="yanhuo-alert" role="alert">{{ error }}</div>
    <template v-if="account">
      <section class="yanhuo-overview" aria-label="会员与烟火">
        <article class="yanhuo-card yanhuo-member-card">
          <div class="yanhuo-card-head">
            <span class="yanhuo-card-label">会员</span>
            <span class="yanhuo-status" :class="{ active: account.memberActive }"><span />{{ membershipLabel }}</span>
          </div>
          <h2>{{ account.plan?.name || '尚未订阅套餐' }}</h2>
          <p v-if="account.memberUntil" class="yanhuo-card-meta">有效期至 {{ dateTime(account.memberUntil) }}</p>
          <p v-if="auth.user?.uid" class="yanhuo-card-meta">我的用户 ID：<strong class="yanhuo-uid">{{ auth.user.uid }}</strong>（服务商添加你时使用）</p>
          <p v-else class="yanhuo-card-meta">未激活的账号只能查看。购买会员或输入激活码后即可使用全部功能，使用时按烟火计费。</p>
          <ul class="yanhuo-entitlements">
            <li :class="{ enabled: account.memberActive }"><Check :size="14" />图片、视频、文案（按烟火）</li>
            <li :class="{ enabled: account.memberActive && account.plan?.liveEnabled }"><Check :size="14" />AI 实景直播</li>
          </ul>
          <a href="#member-plans" class="yanhuo-secondary">{{ account.memberActive ? '续订套餐' : '购买会员' }}</a>
        </article>
        <article class="yanhuo-card yanhuo-balance-card">
          <div class="yanhuo-card-head">
            <span class="yanhuo-card-label">可用烟火</span>
          </div>
          <p class="yanhuo-number"><YanhuoIcon :size="30" />{{ number(account.available) }}</p>
          <dl class="yanhuo-sources"><div><dt>赠送烟火</dt><dd>{{ number(account.giftAvailable) }}</dd></div><div><dt>充值余额</dt><dd>{{ number(account.purchasedAvailable) }}</dd></div><div><dt>生成中预占</dt><dd>{{ number(account.held) }}</dd></div></dl>
          <button type="button" class="yanhuo-secondary" @click="openTopup">充值烟火</button>
        </article>
      </section>

      <section class="yanhuo-section" aria-labelledby="redeem-title">
        <header class="yanhuo-section-head"><h2 id="redeem-title">激活码 / 兑换码</h2><p>输入激活码开通会员，输入兑换码烟火立即到账；都是全店共用，每个码只能使用一次。</p></header>
        <form class="yanhuo-redeem" @submit.prevent="redeem">
          <input v-model="redeemCode" class="yanhuo-redeem-input" maxlength="40" autocomplete="off" spellcheck="false" placeholder="XXXX-XXXX-XXXX-XXXX" aria-label="激活码或兑换码">
          <button class="yanhuo-primary" type="submit" :disabled="redeeming || !redeemCode.trim()">{{ redeeming ? '兑换中…' : '兑换' }}</button>
        </form>
        <p v-if="redeemResult?.kind === 'MEMBERSHIP'" class="yanhuo-redeem-done" role="status">已开通「{{ redeemResult.planName }}」，有效期至 {{ dateTime(redeemResult.memberUntil) }}。</p>
        <p v-else-if="redeemResult" class="yanhuo-redeem-done" role="status">已到账 {{ number(redeemResult.credits) }} {{ redeemResult.kind === 'TOPUP' ? '充值' : '赠送' }}烟火，当前可用 {{ number(redeemResult.available) }}。</p>
        <div v-if="redeemError" class="yanhuo-alert" role="alert">{{ redeemError }}</div>
      </section>

      <section id="member-plans" class="yanhuo-section" aria-labelledby="plans-title">
        <header class="yanhuo-section-head"><h2 id="plans-title">会员套餐</h2><p>开通会员后即可使用全部功能，使用时按烟火计费；也可以在上方输入激活码开通。</p></header>
        <div v-if="plansError" class="yanhuo-alert" role="alert">{{ plansError }}<button type="button" @click="reloadPlans">重新读取</button></div>
        <div v-else-if="!billing.plans.length" class="yanhuo-card yanhuo-empty"><img src="/images/brand/yifangzhi-mark.png" alt="" width="36" height="36"><h3>套餐尚未开放</h3><p>正式套餐公布后，可在这里订阅。</p></div>
        <div v-else class="yanhuo-plan-grid">
          <article v-for="plan in billing.plans" :key="plan.id" class="yanhuo-card yanhuo-plan" :class="{ 'is-current': account.plan?.code === plan.code && account.memberActive }">
            <div class="yanhuo-plan-top"><h3>{{ plan.name }}</h3><span v-if="account.plan?.code === plan.code && account.memberActive" class="yanhuo-current">当前套餐</span></div>
            <p class="yanhuo-plan-price">{{ yuan(plan.priceMinor) }}<small>/ {{ plan.durationDays }} 天</small></p>
            <p class="yanhuo-plan-description">{{ plan.description }}</p>
            <ul><li v-if="plan.giftCredits"><Check :size="15" />赠送 {{ number(plan.giftCredits) }} 烟火</li><li v-if="plan.imageEnabled"><Check :size="15" />图片创作</li><li v-if="plan.videoEnabled"><Check :size="15" />视频创作</li><li v-if="plan.liveEnabled"><Check :size="15" />直播（{{ account.liveCreditsPerHour }} 烟火 / 小时）</li><li v-if="plan.allianceQuotaMinor"><Check :size="15" />联盟券额度 ¥{{ (plan.allianceQuotaMinor / 100).toFixed(2) }} / 期</li></ul>
            <button class="yanhuo-primary" type="button" :disabled="!canPay || !canSwitchTo(plan)" @click="choosePlan(plan)">{{ !account.memberActive ? '订阅套餐' : !canSwitchTo(plan) ? '到期后可订阅' : '续订套餐' }}</button>
          </article>
        </div>
      </section>


      <section class="yanhuo-section" aria-labelledby="rules-title">
        <header class="yanhuo-section-head"><h2 id="rules-title">计费说明</h2><p>优先使用赠送的烟火；所有功能都要账号已激活（会员生效中）。</p></header>
        <dl class="yanhuo-rule-grid">
          <div><dt>图片创作</dt><dd><strong><YanhuoIcon :size="14" />{{ account.imageCredits }} / 张</strong>生成中先预占，未交付的退回。</dd></div>
          <div><dt>视频反推</dt><dd><strong><YanhuoIcon :size="14" />{{ account.videoAnalysisCredits }} / 次</strong>分析成功后扣除，失败或取消退回。</dd></div>
          <div><dt>视频生成</dt><dd><strong>按确认的报价</strong>先预占烟火，交付后按实际用量结算。</dd></div>
          <div><dt>直播</dt><dd><strong><YanhuoIcon :size="14" />{{ account.liveCreditsPerHour }} / 小时</strong>开播先预占一小时，按分钟结算，暂停不计费。</dd></div>
        </dl>
        <p class="yanhuo-note">其他模块按正式公布的规则计费。</p>
      </section>
    </template>
    <dialog ref="topupDialog" class="yanhuo-payment yanhuo-topup-dialog" aria-labelledby="topup-title" @cancel.prevent="closeTopup" @click="event => { if (event.target === topupDialog) closeTopup() }">
      <template v-if="account">
        <header><img src="/images/brand/yifangzhi-mark.png" alt="" width="32" height="32"><h2 id="topup-title">充值烟火</h2><button class="yanhuo-icon" type="button" aria-label="关闭充值窗口" title="关闭" @click="closeTopup"><X :size="20" /></button></header>
        <p class="yanhuo-topup-rate">1 元 = {{ account.creditsPerYuan }} 烟火，充值的烟火长期有效。</p>
        <p v-if="account.canManage && !account.memberActive" class="yanhuo-alert yanhuo-topup-warning" role="status">充值只增加烟火余额，不会开通会员。账号激活后才能使用功能，请先<a href="#member-plans" class="yanhuo-link" @click="closeTopup">购买会员</a>或输入激活码。</p>
        <form class="yanhuo-topup-form" @submit.prevent="topUp">
          <fieldset class="yanhuo-amounts"><legend class="sr-only">充值金额</legend><label v-for="value in preset" :key="value" :class="{ selected: amount === value }"><input v-model="amount" type="radio" :value="value" name="topup-amount"><span>{{ yuan(value * 100) }}</span><small><YanhuoIcon :size="12" />{{ number(value * account.creditsPerYuan) }}</small></label></fieldset>
          <div class="yanhuo-topup-row"><label class="yanhuo-custom"><span>自定义金额（{{ MIN_TOPUP_YUAN }} 元起）</span><span class="yanhuo-money-input"><span>¥</span><input v-model.number="amount" inputmode="numeric" type="number" :min="MIN_TOPUP_YUAN" max="100000" step="1" required aria-label="充值金额，单位元"></span></label><div class="yanhuo-topup-total"><span>到账烟火</span><strong><YanhuoIcon :size="20" />{{ number(topupCredits) }}</strong></div></div>
          <BillingCheckoutTerms ref="topupTerms" v-model="purchaseConsent" :method="account.paymentMethod" :available="account.payment.available" :can-manage="account.canManage" :disabled="busy" :error="errorAt === 'topup' ? error : ''">
            <div class="yanhuo-checkout-pay"><span class="yanhuo-checkout-due">应付<strong>{{ validAmount ? yuan(amount * 100) : '—' }}</strong></span><button class="yanhuo-primary" type="submit" :disabled="!canPay || !validAmount"><Wallet :size="16" />{{ busy ? '正在创建订单...' : '确认支付' }}</button></div>
          </BillingCheckoutTerms>
        </form>
      </template>
    </dialog>
    <dialog ref="successDialog" class="yanhuo-payment yanhuo-success-dialog" aria-labelledby="success-title" @cancel.prevent="closeSuccess" @click="event => { if (event.target === successDialog) closeSuccess() }">
      <template v-if="topupResult">
        <div class="yanhuo-success-mark"><Check :size="26" /></div>
        <h2 id="success-title">充值成功</h2>
        <p class="yanhuo-success-time">{{ dateTime(topupResult.paidAt) }} 到账</p>
        <dl class="yanhuo-success-rows">
          <div v-if="topupResult.before !== null"><dt>原有烟火</dt><dd><YanhuoIcon :size="16" />{{ number(topupResult.before) }}</dd></div>
          <div class="added"><dt>本次充值</dt><dd>+{{ number(topupResult.credits) }}</dd></div>
          <div v-if="topupResult.now !== null" class="total"><dt>现在烟火</dt><dd><YanhuoIcon :size="18" />{{ number(topupResult.now) }}</dd></div>
        </dl>
        <button class="yanhuo-primary" type="button" @click="closeSuccess">完成</button>
      </template>
    </dialog>
    <dialog ref="planDialog" class="yanhuo-payment" aria-labelledby="plan-confirm-title" @cancel.prevent="closePlan" @click="event => { if (event.target === planDialog) closePlan() }">
      <template v-if="pendingPlan && account">
        <header><img src="/images/brand/yifangzhi-mark.png" alt="" width="32" height="32"><h2 id="plan-confirm-title">{{ account.memberActive ? '续订套餐' : '订阅套餐' }}</h2><button class="yanhuo-icon" type="button" aria-label="关闭" title="关闭" @click="closePlan"><X :size="20" /></button></header>
        <p class="yanhuo-payment-amount">{{ yuan(pendingPlan.priceMinor) }}</p>
        <p class="yanhuo-payment-item">{{ pendingPlan.name }} · {{ pendingPlan.durationDays }} 天 · 赠送 {{ number(pendingPlan.giftCredits) }} 烟火</p>
        <BillingCheckoutTerms ref="planTerms" v-model="purchaseConsent" :method="account.paymentMethod" :available="account.payment.available" :can-manage="account.canManage" :disabled="busy" :error="errorAt === 'plan' ? error : ''">
          <button class="yanhuo-primary" type="button" :disabled="!canPay" @click="subscribe(pendingPlan)">{{ busy ? '正在创建订单...' : '确认支付' }}</button>
        </BillingCheckoutTerms>
      </template>
    </dialog>
    <dialog ref="paymentDialog" class="yanhuo-payment" aria-labelledby="payment-title" @cancel.prevent="closePayment" @click="event => { if (event.target === paymentDialog) closePayment() }">
      <template v-if="payment">
        <header><img src="/images/brand/yifangzhi-mark.png" alt="" width="32" height="32"><h2 id="payment-title">{{ payment.state === 'PAID' ? '烟火已到账' : payment.kind === 'TOPUP' ? '充值烟火' : '订阅会员' }}</h2><button class="yanhuo-icon" type="button" aria-label="关闭支付窗口" title="关闭" @click="closePayment"><X :size="20" /></button></header>
        <p class="yanhuo-payment-amount">{{ yuan(payment.amountMinor) }}</p>
        <p class="yanhuo-payment-item">{{ payment.kind === 'TOPUP' ? number(payment.credits) + ' 烟火' : payment.plan?.name + ' · 赠送 ' + number(payment.credits) + ' 烟火' }}</p>
        <div v-if="payment.state === 'PAID'" class="yanhuo-payment-result positive"><Check :size="36" /><strong>支付成功</strong><span>{{ dateTime(payment.paidAt) }}</span></div>
        <template v-else-if="payment.state === 'PENDING_PAYMENT'">
          <template v-if="payment.paymentMethod === 'WECHAT'">
            <div class="yanhuo-qr"><img v-if="qr" :src="qr" width="240" height="240" alt="微信支付二维码"><span v-else>正在读取收款码...</span></div><p class="yanhuo-payment-caption">微信扫码支付</p>
          </template>
          <template v-else-if="payment.paymentMethod === 'ALIPAY'">
            <button class="yanhuo-primary" type="button" :disabled="paymentLoading" @click="openAlipay">前往支付宝付款</button>
            <p class="yanhuo-payment-caption">{{ mobilePayment ? '将打开支付宝收银台，付款后返回网站核实到账' : '在新窗口完成付款后回到这里，到账后自动更新' }}</p>
          </template>
          <template v-else>
            <dl class="yanhuo-manual-details"><div><dt>收款方</dt><dd>{{ payment.payment.recipient }}</dd></div><div v-if="payment.payment.account"><dt>收款账号</dt><dd>{{ payment.payment.account }}</dd></div><div v-if="payment.payment.bank"><dt>开户行</dt><dd>{{ payment.payment.bank }}</dd></div></dl>
            <div v-if="payment.payment.qrCodeUrl" class="yanhuo-qr"><img :src="payment.payment.qrCodeUrl" width="240" height="240" alt="收款方提供的付款二维码"></div>
            <p class="yanhuo-note">{{ payment.payment.instructions || '请按收款信息付款，并向客服提供订单号与付款回单。核实实际收款后到账。' }}</p>
          </template>
          <p class="yanhuo-note">有效至 {{ dateTime(payment.expiresAt) }}</p>
          <div class="yanhuo-payment-actions"><button class="yanhuo-link" type="button" :disabled="paymentLoading" @click="pollPayment(true)"><RefreshCw :size="14" />刷新支付状态</button><button class="yanhuo-link" type="button" :disabled="paymentLoading" @click="cancelPayment">取消订单</button></div>
        </template>
        <div v-else class="yanhuo-payment-result"><strong>{{ labels[payment.state] }}</strong></div>
        <p v-if="paymentError" class="yanhuo-alert" role="alert">{{ paymentError }}</p><p class="yanhuo-order-id">{{ payment.id }}</p>
      </template>
    </dialog>
  </div>
</template>
