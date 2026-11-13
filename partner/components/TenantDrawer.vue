<script setup>
import AppSelect from '../../src/components/AppSelect.vue'
import { computed, reactive, ref, watch } from 'vue'
import { Check, Coins, History, RefreshCw, Store, UserRoundCog } from 'lucide-vue-next'
import { partnerApi, tenantSources } from '../../src/services/partnerApi.js'
import { managementDate } from '../../src/domain/platformManagement.js'
import { dateText, membershipState, number, renewedUntil, tenantTimeline, transferError, transferPresets } from '../../src/domain/partnerOps.js'
import { bds, isOwner, loadPartner, partner } from '../partnerStore.js'
import PartnerPanel from './PartnerPanel.vue'
import ConfirmBox from './ConfirmBox.vue'

/**
 * 商户详情抽屉：会员续期、分配烟火（负责人）、调整归属 BD（负责人）、往来记录。
 * 每项操作先填写，再在页内确认改动结果，最后提交。改完通过 updated 把新的商户行交回列表。
 */
const props = defineProps({ tenantId: { type: Number, default: null } })
const emit = defineEmits(['close', 'updated'])

const owner = computed(() => isOwner())
const detail = ref(null)
const loading = ref(false)
const error = ref('')
const busy = ref(false)
const notice = ref('')
const tenant = computed(() => detail.value?.tenant || null)
const available = computed(() => partner.overview?.partner.creditsAvailable || 0)
const quotas = computed(() => (partner.overview?.quotas || []).filter(q => q.planName))
const timeline = computed(() => tenantTimeline(detail.value))
const state = computed(() => tenant.value ? membershipState(tenant.value) : null)

const renewal = reactive({ planCode: '', requestKey: '', confirming: false, error: '' })
const transfer = reactive({ credits: '', note: '', requestKey: '', confirming: false, error: '' })
const assign = reactive({ bdUserId: '', confirming: false, error: '' })
const renewQuota = computed(() => quotas.value.find(q => q.planCode === renewal.planCode))
const renewTo = computed(() => tenant.value && renewQuota.value ? renewedUntil(tenant.value, renewQuota.value.durationDays) : null)
const assignTarget = computed(() => bds().find(b => String(b.userId) === assign.bdUserId))
const currentBd = computed(() => tenant.value?.bdUserId ? String(tenant.value.bdUserId) : '')

