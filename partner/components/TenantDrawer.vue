<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { Activity, Check, Coins, History, ListChecks, RefreshCw, Store } from 'lucide-vue-next'
import { partnerApi, tenantSources } from '../../src/services/partnerApi.js'
import { managementDate } from '../../src/domain/platformManagement.js'
import { accountSplit, capabilityBars, dateText, membershipState, number, renewedUntil, sinceText, tenantTimeline, transferError, transferPresets } from '../../src/domain/partnerOps.js'
import { can, loadPartner, partner } from '../partnerStore.js'
import MerchantCrm from './MerchantCrm.vue'
import RemarkField from '../../src/components/RemarkField.vue'
import PartnerPanel from './PartnerPanel.vue'
import ConfirmBox from './ConfirmBox.vue'
import OptionCards from './OptionCards.vue'

/**
 * 商户详情抽屉：会员续期、分配烟火（有分配权限的人，从团队账户扣）、往来记录、档案跟进。
 * 每项操作先填写，再在页内确认改动结果，最后提交。改完通过 updated 把新的商户行交回列表。
 */
const props = defineProps({ tenantId: { type: Number, default: null }, tab: { type: String, default: 'overview' } })
const emit = defineEmits(['close', 'updated'])

const distributor = computed(() => can('distribute'))
const seesMoney = computed(() => can('distribute') || can('finance'))
// 详情分页签；跟进第一次切过去才加载，之后切走也保留，已填的内容不丢。
const tabs = computed(() => [{ key: 'overview', label: '概况' }, { key: 'follow', label: '跟进' },
  { key: 'service', label: distributor.value ? '续期与烟火' : '会员续期' }])
const activeTab = ref('overview')
const seen = reactive(new Set())
function pickTab(key) { activeTab.value = key; seen.add(key) }
const detail = ref(null)
const loading = ref(false)
const error = ref('')
const busy = ref(false)
const notice = ref('')
const tenant = computed(() => detail.value?.tenant || null)
const available = computed(() => partner.overview?.account?.available || 0)
const split = computed(() => accountSplit(partner.overview?.account, Number(transfer.credits) || 0))
const plans = computed(() => partner.overview?.plans || [])
const timeline = computed(() => tenantTimeline(detail.value))
const state = computed(() => tenant.value ? membershipState(tenant.value) : null)
const usage = computed(() => detail.value?.usage || null)
const usageBars = computed(() => capabilityBars(usage.value?.consumedByCapability))
const steps = computed(() => {
  const o = detail.value?.onboarding
  if (!o) return []
  return [
    { done: o.activated, title: '开通会员', hint: o.activated ? '已开通过' : `可以在「${tabs.value[2].label}」里直接开通` },
    { done: o.stores > 0, title: '建立门店', hint: o.stores > 0 ? `${o.stores} 家门店` : '还没有门店' },
    { done: o.products > 0, title: '上传商品', hint: o.products > 0 ? `${o.products} 个商品` : '还没有商品资料' },
    { done: o.firstUse, title: '完成第一次创作', hint: o.firstUse ? `首次使用 ${dateText(usage.value?.firstUsedAt)}` : '还没用过 AI 功能' },
  ]
})

const renewal = reactive({ planCode: '', requestKey: '', confirming: false, error: '' })
const transfer = reactive({ credits: '', note: '', requestKey: '', confirming: false, error: '' })
const renewPlan = computed(() => plans.value.find(p => p.planCode === renewal.planCode))
const renewTo = computed(() => tenant.value && renewPlan.value ? renewedUntil(tenant.value, renewPlan.value.durationDays) : null)
const planOptions = computed(() => plans.value.map(p => ({ value: p.planCode, title: `${p.planName} · ${p.durationDays} 天`, detail: '不限个数' })))

