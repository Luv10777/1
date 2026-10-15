<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowDownLeft, ArrowUpRight, Check, CircleHelp, RefreshCw, Wallet, X } from 'lucide-vue-next'
import QRCode from 'qrcode'
import { billing, billingApi, dateTime, loadBillingPlans, number, refreshBilling, yuan } from '../stores/billing'
import BillingPlanManager from '../components/BillingPlanManager.vue'

const route = useRoute()
const account = computed(() => billing.summary)
const returnTo = computed(() => typeof route.query.redirect === 'string'
  && route.query.redirect.startsWith('/') && !route.query.redirect.startsWith('//')
  ? route.query.redirect : '/dashboard')
const amount = ref(50)
const preset = [10, 50, 100, 300]
const validAmount = computed(() => Number.isInteger(amount.value) && amount.value >= 1 && amount.value <= 100000)
const topupCredits = computed(() => validAmount.value ? amount.value * (account.value?.creditsPerYuan || 10) : 0)
const busy = ref(false)
const error = ref('')
const plansError = ref('')
const recordsError = ref('')
const tab = ref('ledger')
const ledger = ref([])
const orders = ref([])
const pages = ref({ ledger: 0, orders: 0 })
const hasMore = ref({ ledger: false, orders: false })
const recordsLoading = ref(false)
const payment = ref(null)
const qr = ref('')
const paymentError = ref('')
const paymentLoading = ref(false)
const paymentDialog = ref(null)
const topupInput = ref(null)
const refreshButton = ref(null)
let paymentTimer
let balanceTimer
let disposed = false
let qrVersion = 0
const keys = new Map()
const labels = { PENDING_PAYMENT: '待支付', PAID: '已到账', CANCELLED: '已取消', EXPIRED: '已过期' }
const entryLabels = { RESERVE: '预占（未扣费）', CAPTURE: '消耗', RELEASE: '释放预占', GRANT: '赠送', PURCHASE: '充值' }
const membershipLabel = computed(() => account.value?.memberActive ? '会员生效中' : account.value?.plan ? '会员已到期' : '尚未开通会员')
const canPay = computed(() => account.value?.canManage && account.value?.payment.available && !billing.error && !busy.value)
const paymentLabel = computed(() => account.value?.paymentMethod === 'MANUAL' ? '查看付款信息' : '微信支付')
const keyFor = (key) => {
  if (!keys.has(key)) keys.set(key, 'billing-' + crypto.randomUUID())
  return keys.get(key)
}
const reloadPlans = async () => {
  try { await loadBillingPlans(); plansError.value = '' }
  catch (e) { plansError.value = e.message || '套餐暂时无法读取' }
}
const loadRecords = async (more = false) => {
  if (recordsLoading.value) return
  const kind = tab.value
  recordsLoading.value = true
  recordsError.value = ''
  const page = more ? pages.value[kind] + 1 : 0
  try {
    const result = await billingApi[kind](page)
    if (disposed) return
    const list = kind === 'ledger' ? ledger : orders
    list.value = more ? [...list.value, ...result.items] : result.items
    pages.value[kind] = page
    hasMore.value[kind] = result.hasMore
  } catch (e) { recordsError.value = e.message || '账务记录暂时无法读取' }
  finally { recordsLoading.value = false }
}
const selectTab = (value) => { tab.value = value; loadRecords() }
const refresh = async () => { await refreshBilling(); await reloadPlans(); await loadRecords() }
const closePayment = () => { clearTimeout(paymentTimer); paymentDialog.value?.close(); payment.value = null; qr.value = ''; qrVersion += 1; refreshButton.value?.focus() }
const applyPayment = async (order) => {
  if (disposed || !payment.value) return
  payment.value = order
  if (['PAID', 'CANCELLED', 'EXPIRED'].includes(order.state)) keys.delete(order.kind === 'TOPUP' ? 'topup:' + order.amountMinor / 100 : 'plan:' + order.plan?.id)
  if (order.state === 'PAID') {
    keys.clear()
    qr.value = ''
    await refreshBilling()
    await loadRecords()
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
const topUp = async () => {
  if (!validAmount.value || !canPay.value) return
  busy.value = true; error.value = ''
  try { await openPayment(await billingApi.topUp({ requestKey: keyFor('topup:' + amount.value), amountMinor: amount.value * 100 })); await loadRecords() }
  catch (e) { error.value = e.message || '充值订单创建失败'; await loadRecords() }
  finally { busy.value = false }
}
const subscribe = async (plan) => {
  if (!canPay.value) return
  busy.value = true; error.value = ''
  try { await openPayment(await billingApi.subscribe({ requestKey: keyFor('plan:' + plan.id), planId: plan.id })); await loadRecords() }
  catch (e) { error.value = e.message || '订阅订单创建失败'; await loadRecords() }
  finally { busy.value = false }
}
const resumeOrder = async (order) => {
  error.value = ''; busy.value = true
  try { await openPayment(await billingApi.refresh(order.id)) }
  catch (e) { error.value = e.message || '支付订单暂时无法读取' }
  finally { busy.value = false }
}
const cancelPayment = async () => {
  if (!payment.value || paymentLoading.value) return
  paymentLoading.value = true
  try { await applyPayment(await billingApi.cancel(payment.value.id)); await loadRecords(); paymentError.value = '' }
  catch (e) { paymentError.value = e.message || '订单取消失败，请刷新支付状态' }
  finally { paymentLoading.value = false }
}
const entryAmount = (entry) => entry.availableDelta + entry.heldDelta
onMounted(async () => {
  await refresh()
  if (route.query.topup) topupInput.value?.focus()
  balanceTimer = setInterval(() => { if (document.visibilityState === 'visible') refreshBilling() }, 30000)
})
onBeforeUnmount(() => { disposed = true; clearTimeout(paymentTimer); clearInterval(balanceTimer); qrVersion += 1 })
</script>

<template>
  <div class="yanhuo-page">
    <header class="yanhuo-heading">
      <div><p class="eyebrow">一方志 · 我的账户</p><h1>套餐与权益</h1></div>
      <button ref="refreshButton" class="yanhuo-icon" type="button" title="刷新账户" aria-label="刷新账户" :disabled="billing.loading" @click="refresh"><RefreshCw :size="18" :class="{ spinning: billing.loading }" /></button>
    </header>
    <div v-if="route.query.membership && !account?.memberActive" class="yanhuo-alert" role="status">{{ route.query.membership === 'unavailable' ? '暂时无法确认会员状态，请重新读取账户后继续。' : `使用${route.query.module || '平台功能'}需要先开通会员。充值烟火不会自动开通会员。` }}</div>
    <RouterLink v-if="route.query.membership && account?.memberActive" :to="returnTo" class="generation-billing-link">会员已生效，继续使用{{ route.query.module || '平台功能' }} →</RouterLink>
    <div v-if="billing.error" class="yanhuo-alert" role="alert">{{ billing.error }}<button type="button" @click="refresh">重新读取</button></div>
    <div v-else-if="!account" class="yanhuo-empty" role="status">正在读取烟火账户...</div>
    <template v-if="account">
      <section class="yanhuo-account" aria-label="烟火与会员">
        <div class="yanhuo-balance">
          <div class="yanhuo-caption"><Wallet :size="17" /><span>可用烟火</span></div>
          <p class="yanhuo-number">{{ billing.error ? '--' : number(account.available) }}<small>烟火</small></p>
          <dl class="yanhuo-sources"><div><dt>会员赠送</dt><dd>{{ number(account.giftAvailable) }}</dd></div><div><dt>充值余额</dt><dd>{{ number(account.purchasedAvailable) }}</dd></div><div><dt>生成中预占</dt><dd>{{ number(account.held) }}</dd></div></dl>
        </div>
        <div class="yanhuo-membership">
          <span class="yanhuo-status" :class="{ active: account.memberActive }"><span />{{ membershipLabel }}</span>
          <h2>{{ account.plan?.name || '会员套餐待订阅' }}</h2>
          <p v-if="account.memberUntil">有效期至 {{ dateTime(account.memberUntil) }}</p>
          <p v-else>信息发布与商品购买无需会员；AI 创作需开通会员，再按规则使用烟火。</p>
          <div class="yanhuo-entitlements"><span :class="{ enabled: account.memberActive && account.plan?.imageEnabled }"><Check :size="14" />图片创作</span><span :class="{ enabled: account.memberActive && account.plan?.videoEnabled }"><Check :size="14" />视频创作</span></div>
        </div>
        <div class="yanhuo-rules">
          <h3>烟火计费</h3>
          <p><strong>1 元 = {{ account.creditsPerYuan }} 烟火</strong><br>每张图片 {{ account.imageCredits }} 烟火，优先使用会员赠送余额。</p>
          <p>视频反推每次 {{ account.videoAnalysisCredits }} 烟火，分析成功后扣除，失败或取消退回。</p>
          <p>视频生成以确认的报价预占烟火，交付后结算。其他模块按正式公布的规则计费。</p>
          <span><CircleHelp :size="14" />充值烟火可保留，创作需有效会员。</span>
        </div>
      </section>
      <section id="topup" class="yanhuo-topup">
        <div class="yanhuo-section-title"><h2>添一份烟火</h2><p>充值烟火</p></div>
        <p v-if="account.canManage && !account.memberActive" class="yanhuo-alert yanhuo-topup-warning" role="status">充值只增加烟火余额，不会开通或恢复会员。使用 AI 创作等会员功能前，请先<a href="#member-plans" class="yanhuo-link">订阅或续订套餐</a>。</p>
        <form @submit.prevent="topUp">
          <fieldset class="yanhuo-amounts"><legend class="sr-only">充值金额</legend><label v-for="value in preset" :key="value" :class="{ selected: amount === value }"><input v-model="amount" type="radio" :value="value" name="topup-amount"><span>{{ yuan(value * 100) }}</span><small>{{ number(value * account.creditsPerYuan) }} 烟火</small></label></fieldset>
          <div class="yanhuo-topup-row"><label class="yanhuo-custom"><span>自定义金额</span><span class="yanhuo-money-input"><span>¥</span><input ref="topupInput" v-model.number="amount" inputmode="numeric" type="number" min="1" max="100000" step="1" required aria-label="充值金额，单位元"></span></label><div class="yanhuo-topup-total"><span>到账烟火</span><strong>{{ number(topupCredits) }}</strong></div><button class="yanhuo-primary" type="submit" :disabled="!canPay || !validAmount"><Wallet :size="16" />{{ busy ? '正在创建订单...' : paymentLabel }}</button></div>
          <p v-if="!account.canManage" class="yanhuo-note">充值与订阅由账户管理员办理。</p><p v-else-if="!account.payment.available" class="yanhuo-note">微信收款暂未开放。</p>
        </form>
      </section>
      <section id="member-plans" class="yanhuo-catalog">
        <div class="yanhuo-section-title"><h2>会员套餐</h2><p>订阅与续期</p></div>
        <div v-if="plansError" class="yanhuo-alert" role="alert">{{ plansError }}<button type="button" @click="reloadPlans">重新读取</button></div>
        <div v-else-if="!billing.plans.length" class="yanhuo-empty"><img src="/images/brand/yifangzhi-mark.png" alt="" width="36" height="36"><h3>套餐尚未开放</h3><p>正式套餐公布后，可在这里订阅。</p></div>
        <div v-else class="yanhuo-plan-grid"><article v-for="plan in billing.plans" :key="plan.id" class="yanhuo-plan"><div class="yanhuo-plan-top"><h3>{{ plan.name }}</h3><span v-if="account.plan?.code === plan.code && account.memberActive" class="yanhuo-current">当前套餐</span></div><p class="yanhuo-plan-price">{{ yuan(plan.priceMinor) }}<small>/ {{ plan.durationDays }} 天</small></p><p class="yanhuo-plan-description">{{ plan.description }}</p><ul><li><Check :size="15" />赠送 {{ number(plan.giftCredits) }} 烟火</li><li v-if="plan.imageEnabled"><Check :size="15" />图片创作</li><li v-if="plan.videoEnabled"><Check :size="15" />视频创作</li></ul><button class="yanhuo-primary" type="button" :disabled="!canPay || (account.memberActive && account.plan?.code !== plan.code)" @click="subscribe(plan)">{{ account.memberActive && account.plan?.code === plan.code ? '续订套餐' : account.memberActive ? '到期后可订阅' : '订阅套餐' }}<ArrowUpRight :size="16" /></button></article></div>
      </section>
      <p v-if="error" class="yanhuo-alert" role="alert">{{ error }}</p>
      <section class="yanhuo-records">
        <div class="yanhuo-records-head"><h2>账户记录</h2><div class="yanhuo-tabs" role="tablist" aria-label="账务记录"><button id="ledger-tab" type="button" role="tab" :aria-selected="tab === 'ledger'" aria-controls="billing-records" @click="selectTab('ledger')">烟火明细</button><button v-if="account.canManage" id="orders-tab" type="button" role="tab" :aria-selected="tab === 'orders'" aria-controls="billing-records" @click="selectTab('orders')">支付订单</button></div></div>
        <div v-if="recordsError" class="yanhuo-alert" role="alert">{{ recordsError }}<button type="button" @click="loadRecords()">重新读取</button></div>
        <div id="billing-records" role="tabpanel" :aria-labelledby="tab + '-tab'" class="yanhuo-table-wrap">
          <table v-if="tab === 'ledger' && ledger.length" class="yanhuo-table"><thead><tr><th>时间</th><th>内容</th><th>类型</th><th class="numeric">烟火收支</th><th class="numeric">可用余额</th></tr></thead><tbody><tr v-for="entry in ledger" :key="entry.id"><td class="time">{{ dateTime(entry.createdAt) }}</td><td>{{ entry.description }}</td><td><span class="yanhuo-entry-type">{{ entryLabels[entry.type] || entry.type }}</span></td><td class="numeric" :class="{ positive: entryAmount(entry) > 0 }">{{ entryAmount(entry) > 0 ? '+' : '' }}{{ number(entryAmount(entry)) }}<small v-if="entry.heldDelta" class="yanhuo-held-change">预占 {{ entry.heldDelta > 0 ? '+' : '' }}{{ number(entry.heldDelta) }}</small></td><td class="numeric">{{ number(entry.availableAfter) }}</td></tr></tbody></table>
          <table v-else-if="tab === 'orders' && orders.length" class="yanhuo-table"><thead><tr><th>时间 / 订单</th><th>内容</th><th class="numeric">金额</th><th>状态</th><th><span class="sr-only">订单操作</span></th></tr></thead><tbody><tr v-for="order in orders" :key="order.id"><td class="time">{{ dateTime(order.createdAt) }}<small class="yanhuo-order-id">{{ order.id }}</small></td><td>{{ order.kind === 'TOPUP' ? '充值 ' + number(order.credits) + ' 烟火' : order.plan?.name }}</td><td class="numeric">{{ yuan(order.amountMinor) }}</td><td><span :class="{ positive: order.state === 'PAID' }">{{ labels[order.state] || order.state }}</span></td><td><button v-if="order.state === 'PENDING_PAYMENT'" class="yanhuo-link" type="button" :disabled="busy" @click="resumeOrder(order)">继续支付<ArrowUpRight :size="14" /></button><button v-else class="yanhuo-link" type="button" :disabled="busy" @click="resumeOrder(order)">详情<ArrowUpRight :size="14" /></button></td></tr></tbody></table>
          <div v-else class="yanhuo-empty small" role="status">{{ recordsLoading ? '正在读取记录...' : tab === 'ledger' ? '暂无烟火明细' : '暂无支付订单' }}</div>
        </div>
        <p v-if="tab === 'ledger' && ledger.length" class="yanhuo-note">预占与释放只在可用余额和预占余额之间转移，烟火收支为 0；成功交付后才记录消耗。</p>
        <button v-if="hasMore[tab]" class="yanhuo-more" type="button" :disabled="recordsLoading" @click="loadRecords(true)">{{ recordsLoading ? '正在读取...' : '查看更多' }}<ArrowDownLeft :size="14" /></button>
      </section>
      <BillingPlanManager v-if="account.operator" @updated="reloadPlans" />
    </template>
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
