<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { Check, X } from 'lucide-vue-next'
import { partnerApi } from '../../src/services/partnerApi.js'
import { number, planBatchTransfer, runBatchTransfers, transferPresets } from '../../src/domain/partnerOps.js'
import { loadPartner, partner } from '../partnerStore.js'
import PartnerPanel from './PartnerPanel.vue'
import ConfirmBox from './ConfirmBox.vue'

/**
 * 批量分配烟火：给勾选的商户逐个填数量，或一键统一填；确认后逐笔提交，每笔一个 requestKey。
 * 某一笔失败只标在那一行，再点「重试失败的」用同一个 requestKey 补上，不会重复扣烟火。
 */
const props = defineProps({ tenants: { type: Array, default: () => [] } })
const emit = defineEmits(['close', 'done'])

const rows = reactive([])
const same = ref('')
const confirming = ref(false)
const busy = ref(false)
const error = ref('')
const available = computed(() => partner.overview?.partner.creditsAvailable || 0)
const pending = computed(() => rows.filter(r => r.status !== 'done'))
const plan = computed(() => planBatchTransfer(pending.value, available.value))
const finished = computed(() => rows.length > 0 && rows.every(r => r.status === 'done'))
const anyFailed = computed(() => rows.some(r => r.status === 'failed'))

watch(() => props.tenants, list => {
  rows.splice(0, rows.length, ...list.map(t => ({ tenantId: t.tenantId, tenantName: t.tenantName, balance: t.creditsAvailable,
    credits: '', requestKey: 'transfer-' + crypto.randomUUID(), status: '', error: '' })))
  same.value = ''
  confirming.value = false
  error.value = ''
}, { immediate: true })

function fillAll(value) { same.value = String(value); for (const r of pending.value) r.credits = String(value) }
function ask() { error.value = plan.value.error; confirming.value = !error.value }

async function submit() {
  busy.value = true
  confirming.value = false
  try {
    const keys = Object.fromEntries(plan.value.entries.map(e => [e.tenantId, rows.find(r => r.tenantId === e.tenantId).requestKey]))
    const results = await runBatchTransfers(plan.value.entries, keys, partnerApi.transfer)
    for (const r of results) {
      const row = rows.find(x => x.tenantId === r.tenantId)
      if (r.ok) Object.assign(row, { status: 'done', error: '', balance: r.result.creditsAvailable })
      else Object.assign(row, { status: 'failed', error: r.error })
    }
    await loadPartner(true)
    emit('done', results.filter(r => r.ok).map(r => r.result))
  } finally { busy.value = false }
}
</script>

<template>
  <PartnerPanel modal :open="tenants.length > 0" :busy="busy" title="批量分配烟火" :eyebrow="`已选 ${tenants.length} 个商户`" @close="emit('close')">
    <p class="mg-muted">分出的烟火记入商户的充值烟火，不能撤回。空着的商户不分配。</p>
    <div class="partner-presets"><span class="mg-muted">每个都填：</span><button v-for="value in transferPresets" :key="value" type="button" :class="{ active: same === String(value) }" :disabled="busy || confirming" @click="fillAll(value)">{{ number(value) }}</button></div>
    <div class="partner-batch-list">
      <div v-for="row in rows" :key="row.tenantId">
        <span>{{ row.tenantName || '商户' }}<small>当前余额 {{ row.balance == null ? '—' : number(row.balance) }}</small>
          <small v-if="row.status === 'done'" class="done"><Check :size="12" /> 已到账</small>
          <small v-else-if="row.status === 'failed'" class="failed"><X :size="12" /> {{ row.error }}</small></span>
        <input v-model="row.credits" type="number" min="1" max="1000000" step="1" inputmode="numeric" placeholder="烟火数" :aria-label="`分给${row.tenantName || '商户'}的烟火`" :disabled="busy || confirming || row.status === 'done'" />
      </div>
    </div>
    <div class="partner-sum"><span>合计 <b class="mg-mono">{{ number(plan.total) }}</b> 烟火（{{ plan.entries.length }} 个商户）</span><span>可分配 <span class="mg-mono">{{ number(available) }}</span></span></div>
    <ConfirmBox v-if="confirming" :busy="busy" confirm-text="确认分配" @confirm="submit" @cancel="confirming = false">
      <p>给 {{ plan.entries.length }} 个商户共分 <b>{{ number(plan.total) }}</b> 烟火，分完你还剩 <b>{{ number(available - plan.total) }}</b>。</p>
    </ConfirmBox>
    <p v-if="error" class="mg-inline-error" role="alert">{{ error }}</p>
    <p v-if="anyFailed && !busy" class="mg-inline-error" role="alert">有几笔没成功，已标在对应商户下面；成功的已到账。可以直接重试失败的，不会重复扣烟火。</p>
    <template #actions>
      <button class="mg-button" type="button" :disabled="busy" @click="emit('close')">{{ finished ? '完成' : '取消' }}</button>
      <button v-if="!finished" class="mg-button mg-primary" type="button" :disabled="busy || confirming" @click="ask">{{ busy ? '正在分配…' : anyFailed ? '重试失败的' : '分配' }}</button>
    </template>
  </PartnerPanel>
</template>
