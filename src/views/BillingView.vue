<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowUpRight, Check, RefreshCw, Wallet, X } from 'lucide-vue-next'
import QRCode from 'qrcode'
import { billing, billingApi, dateTime, loadBillingPlans, number, refreshBilling, yuan } from '../stores/billing'
import BillingPlanManager from '../components/BillingPlanManager.vue'
import BillingCheckoutTerms from '../components/BillingCheckoutTerms.vue'
import YanhuoIcon from '../components/YanhuoIcon.vue'
import { auth } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const account = computed(() => billing.summary)
const returnTo = computed(() => typeof route.query.redirect === 'string'
  && route.query.redirect.startsWith('/') && !route.query.redirect.startsWith('//')
  ? route.query.redirect : '/dashboard')
const amount = ref(50)
const purchaseConsent = ref(false)
const planTerms = ref(null)
const topupTerms = ref(null)
watch(() => [auth.user?.id, auth.tenantId], () => { purchaseConsent.value = false }, { flush: 'sync' })
const preset = [10, 50, 100, 300]
const validAmount = computed(() => Number.isInteger(amount.value) && amount.value >= 1 && amount.value <= 100000)
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
const topupDialog = ref(null)
const pendingPlan = ref(null)
const refreshButton = ref(null)
let paymentTimer
let balanceTimer
let disposed = false
let qrVersion = 0
const keys = new Map()
const labels = { PENDING_PAYMENT: '待支付', PAID: '已到账', CANCELLED: '已取消', EXPIRED: '已过期' }
const membershipLabel = computed(() => account.value?.memberActive ? '会员生效中' : account.value?.plan ? '会员已到期' : '尚未开通会员')
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
const closePayment = () => { clearTimeout(paymentTimer); paymentDialog.value?.close(); payment.value = null; qr.value = ''; qrVersion += 1; refreshButton.value?.focus() }
const applyPayment = async (order) => {
  if (disposed || !payment.value) return
  payment.value = order
  if (['PAID', 'CANCELLED', 'EXPIRED'].includes(order.state)) keys.delete(order.kind === 'TOPUP' ? 'topup:' + order.amountMinor / 100 : 'plan:' + order.plan?.id)
  if (order.state === 'PAID') {
    keys.clear()
    qr.value = ''
    await refreshBilling()
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
const openPayment = async (order) => {
  payment.value = order
  paymentError.value = ''
  qr.value = ''
  await nextTick()
  paymentDialog.value?.showModal()
  await applyPayment(order)
  if (order.state === 'PENDING_PAYMENT') paymentTimer = setTimeout(pollPayment, 4000)
}
// The tab opens inside the click so popup blockers allow it; the signed URL is fetched fresh because Alipay rejects stale ones.
const openAlipay = async () => {
  if (!payment.value || paymentLoading.value) return
  const tab = window.open('', '_blank')
  if (!tab) { paymentError.value = '浏览器拦截了新窗口，请允许本站弹出窗口后重试'; return }
  tab.opener = null
  paymentLoading.value = true
  try { tab.location.href = await billingApi.alipay(payment.value.id); paymentError.value = '' }
  catch (e) { tab.close(); paymentError.value = e.message || '支付宝收银台暂时无法打开' }
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
  if (route.query.topup && account.value) openTopup()
  balanceTimer = setInterval(() => { if (document.visibilityState === 'visible') refreshBilling() }, 30000)
})
onBeforeUnmount(() => { disposed = true; clearTimeout(paymentTimer); clearInterval(balanceTimer); qrVersion += 1 })
</script>

<template>
  <div class="yanhuo-page">
    <header class="yanhuo-heading">
      <div>
        <h1>套餐与权益</h1>
        <p class="yanhuo-intro">开通会员后即可使用 AI 创作，创作按烟火计费，会员赠送的烟火优先使用。</p>
      </div>
      <button ref="refreshButton" class="yanhuo-icon" type="button" title="刷新账户" aria-label="刷新账户" :disabled="billing.loading" @click="refresh"><RefreshCw :size="18" :class="{ spinning: billing.loading }" /></button>
    </header>
    <div v-if="route.query.membership && !account?.memberActive" class="yanhuo-alert" role="status">{{ route.query.membership === 'unavailable' ? '暂时无法确认会员状态，请重新读取账户后继续。' : `使用${route.query.module || '平台功能'}需要先开通会员。充值烟火不会自动开通会员。` }}</div>
    <RouterLink v-if="route.query.membership && account?.memberActive" :to="returnTo" class="generation-billing-link">会员已生效，继续使用{{ route.query.module || '平台功能' }} →</RouterLink>
    <div v-if="billing.error" class="yanhuo-alert" role="alert">{{ billing.error }}<button type="button" @click="refresh">重新读取</button></div>
    <div v-else-if="!account" class="yanhuo-empty" role="status">正在读取烟火账户...</div>
    <template v-if="account">
      <section class="yanhuo-overview" aria-label="会员与烟火">
        <article class="yanhuo-card yanhuo-member-card">
          <div class="yanhuo-card-head">
            <span class="yanhuo-card-label">会员</span>
            <span class="yanhuo-status" :class="{ active: account.memberActive }"><span />{{ membershipLabel }}</span>
          </div>
          <h2>{{ account.plan?.name || '尚未订阅套餐' }}</h2>
          <p v-if="account.memberUntil" class="yanhuo-card-meta">有效期至 {{ dateTime(account.memberUntil) }}</p>
          <p v-else class="yanhuo-card-meta">信息发布与商品购买无需会员；AI 创作需开通会员，再按规则使用烟火。</p>
          <ul class="yanhuo-entitlements">
            <li :class="{ enabled: account.memberActive && account.plan?.imageEnabled }"><Check :size="14" />图片创作</li>
            <li :class="{ enabled: account.memberActive && account.plan?.videoEnabled }"><Check :size="14" />视频创作</li>
            <li :class="{ enabled: account.memberActive && account.plan?.liveEnabled }"><Check :size="14" />直播</li>
          </ul>
          <a href="#member-plans" class="yanhuo-secondary">{{ account.memberActive ? '续订套餐' : '选择套餐' }}<ArrowUpRight :size="15" /></a>
        </article>
        <article class="yanhuo-card yanhuo-balance-card">
          <div class="yanhuo-card-head">
            <span class="yanhuo-card-label">可用烟火</span>
          </div>
          <p class="yanhuo-number"><YanhuoIcon :size="30" />{{ number(account.available) }}</p>
          <dl class="yanhuo-sources"><div><dt>会员赠送</dt><dd>{{ number(account.giftAvailable) }}</dd></div><div><dt>充值余额</dt><dd>{{ number(account.purchasedAvailable) }}</dd></div><div><dt>生成中预占</dt><dd>{{ number(account.held) }}</dd></div></dl>
          <button type="button" class="yanhuo-secondary" @click="openTopup">充值烟火<ArrowUpRight :size="15" /></button>
        </article>
      </section>

      <section id="member-plans" class="yanhuo-section" aria-labelledby="plans-title">
        <header class="yanhuo-section-head"><h2 id="plans-title">会员套餐</h2><p>订阅与续期，套餐赠送的烟火在开通时发放。</p></header>
        <div v-if="plansError" class="yanhuo-alert" role="alert">{{ plansError }}<button type="button" @click="reloadPlans">重新读取</button></div>
        <div v-else-if="!billing.plans.length" class="yanhuo-card yanhuo-empty"><img src="/images/brand/yifangzhi-mark.png" alt="" width="36" height="36"><h3>套餐尚未开放</h3><p>正式套餐公布后，可在这里订阅。</p></div>
        <div v-else class="yanhuo-plan-grid">
          <article v-for="plan in billing.plans" :key="plan.id" class="yanhuo-card yanhuo-plan" :class="{ 'is-current': account.plan?.code === plan.code && account.memberActive }">
            <div class="yanhuo-plan-top"><h3>{{ plan.name }}</h3><span v-if="account.plan?.code === plan.code && account.memberActive" class="yanhuo-current">当前套餐</span></div>
            <p class="yanhuo-plan-price">{{ yuan(plan.priceMinor) }}<small>/ {{ plan.durationDays }} 天</small></p>
            <p class="yanhuo-plan-description">{{ plan.description }}</p>
            <ul><li><Check :size="15" />赠送 {{ number(plan.giftCredits) }} 烟火</li><li v-if="plan.imageEnabled"><Check :size="15" />图片创作</li><li v-if="plan.videoEnabled"><Check :size="15" />视频创作</li><li v-if="plan.liveEnabled"><Check :size="15" />直播（{{ account.liveCreditsPerHour }} 烟火 / 小时）</li><li v-if="plan.allianceQuotaMinor"><Check :size="15" />联盟券额度 ¥{{ (plan.allianceQuotaMinor / 100).toFixed(2) }} / 期</li></ul>
            <button class="yanhuo-primary" type="button" :disabled="!canPay || (account.memberActive && account.plan?.code !== plan.code)" @click="choosePlan(plan)">{{ account.memberActive && account.plan?.code === plan.code ? '续订套餐' : account.memberActive ? '到期后可订阅' : '订阅套餐' }}<ArrowUpRight :size="16" /></button>
          </article>
        </div>
      </section>


      <section class="yanhuo-section" aria-labelledby="rules-title">
        <header class="yanhuo-section-head"><h2 id="rules-title">计费说明</h2><p>创作需有效会员，优先使用会员赠送的烟火。</p></header>
        <dl class="yanhuo-rule-grid">
          <div><dt>图片创作</dt><dd><strong><YanhuoIcon :size="14" />{{ account.imageCredits }} / 张</strong>生成中先预占，未交付的退回。</dd></div>
          <div><dt>视频反推</dt><dd><strong><YanhuoIcon :size="14" />{{ account.videoAnalysisCredits }} / 次</strong>分析成功后扣除，失败或取消退回。</dd></div>
          <div><dt>视频生成</dt><dd><strong>按确认的报价</strong>先预占烟火，交付后按实际用量结算。</dd></div>
          <div><dt>直播</dt><dd><strong><YanhuoIcon :size="14" />{{ account.liveCreditsPerHour }} / 小时</strong>开播先预占一小时，按分钟结算，暂停不计费。</dd></div>
        </dl>
        <p class="yanhuo-note">其他模块按正式公布的规则计费。</p>
      </section>
      <BillingPlanManager v-if="account.operator" @updated="reloadPlans" />
    </template>
    <dialog ref="topupDialog" class="yanhuo-payment yanhuo-topup-dialog" aria-labelledby="topup-title" @cancel.prevent="closeTopup" @click="event => { if (event.target === topupDialog) closeTopup() }">
      <template v-if="account">
        <header><img src="/images/brand/yifangzhi-mark.png" alt="" width="32" height="32"><h2 id="topup-title">充值烟火</h2><button class="yanhuo-icon" type="button" aria-label="关闭充值窗口" title="关闭" @click="closeTopup"><X :size="20" /></button></header>
        <p class="yanhuo-topup-rate">1 元 = {{ account.creditsPerYuan }} 烟火，充值的烟火长期有效。</p>
        <p v-if="account.canManage && !account.memberActive" class="yanhuo-alert yanhuo-topup-warning" role="status">充值只增加烟火余额，不会开通或恢复会员。使用 AI 创作等会员功能前，请先<a href="#member-plans" class="yanhuo-link" @click="closeTopup">订阅或续订套餐</a>。</p>
        <form class="yanhuo-topup-form" @submit.prevent="topUp">
          <fieldset class="yanhuo-amounts"><legend class="sr-only">充值金额</legend><label v-for="value in preset" :key="value" :class="{ selected: amount === value }"><input v-model="amount" type="radio" :value="value" name="topup-amount"><span>{{ yuan(value * 100) }}</span><small><YanhuoIcon :size="12" />{{ number(value * account.creditsPerYuan) }}</small></label></fieldset>
          <div class="yanhuo-topup-row"><label class="yanhuo-custom"><span>自定义金额</span><span class="yanhuo-money-input"><span>¥</span><input v-model.number="amount" inputmode="numeric" type="number" min="1" max="100000" step="1" required aria-label="充值金额，单位元"></span></label><div class="yanhuo-topup-total"><span>到账烟火</span><strong><YanhuoIcon :size="20" />{{ number(topupCredits) }}</strong></div></div>
          <BillingCheckoutTerms ref="topupTerms" v-model="purchaseConsent" :method="account.paymentMethod" :available="account.payment.available" :can-manage="account.canManage" :disabled="busy" :error="errorAt === 'topup' ? error : ''">
            <div class="yanhuo-checkout-pay"><span class="yanhuo-checkout-due">应付<strong>{{ validAmount ? yuan(amount * 100) : '—' }}</strong></span><button class="yanhuo-primary" type="submit" :disabled="!canPay || !validAmount"><Wallet :size="16" />{{ busy ? '正在创建订单...' : '确认支付' }}</button></div>
          </BillingCheckoutTerms>
        </form>
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
            <button class="yanhuo-primary" type="button" :disabled="paymentLoading" @click="openAlipay">前往支付宝付款<ArrowUpRight :size="16" /></button>
            <p class="yanhuo-payment-caption">在新窗口完成付款后回到这里，到账后自动更新</p>
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