function reset() {
  Object.assign(renewal, { planCode: quotas.value.find(q => q.remaining > 0)?.planCode || '', requestKey: 'renew-' + crypto.randomUUID(), confirming: false, error: '' })
  Object.assign(transfer, { credits: '', note: '', requestKey: 'transfer-' + crypto.randomUUID(), confirming: false, error: '' })
  Object.assign(assign, { bdUserId: currentBd.value, confirming: false, error: '' })
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
watch(() => props.tenantId, id => { detail.value = null; notice.value = ''; if (id) load() }, { immediate: true })

async function refresh(message) {
  notice.value = message
  const [fresh] = await Promise.all([partnerApi.tenant(props.tenantId), loadPartner(true)])
  detail.value = fresh
  emit('updated', fresh.tenant)
  reset()
}

function askRenew() {
  const q = renewQuota.value
  renewal.error = !q ? '请选择套餐。' : q.remaining < 1 ? `「${q.planName}」额度已用完，请联系${owner.value ? '平台' : '负责人'}追加。` : ''
  renewal.confirming = !renewal.error
}
async function renew() {
  busy.value = true
  try {
    await partnerApi.renew(props.tenantId, { requestKey: renewal.requestKey, planCode: renewal.planCode })
    await refresh(`已续期，会员到 ${dateText(renewTo.value)}`)
  } catch (failure) {
    renewal.error = failure.message || '续期失败，请稍后再确认一次（不会重复扣额度）。'
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

function askAssign() {
  assign.error = assign.bdUserId === currentBd.value ? '归属没有变化。' : ''
  assign.confirming = !assign.error
}
async function saveAssign() {
  busy.value = true
  try {
    await partnerApi.assignBd(props.tenantId, assignTarget.value?.userId)
    await refresh(assignTarget.value ? `已转给 BD「${assignTarget.value.name || assignTarget.value.uid}」` : '已收回由你直接管')
  } catch (failure) {
    assign.error = failure.message || '调整失败，请稍后再试。'
    assign.confirming = false
  } finally { busy.value = false }
}
</script>

<template>
  <PartnerPanel :open="Boolean(tenantId)" :busy="busy" :title="tenant?.tenantName || '商户详情'" :eyebrow="tenant ? `商户 #${tenant.tenantId}` : ''" @close="emit('close')">
    <template v-if="tenant" #summary>
      <span :class="['partner-member', state.tone]">{{ state.text }}</span>
      <span>{{ tenant.ownerName || '—' }} · UID {{ tenant.ownerUid || '—' }}</span>
      <span class="partner-pill" :class="{ bd: tenant.bdUserId }">{{ tenant.bdUserId ? `BD · ${tenant.bdName || '—'}` : '负责人直接管' }}</span>
    </template>

    <div v-if="loading && !detail" class="mg-state"><p>正在读取…</p></div>
    <div v-else-if="error" class="mg-state mg-error" role="alert"><p>{{ error }}</p><button class="mg-button" type="button" @click="load"><RefreshCw :size="15" />重新读取</button></div>
    <template v-else-if="tenant">
      <div v-if="notice" class="mg-feedback success" role="status"><Check :size="16" />{{ notice }}</div>

      <section class="mg-detail-section">
        <h3><Store :size="17" />基本信息</h3>
        <dl class="mg-detail-grid">
          <div><dt>联系人</dt><dd>{{ tenant.ownerName || '—' }}</dd></div>
          <div><dt>联系人 UID</dt><dd class="mg-mono">{{ tenant.ownerUid || '—' }}</dd></div>
          <div><dt>归属方式</dt><dd>{{ tenantSources[tenant.source] || tenant.source }}</dd></div>
          <div><dt>加入时间</dt><dd>{{ managementDate(tenant.boundAt) }}</dd></div>
          <div><dt>烟火余额</dt><dd class="mg-mono">{{ tenant.creditsAvailable == null ? '—' : number(tenant.creditsAvailable) }}</dd></div>
          <div><dt>会员套餐</dt><dd>{{ tenant.memberActive ? tenant.planName || '会员' : state.short }}</dd></div>
        </dl>
      </section>

      <section class="mg-detail-section" aria-labelledby="renew-title">
        <h3 id="renew-title"><RefreshCw :size="17" />会员续期</h3>
        <p class="mg-muted">扣 1 个激活码额度直接给商户开通，不用商户输入激活码。还没到期的从原到期日往后加。</p>
        <div v-if="!quotas.length" class="mg-muted">没有可用的激活码额度，请联系{{ owner ? '平台' : '负责人' }}。</div>
        <template v-else>
          <label class="partner-field">套餐<AppSelect v-model="renewal.planCode" :disabled="busy || renewal.confirming" aria-label="续期套餐"><option value="" disabled>选择套餐</option><option v-for="q in quotas" :key="q.planCode" :value="q.planCode" :disabled="q.remaining < 1">{{ q.planName }} · {{ q.durationDays }} 天（剩 {{ number(q.remaining) }} 个额度）</option></AppSelect></label>
          <ConfirmBox v-if="renewal.confirming" :busy="busy" confirm-text="确认续期" @confirm="renew" @cancel="renewal.confirming = false">
            <p>用 1 个「{{ renewQuota.planName }}」额度给「{{ tenant.tenantName }}」续期 {{ renewQuota.durationDays }} 天。</p>
            <p>到期日：<b>{{ tenant.memberActive ? dateText(tenant.memberUntil) : '未生效' }}</b> → <b>{{ dateText(renewTo) }}</b>；「{{ renewQuota.planName }}」额度剩 {{ renewQuota.remaining - 1 }} 个。</p>
          </ConfirmBox>
          <div v-else class="partner-section-actions"><button class="mg-button mg-primary" type="button" :disabled="busy" @click="askRenew">续期</button></div>
          <p v-if="renewal.error" class="mg-inline-error" role="alert">{{ renewal.error }}</p>
        </template>
      </section>

      <section v-if="owner" class="mg-detail-section" aria-labelledby="transfer-title">
        <h3 id="transfer-title"><Coins :size="17" />分配烟火</h3>
        <p class="mg-muted">分出的烟火记入商户的充值烟火，不能撤回。你现在可分配 {{ number(available) }} 烟火。</p>
        <label class="partner-field">烟火数<input v-model="transfer.credits" type="number" min="1" max="1000000" step="1" inputmode="numeric" placeholder="例如 2000" :disabled="busy || transfer.confirming" /></label>
        <div class="partner-presets"><button v-for="value in transferPresets" :key="value" type="button" :class="{ active: Number(transfer.credits) === value }" :disabled="busy || transfer.confirming" @click="transfer.credits = String(value)">{{ number(value) }}</button></div>
        <label class="partner-field">备注（可选）<input v-model="transfer.note" maxlength="200" placeholder="例如：11 月活动补贴" :disabled="busy || transfer.confirming" /></label>
        <ConfirmBox v-if="transfer.confirming" :busy="busy" confirm-text="确认分配" @confirm="sendCredits" @cancel="transfer.confirming = false">
          <p>把 <b>{{ number(transfer.credits) }}</b> 烟火分给「{{ tenant.tenantName }}」。</p>
          <p>商户余额 {{ number(tenant.creditsAvailable) }} → <b>{{ number(Number(tenant.creditsAvailable || 0) + Number(transfer.credits)) }}</b>；你的可分配 {{ number(available) }} → <b>{{ number(available - Number(transfer.credits)) }}</b>。分出后不能撤回。</p>
        </ConfirmBox>
        <div v-else class="partner-section-actions"><button class="mg-button mg-primary" type="button" :disabled="busy" @click="askTransfer">分配</button></div>
        <p v-if="transfer.error" class="mg-inline-error" role="alert">{{ transfer.error }}</p>
      </section>

      <section v-if="owner" class="mg-detail-section" aria-labelledby="assign-title">
        <h3 id="assign-title"><UserRoundCog :size="17" />归属 BD</h3>
        <p class="mg-muted">BD 只看得到归自己的商户。转给 BD 后，他可以给这个商户续期；烟火仍由你分配。</p>
        <label class="partner-field">归属给<AppSelect v-model="assign.bdUserId" :disabled="busy || assign.confirming" aria-label="归属 BD"><option value="">我直接管</option><option v-for="b in bds()" :key="b.userId" :value="String(b.userId)">BD · {{ b.name || b.uid }}（名下 {{ b.tenantCount }} 个）</option></AppSelect></label>
        <ConfirmBox v-if="assign.confirming" :busy="busy" confirm-text="确认调整" @confirm="saveAssign" @cancel="assign.confirming = false">
          <p>「{{ tenant.tenantName }}」：<b>{{ tenant.bdUserId ? `BD「${tenant.bdName}」` : '我直接管' }}</b> → <b>{{ assignTarget ? `BD「${assignTarget.name || assignTarget.uid}」` : '我直接管' }}</b>。</p>
        </ConfirmBox>
        <div v-else class="partner-section-actions"><button class="mg-button" type="button" :disabled="busy || !bds().length && !tenant.bdUserId" @click="askAssign">调整归属</button><span v-if="!bds().length && !tenant.bdUserId" class="mg-muted">还没有 BD，可在「团队」页添加。</span></div>
        <p v-if="assign.error" class="mg-inline-error" role="alert">{{ assign.error }}</p>
      </section>

      <section class="mg-detail-section">
        <h3><History :size="17" />往来记录</h3>
        <p v-if="!timeline.length" class="mg-muted">还没有记录。{{ owner ? '' : '烟火分配只有负责人看得到。' }}</p>
        <ul v-else class="partner-timeline">
          <li v-for="row in timeline" :key="row.key"><div>{{ row.title }}<small v-if="row.note">{{ row.note }}</small></div><time>{{ managementDate(row.at) }}</time></li>
        </ul>
      </section>
    </template>
    <template #actions><button class="mg-button" type="button" :disabled="busy" @click="emit('close')">关闭</button></template>
  </PartnerPanel>
</template>