function reset() {
  Object.assign(renewal, { planCode: plans.value[0]?.planCode || '', requestKey: 'renew-' + crypto.randomUUID(), confirming: false, error: '' })
  Object.assign(transfer, { credits: '', note: '', requestKey: 'transfer-' + crypto.randomUUID(), confirming: false, error: '' })
}

async function load() {
  if (!props.tenantId) return
  loading.value = true
  error.value = ''
  try {
    detail.value = await partnerApi.tenant(props.tenantId)
    reset()
  } catch (failure) {
    error.value = failure.message || '商户详情暂时无法读取。'
  } finally { loading.value = false }
}
watch(() => props.tenantId, id => {
  detail.value = null; notice.value = ''; seen.clear()
  if (id) { pickTab(tabs.value.some(t => t.key === props.tab) ? props.tab : 'overview'); load() }
}, { immediate: true })

async function refresh(message) {
  notice.value = message
  const [fresh] = await Promise.all([partnerApi.tenant(props.tenantId), loadPartner(true)])
  detail.value = fresh
  emit('updated', fresh.tenant)
  reset()
}

// 备注名团队共用；改完把新的商户行交回列表。
async function saveRemark(remark) {
  const row = await partnerApi.setRemark(props.tenantId, remark)
  detail.value = { ...detail.value, tenant: row }
  emit('updated', row)
}

function askRenew() {
  renewal.error = renewPlan.value ? '' : '请选择套餐。'
  renewal.confirming = !renewal.error
}
async function renew() {
  busy.value = true
  try {
    await partnerApi.renew(props.tenantId, { requestKey: renewal.requestKey, planCode: renewal.planCode })
    await refresh(`已续期，会员到 ${dateText(renewTo.value)}`)
  } catch (failure) {
    renewal.error = failure.message || '续期失败，请稍后再确认一次（不会重复顺延）。'
    renewal.confirming = false
  } finally { busy.value = false }
}

function askTransfer() {
  transfer.error = transferError(transfer.credits, available.value)
  transfer.confirming = !transfer.error
}
async function sendCredits() {
  busy.value = true
  const credits = Number(transfer.credits)
  try {
    await partnerApi.transfer(props.tenantId, { requestKey: transfer.requestKey, credits, note: transfer.note.trim() || null })
    await refresh(`已分配 ${number(credits)} 烟火`)
  } catch (failure) {
    // 保留同一个 requestKey：再确认一次只会补上没到账的部分，不会重复扣。
    transfer.error = `${failure.message || '分配失败'}，可以直接再确认一次，不会重复扣烟火。`
    transfer.confirming = false
  } finally { busy.value = false }
}

</script>

