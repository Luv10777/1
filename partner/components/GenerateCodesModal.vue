<script setup>
import AppSelect from '../../src/components/AppSelect.vue'
import { computed, reactive, ref, watch } from 'vue'
import { Copy, Download } from 'lucide-vue-next'
import { partnerApi } from '../../src/services/partnerApi.js'
import { formatRedeemCode } from '../../src/services/redeemCodeAdminApi.js'
import { codesCsv, downloadCsv, number } from '../../src/domain/partnerOps.js'
import { loadPartner, partner } from '../partnerStore.js'
import PartnerPanel from './PartnerPanel.vue'
import ConfirmBox from './ConfirmBox.vue'

/** 生成激活码弹窗：选套餐和数量，确认后生成；生成的码在弹窗里直接复制或导出。 */
const props = defineProps({ open: { type: Boolean, default: false } })
const emit = defineEmits(['close', 'created'])

const quotas = computed(() => (partner.overview?.quotas || []).filter(q => q.planName))
const form = reactive({ planCode: '', quantity: '10', note: '', requestKey: '', confirming: false, error: '' })
const busy = ref(false)
const created = ref([])
const copied = ref('')
const quota = computed(() => quotas.value.find(q => q.planCode === form.planCode))
const quantity = computed(() => Number(form.quantity))

watch(() => props.open, open => {
  if (!open) return
  created.value = []
  copied.value = ''
  Object.assign(form, { planCode: quotas.value.find(q => q.remaining > 0)?.planCode || '', quantity: '10', note: '', requestKey: 'codes-' + crypto.randomUUID(), confirming: false, error: '' })
})

function ask() {
  const q = quota.value
  form.error = !q ? '请选择套餐。'
    : !Number.isInteger(quantity.value) || quantity.value < 1 || quantity.value > 200 ? '一次生成 1 至 200 个。'
      : quantity.value > q.remaining ? `「${q.planName}」还能生成 ${q.remaining} 个。` : ''
  form.confirming = !form.error
}

async function generate() {
  busy.value = true
  try {
    created.value = await partnerApi.createCodes({ requestKey: form.requestKey, planCode: form.planCode, quantity: quantity.value, note: form.note.trim() || null })
    form.confirming = false
    await loadPartner(true)
    emit('created', created.value)
  } catch (failure) {
    form.error = failure.message || '生成失败，请稍后再确认一次（不会重复扣额度）。'
    form.confirming = false
  } finally { busy.value = false }
}

async function copyAll() {
  try { await navigator.clipboard.writeText(created.value.map(c => formatRedeemCode(c.code)).join('\n')); copied.value = '已复制全部激活码' }
  catch { copied.value = '浏览器不允许自动复制，请手动选中复制' }
}
function exportCreated() {
  downloadCsv(codesCsv(created.value, { statuses: { UNUSED: '未使用' }, format: formatRedeemCode }), '新生成激活码')
}
</script>

<template>
  <PartnerPanel modal :open="open" :busy="busy" :title="created.length ? `已生成 ${created.length} 个激活码` : '生成激活码'" @close="emit('close')">
    <template v-if="created.length">
      <p class="mg-muted">「{{ created[0].planName }}」· {{ created[0].durationDays }} 天。商户在一方志「套餐与权益」输入后开通；还没有归属的商户会归到生成人名下。</p>
      <ol class="partner-codes-out mg-mono"><li v-for="c in created" :key="c.id">{{ formatRedeemCode(c.code) }}</li></ol>
      <p v-if="copied" class="mg-muted" role="status">{{ copied }}</p>
    </template>
    <template v-else>
      <div v-if="!quotas.length" class="mg-state"><p>没有可用的激活码额度，请联系平台或负责人分配。</p></div>
      <template v-else>
        <label class="partner-field">套餐<AppSelect v-model="form.planCode" :disabled="busy || form.confirming" aria-label="套餐"><option value="" disabled>选择套餐</option><option v-for="q in quotas" :key="q.planCode" :value="q.planCode" :disabled="q.remaining < 1">{{ q.planName }} · {{ q.durationDays }} 天（还能生成 {{ number(q.remaining) }} 个）</option></AppSelect></label>
        <label class="partner-field">数量<input v-model="form.quantity" type="number" min="1" max="200" step="1" inputmode="numeric" :disabled="busy || form.confirming" /><small>一次最多 200 个。</small></label>
        <label class="partner-field">备注（可选）<input v-model="form.note" maxlength="200" placeholder="例如：某某商户、某次活动" :disabled="busy || form.confirming" /></label>
        <ConfirmBox v-if="form.confirming" :busy="busy" confirm-text="确认生成" @confirm="generate" @cancel="form.confirming = false">
          <p>生成 <b>{{ quantity }}</b> 个「{{ quota.planName }}」激活码（每个 {{ quota.durationDays }} 天），额度剩 {{ quota.remaining }} → <b>{{ quota.remaining - quantity }}</b> 个。</p>
          <p>激活码不送烟火；作废后额度不退回。</p>
        </ConfirmBox>
        <p v-if="form.error" class="mg-inline-error" role="alert">{{ form.error }}</p>
      </template>
    </template>
    <template #actions>
      <template v-if="created.length">
        <button class="mg-button" type="button" @click="exportCreated"><Download :size="15" />导出表格</button>
        <button class="mg-button" type="button" @click="copyAll"><Copy :size="15" />复制全部</button>
        <button class="mg-button mg-primary" type="button" @click="emit('close')">完成</button>
      </template>
      <template v-else>
        <button class="mg-button" type="button" :disabled="busy" @click="emit('close')">取消</button>
        <button v-if="quotas.length" class="mg-button mg-primary" type="button" :disabled="busy || form.confirming" @click="ask">生成</button>
      </template>
    </template>
  </PartnerPanel>
</template>
