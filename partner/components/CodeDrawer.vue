<script setup>
import { computed, ref, watch } from 'vue'
import { Ban, Check, Copy, TicketCheck } from 'lucide-vue-next'
import { partnerApi } from '../../src/services/partnerApi.js'
import { formatRedeemCode, redeemCodeStatuses } from '../../src/services/redeemCodeAdminApi.js'
import { managementDate } from '../../src/domain/platformManagement.js'
import PartnerPanel from './PartnerPanel.vue'
import ConfirmBox from './ConfirmBox.vue'

/** 激活码详情：状态、生成人、兑换商户；没兑换的可以复制、作废（作废后额度不退回）。 */
const props = defineProps({ code: { type: Object, default: null }, creatorName: { type: String, default: '' } })
const emit = defineEmits(['close', 'updated'])

const busy = ref(false)
const confirming = ref(false)
const error = ref('')
const notice = ref('')
const unused = computed(() => props.code?.status === 'UNUSED')
watch(() => props.code?.id, () => { confirming.value = false; error.value = ''; notice.value = '' })

async function copy() {
  try { await navigator.clipboard.writeText(formatRedeemCode(props.code.code)); notice.value = '已复制' }
  catch { notice.value = '浏览器不允许自动复制，请手动选中复制' }
}
async function voidCode() {
  busy.value = true
  try {
    emit('updated', await partnerApi.voidCode(props.code.id))
    notice.value = '已作废'
    confirming.value = false
  } catch (failure) {
    error.value = failure.message || '作废失败，请稍后再试。'
    confirming.value = false
  } finally { busy.value = false }
}
</script>

<template>
  <PartnerPanel :open="Boolean(code)" :busy="busy" :title="code ? formatRedeemCode(code.code) : ''" eyebrow="激活码" @close="emit('close')">
    <template v-if="code" #summary>
      <span class="partner-pill" :class="{ bd: unused }">{{ redeemCodeStatuses[code.status] || code.status }}</span>
      <span>{{ code.planName || '—' }} · {{ code.durationDays ?? '—' }} 天</span>
    </template>
    <template v-if="code">
      <div v-if="notice" class="mg-feedback success" role="status"><Check :size="16" />{{ notice }}</div>
      <section class="mg-detail-section">
        <h3><TicketCheck :size="17" />详情</h3>
        <dl class="mg-detail-grid">
          <div><dt>生成人</dt><dd>{{ creatorName || '—' }}</dd></div>
          <div><dt>生成时间</dt><dd>{{ managementDate(code.createdAt) }}</dd></div>
          <div><dt>兑换商户</dt><dd>{{ code.redeemedTenantName || '—' }}</dd></div>
          <div><dt>兑换时间</dt><dd>{{ code.redeemedAt ? managementDate(code.redeemedAt) : '—' }}</dd></div>
          <div v-if="code.voidedAt"><dt>作废时间</dt><dd>{{ managementDate(code.voidedAt) }}</dd></div>
          <div class="full"><dt>备注</dt><dd>{{ code.note || '—' }}</dd></div>
        </dl>
      </section>
      <section v-if="unused" class="mg-detail-section">
        <h3><Ban :size="17" />作废</h3>
        <p class="mg-muted">作废后这个码不能再兑换，已用掉的额度不退回。码已经发给商户、但还没兑换时可以作废防止被用。</p>
        <ConfirmBox v-if="confirming" danger :busy="busy" confirm-text="确认作废" @confirm="voidCode" @cancel="confirming = false">
          <p>作废激活码 <b class="mg-mono">{{ formatRedeemCode(code.code) }}</b>（{{ code.planName }}），额度不退回。</p>
        </ConfirmBox>
        <div v-else class="partner-section-actions"><button class="mg-button mg-danger" type="button" :disabled="busy" @click="confirming = true">作废这个码</button></div>
        <p v-if="error" class="mg-inline-error" role="alert">{{ error }}</p>
      </section>
    </template>
    <template #actions>
      <button v-if="unused" class="mg-button" type="button" @click="copy"><Copy :size="15" />复制</button>
      <button class="mg-button" type="button" :disabled="busy" @click="emit('close')">关闭</button>
    </template>
  </PartnerPanel>
</template>
