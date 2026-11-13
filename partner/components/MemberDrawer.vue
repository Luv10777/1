<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { BarChart3, Check, Coins, Store, UserMinus } from 'lucide-vue-next'
import { partnerApi } from '../../src/services/partnerApi.js'
import { managementDate } from '../../src/domain/platformManagement.js'
import { defaultSource, distributionSources, membershipState, number, partnerRoles, sourceBalance, transferError, transferPresets } from '../../src/domain/partnerOps.js'
import { bds, loadPartner, partner } from '../partnerStore.js'
import PartnerPanel from './PartnerPanel.vue'
import ConfirmBox from './ConfirmBox.vue'
import OptionCards from './OptionCards.vue'
import CreditSource from './CreditSource.vue'

/**
 * 团队成员详情（只给负责人）：业绩数字、名下商户（点开看商户详情），移除 BD 时先选名下商户转给谁。
 */
const props = defineProps({ member: { type: Object, default: null } })
const emit = defineEmits(['close', 'removed', 'open-tenant'])

const tenants = ref([])
const tenantTotal = ref(0)
const loading = ref(false)
const busy = ref(false)
const removal = reactive({ transferTo: '0', confirming: false, error: '' })
const isBd = computed(() => props.member?.role === 'BD')
const others = computed(() => bds().filter(b => b.userId !== props.member?.userId))
const target = computed(() => others.value.find(b => String(b.userId) === removal.transferTo))
const transferOptions = computed(() => [
  { value: '0', title: '我直接管', detail: '商户回到负责人名下' },
  ...others.value.map(b => ({ value: String(b.userId), title: `BD · ${b.name || b.uid}`, detail: `UID ${b.uid || '—'} · 现有 ${b.tenantCount} 个商户` })),
])
const codes = computed(() => props.member?.codes || { generated: 0, redeemed: 0, unused: 0, voided: 0, renewals: 0 })
const gift = reactive({ source: 'POOL', credits: '', note: '', requestKey: '', confirming: false, error: '', done: '' })
const giftBalance = computed(() => sourceBalance(partner.overview, gift.source))
function resetGift() { Object.assign(gift, { source: defaultSource(partner.overview), credits: '', note: '', requestKey: 'bd-' + crypto.randomUUID(), confirming: false, error: '' }) }
function askGift() { gift.error = transferError(gift.credits, giftBalance.value); gift.confirming = !gift.error }
async function sendGift() {
  busy.value = true
  const credits = Number(gift.credits)
  try {
    await partnerApi.transferToBd(props.member.userId, { requestKey: gift.requestKey, credits, note: gift.note.trim() || null, source: gift.source })
    await loadPartner(true)
    const name = props.member.name || props.member.uid
    resetGift()
    gift.done = `已分给 ${name} ${number(credits)} 烟火`
  } catch (failure) {
    gift.error = `${failure.message || '分配失败'}，可以直接再确认一次，不会重复扣烟火。`
    gift.confirming = false
  } finally { busy.value = false }
}

async function load() {
  tenants.value = []
  if (!isBd.value) return
  loading.value = true
  try {
    const result = await partnerApi.tenants({ size: 50, bdUserId: props.member.userId })
    tenants.value = result.items
    tenantTotal.value = result.total
  } finally { loading.value = false }
}
watch(() => props.member?.userId, id => { Object.assign(removal, { transferTo: '0', confirming: false, error: '' }); resetGift(); gift.done = ''; if (id) load().catch(() => {}) }, { immediate: true })

async function remove() {
  busy.value = true
  // 团队刷新后这个成员就不在列表里了，props.member 会变空，先记下来。
  const member = props.member
  try {
    const team = await partnerApi.removeBd(member.userId, Number(removal.transferTo))
    emit('removed', member)
    partner.team = team
    await loadPartner(true)
  } catch (failure) {
    removal.error = failure.message || '移除失败，请稍后再试。'
    removal.confirming = false
  } finally { busy.value = false }
}
</script>