<template>
  <PartnerPanel :open="Boolean(tenantId)" :busy="busy" :title="tenant?.remark || tenant?.tenantName || '商户详情'" :eyebrow="tenant ? `${tenant.remark ? `${tenant.tenantName || '—'} · ` : ''}商户 #${tenant.tenantId}` : ''" @close="emit('close')">
    <template v-if="tenant" #summary>
      <span :class="['partner-member', state.tone]">{{ state.text }}</span>
      <span>{{ tenant.ownerName || '—' }} · UID {{ tenant.ownerUid || '—' }}</span>
      <span v-if="tenant.addedByName" class="partner-pill">{{ tenant.addedByName }} 添加</span>
    </template>

    <div v-if="loading && !detail" class="mg-state"><p>正在读取…</p></div>
    <div v-else-if="error" class="mg-state mg-error" role="alert"><p>{{ error }}</p><button class="mg-button" type="button" @click="load"><RefreshCw :size="15" />重新读取</button></div>
    <template v-else-if="tenant">
      <nav class="management-tabs partner-drawer-tabs" aria-label="商户详情">
        <button v-for="t in tabs" :key="t.key" type="button" :class="{ active: activeTab === t.key }" :aria-pressed="activeTab === t.key" @click="pickTab(t.key)">{{ t.label }}</button>
      </nav>
      <div v-if="notice" class="mg-feedback success" role="status"><Check :size="16" />{{ notice }}</div>

      <template v-if="activeTab === 'overview'">
        <section class="mg-detail-section">
          <h3><Store :size="17" />基本信息</h3>
          <dl class="mg-detail-grid">
            <div class="full"><dt>备注名</dt><dd><RemarkField :value="tenant.remark || ''" :save="saveRemark" :disabled="busy" placeholder="例如：西湖店、张总介绍的" /></dd></div>
            <div><dt>商户名</dt><dd>{{ tenant.tenantName || '—' }}</dd></div>
            <div><dt>联系人</dt><dd>{{ tenant.ownerName || '—' }}</dd></div>
            <div><dt>联系人 UID</dt><dd class="mg-mono">{{ tenant.ownerUid || '—' }}</dd></div>
            <div><dt>归属方式</dt><dd>{{ tenantSources[tenant.source] || tenant.source }}</dd></div>
            <div><dt>加入时间</dt><dd>{{ managementDate(tenant.boundAt) }}</dd></div>
            <div><dt>烟火余额</dt><dd class="mg-mono">{{ tenant.creditsAvailable == null ? '—' : number(tenant.creditsAvailable) }}</dd></div>
            <div><dt>会员套餐</dt><dd>{{ tenant.memberActive ? tenant.planName || '会员' : state.short }}</dd></div>
          </dl>
        </section>

        <section v-if="usage" class="mg-detail-section" aria-labelledby="usage-title">
          <h3 id="usage-title"><Activity :size="17" />使用情况（近 {{ usage.days }} 天）</h3>
          <div class="partner-stats">
            <div><span>消耗烟火</span><strong>{{ number(usage.consumed) }}</strong></div>
            <div><span>使用次数</span><strong>{{ number(usage.uses) }}</strong></div>
            <div><span>最近使用</span><strong class="partner-stat-text">{{ sinceText(usage.lastUsedAt) }}</strong></div>
            <div :class="{ warn: usage.lowBalance }"><span>{{ usage.lowBalance ? '余额不足' : '当前余额' }}</span><strong>{{ number(tenant.creditsAvailable) }}</strong></div>
          </div>
          <ul v-if="usageBars.length" class="partner-hbars">
            <li v-for="b in usageBars" :key="b.key"><span>{{ b.label }}</span><i><b :style="{ width: Math.max(2, b.ratio * 100) + '%' }" /></i><strong class="mg-mono">{{ number(b.value) }}<small> · {{ number(usage.usesByCapability[b.key]) }} 次</small></strong></li>
          </ul>
          <p class="mg-muted">{{ usage.lastTopUpCredits ? `最近一次充值 ${number(usage.lastTopUpCredits)} 烟火（${dateText(usage.lastTopUpAt)}），余额低于它的 10% 时提醒。` : '还没有充值记录。' }}只统计烟火和次数，不显示作品内容。</p>
        </section>

        <section v-if="steps.length" class="mg-detail-section">
          <h3><ListChecks :size="17" />上手进度</h3>
          <ol class="partner-steps">
            <li v-for="s in steps" :key="s.title" :class="{ done: s.done }"><span class="partner-step-mark">{{ s.done ? '✓' : '' }}</span><div><b>{{ s.title }}</b><small>{{ s.hint }}</small></div></li>
          </ol>
        </section>

        <section class="mg-detail-section">
          <h3><History :size="17" />往来记录</h3>
          <p v-if="!timeline.length" class="mg-muted">还没有记录。{{ seesMoney ? '' : '烟火分配要有分配或财务权限才看得到。' }}</p>
          <ul v-else class="partner-timeline">
            <li v-for="row in timeline" :key="row.key"><div>{{ row.title }}<small v-if="row.note">{{ row.note }}</small></div><time>{{ managementDate(row.at) }}</time></li>
          </ul>
        </section>
      </template>

      <MerchantCrm v-if="seen.has('follow')" v-show="activeTab === 'follow'" :key="`crm-${tenant.tenantId}`" :tenant-id="tenant.tenantId" @updated="emit('updated', tenant)" />

      <template v-if="activeTab === 'service'">
        <section class="mg-detail-section" aria-labelledby="renew-title">
          <h3 id="renew-title"><RefreshCw :size="17" />会员续期</h3>
          <p class="mg-muted">直接给商户开通，不用商户输入激活码，不限次数。还没到期的从原到期日往后加。</p>
          <div v-if="!plans.length" class="mg-muted">平台还没有建会员套餐，请联系平台。</div>
          <template v-else>
            <p class="partner-field-label">选择套餐</p>
            <OptionCards v-model="renewal.planCode" :options="planOptions" label="续期套餐" :disabled="busy || renewal.confirming" />
            <ConfirmBox v-if="renewal.confirming" :busy="busy" confirm-text="确认续期" @confirm="renew" @cancel="renewal.confirming = false">
              <p>给「{{ tenant.tenantName }}」续期「{{ renewPlan.planName }}」{{ renewPlan.durationDays }} 天。</p>
              <p>到期日：<b>{{ tenant.memberActive ? dateText(tenant.memberUntil) : '未生效' }}</b> → <b>{{ dateText(renewTo) }}</b>。</p>
            </ConfirmBox>
            <div v-else class="partner-section-actions"><button class="mg-button mg-primary" type="button" :disabled="busy" @click="askRenew">续期</button></div>
            <p v-if="renewal.error" class="mg-inline-error" role="alert">{{ renewal.error }}</p>
          </template>
        </section>

        <section v-if="distributor" class="mg-detail-section" aria-labelledby="transfer-title">
          <h3 id="transfer-title"><Coins :size="17" />分配烟火</h3>
          <p class="mg-muted">从团队账户扣，先扣赠送；分出后不能撤回，每一笔都记在「烟火」页的分配记录里。团队烟火 {{ number(available) }}。</p>
          <label class="partner-field">烟火数<input v-model="transfer.credits" type="number" min="1" max="1000000" step="1" inputmode="numeric" placeholder="例如 2000" :disabled="busy || transfer.confirming" /></label>
          <div class="partner-presets"><button v-for="value in transferPresets" :key="value" type="button" :class="{ active: Number(transfer.credits) === value }" :disabled="busy || transfer.confirming" @click="transfer.credits = String(value)">{{ number(value) }}</button></div>
          <label class="partner-field">备注（可选）<input v-model="transfer.note" maxlength="200" placeholder="例如：11 月活动补贴" :disabled="busy || transfer.confirming" /></label>
          <ConfirmBox v-if="transfer.confirming" :busy="busy" confirm-text="确认分配" @confirm="sendCredits" @cancel="transfer.confirming = false">
            <p>把 <b>{{ number(transfer.credits) }}</b> 烟火分给「{{ tenant.tenantName }}」。</p>
            <p>团队烟火 {{ number(available) }} → <b>{{ number(available - Number(transfer.credits)) }}</b>；商户余额 {{ number(tenant.creditsAvailable) }} → <b>{{ number(Number(tenant.creditsAvailable || 0) + Number(transfer.credits)) }}</b>。</p>
            <p>其中赠送 {{ number(split.gift) }}、充值 {{ number(split.purchased) }}，商户收到的性质不变。分出后不能撤回。</p>
          </ConfirmBox>
          <div v-else class="partner-section-actions"><button class="mg-button mg-primary" type="button" :disabled="busy" @click="askTransfer">分配</button></div>
          <p v-if="transfer.error" class="mg-inline-error" role="alert">{{ transfer.error }}</p>
        </section>
      </template>
    </template>
    <template #actions><button class="mg-button" type="button" :disabled="busy" @click="emit('close')">关闭</button></template>
  </PartnerPanel>
</template>