<template>
  <PartnerPanel :open="Boolean(member)" :busy="busy" :title="member?.name || '未填写姓名'" :eyebrow="member ? `UID ${member.uid || '—'}` : ''" @close="emit('close')">
    <template v-if="member" #summary>
      <span class="partner-pill" :class="{ bd: isBd }">{{ partnerRoles[member.role] || member.role }}</span>
      <span>加入于 {{ managementDate(member.createdAt) }}</span>
      <span v-if="member.phone">手机 {{ member.phone }}</span>
    </template>
    <template v-if="member">
      <section class="mg-detail-section">
        <h3><BarChart3 :size="17" />业绩</h3>
        <div class="partner-stats">
          <div v-if="isBd"><span>名下商户</span><strong>{{ number(member.tenantCount) }}</strong></div>
          <div><span>生成激活码</span><strong>{{ number(codes.generated) }}</strong></div>
          <div><span>已兑换</span><strong>{{ number(codes.redeemed) }}</strong></div>
          <div><span>未使用</span><strong>{{ number(codes.unused) }}</strong></div>
          <div><span>直接续期</span><strong>{{ number(codes.renewals) }}</strong></div>
          <div><span>已作废</span><strong>{{ number(codes.voided) }}</strong></div>
        </div>
        <p class="mg-muted">激活码数含直接续期自动生成的码。{{ isBd ? '' : '负责人管本服务商全部商户。' }}</p>
      </section>

      <section v-if="isBd" class="mg-detail-section" aria-labelledby="bd-gift-title">
        <h3 id="bd-gift-title"><Coins :size="17" />分配烟火</h3>
        <p class="mg-muted">分给 BD 的烟火进他自己的一方志账号，他可以自己用。分出后不能撤回，记在「分配记录」里。</p>
        <div v-if="gift.done" class="mg-feedback success" role="status"><Check :size="16" />{{ gift.done }}</div>
        <CreditSource v-model="gift.source" :disabled="busy || gift.confirming" />
        <label class="partner-field">烟火数<input v-model="gift.credits" type="number" min="1" max="1000000" step="1" inputmode="numeric" placeholder="例如 1000" :disabled="busy || gift.confirming" /></label>
        <div class="partner-presets"><button v-for="value in transferPresets" :key="value" type="button" :class="{ active: Number(gift.credits) === value }" :disabled="busy || gift.confirming" @click="gift.credits = String(value)">{{ number(value) }}</button></div>
        <label class="partner-field">备注（可选）<input v-model="gift.note" maxlength="200" placeholder="例如：11 月提成" :disabled="busy || gift.confirming" /></label>
        <ConfirmBox v-if="gift.confirming" :busy="busy" confirm-text="确认分配" @confirm="sendGift" @cancel="gift.confirming = false">
          <p>从「{{ distributionSources[gift.source] }}」分 <b>{{ number(gift.credits) }}</b> 烟火给 BD「{{ member.name || member.uid }}」，{{ distributionSources[gift.source] }} {{ number(giftBalance) }} → <b>{{ number(giftBalance - Number(gift.credits)) }}</b>。</p>
        </ConfirmBox>
        <div v-else class="partner-section-actions"><button class="mg-button mg-primary" type="button" :disabled="busy" @click="askGift">分配</button></div>
        <p v-if="gift.error" class="mg-inline-error" role="alert">{{ gift.error }}</p>
      </section>

      <section v-if="isBd" class="mg-detail-section">
        <h3><Store :size="17" />名下商户</h3>
        <p v-if="loading" class="mg-muted">正在读取…</p>
        <p v-else-if="!tenants.length" class="mg-muted">还没有商户。可以在「名下商户」打开某个商户，把归属调给他。</p>
        <ul v-else class="partner-timeline">
          <li v-for="t in tenants" :key="t.tenantId"><div><button class="mg-text-button" type="button" @click="emit('open-tenant', t.tenantId)">{{ t.tenantName || '商户' }}</button><small>{{ t.ownerName || '—' }} · UID {{ t.ownerUid || '—' }}</small></div><span :class="['partner-member', membershipState(t).tone]">{{ membershipState(t).short }}</span></li>
        </ul>
        <p v-if="tenantTotal > tenants.length" class="mg-muted">只列出前 {{ tenants.length }} 个，共 {{ tenantTotal }} 个；全部请到「名下商户」按 BD 筛选。</p>
      </section>

      <section v-if="isBd" class="mg-detail-section" aria-labelledby="remove-bd-title">
        <h3 id="remove-bd-title"><UserMinus :size="17" />移除 BD</h3>
        <p class="mg-muted">移除后他不能再登录服务商后台，他生成的激活码照常可用。名下商户需要先选一个去处。</p>
        <p class="partner-field-label">名下 {{ member.tenantCount }} 个商户转给</p>
        <OptionCards v-model="removal.transferTo" :options="transferOptions" label="商户转给" :disabled="busy || removal.confirming" />
        <ConfirmBox v-if="removal.confirming" danger :busy="busy" confirm-text="确认移除" @confirm="remove" @cancel="removal.confirming = false">
          <p>移除 BD「{{ member.name || member.uid }}」，他名下的 <b>{{ member.tenantCount }}</b> 个商户转给 <b>{{ target ? `BD ${target.name || target.uid}` : '你直接管' }}</b>。</p>
        </ConfirmBox>
        <div v-else class="partner-section-actions"><button class="mg-button mg-danger" type="button" :disabled="busy" @click="removal.confirming = true">移除 BD</button></div>
        <p v-if="removal.error" class="mg-inline-error" role="alert">{{ removal.error }}</p>
      </section>
    </template>
    <template #actions><button class="mg-button" type="button" :disabled="busy" @click="emit('close')">关闭</button></template>
  </PartnerPanel>
</template>
