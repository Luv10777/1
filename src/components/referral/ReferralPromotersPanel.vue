<script setup>
import { onMounted, ref } from 'vue'
import { RefreshCw, Trophy } from 'lucide-vue-next'
import { referralApi } from '../../services/referralApi'
import { managementDate } from '../../domain/platformManagement'
import { yuan } from '../../stores/billing'

// 推广人排行：领过邀请码的人，按带来的返利从高到低。点名字看这个人的返利明细。
const emit = defineEmits(['show-commissions'])
const rows = ref([])
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = await referralApi.promoters()
  } catch (failure) {
    error.value = failure.message || '推广人排行暂时无法读取'
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<template>
  <section class="management-list" :aria-busy="loading" aria-label="推广人排行">
    <div class="mg-list-caption"><h2>推广人排行</h2><span>按带来的返利从高到低，返利一样时邀请商户多的在前</span></div>
    <div v-if="error" class="mg-state mg-error" role="alert"><Trophy :size="30" /><h3>暂时无法读取</h3><p>{{ error }}</p><button class="mg-button" type="button" :disabled="loading" @click="load">重新读取</button></div>
    <div v-else-if="loading && !rows.length" class="mg-state" role="status"><RefreshCw :size="27" class="mg-spinning" /><p>正在读取推广人…</p></div>
    <div v-else-if="!rows.length" class="mg-state"><Trophy :size="32" /><h3>还没有推广人</h3><p>用户实名核验通过并领取邀请码后会出现在这里。</p></div>
    <div v-else class="management-table-wrap">
      <table class="management-table">
        <thead><tr><th>#</th><th>推广人</th><th>邀请码</th><th class="referral-money">邀请商户</th><th class="referral-money">已产生返利的商户</th><th class="referral-money">返利合计</th><th class="referral-money">冻结中 / 可提现</th></tr></thead>
        <tbody>
          <tr v-for="(item, index) in rows" :key="item.userId">
            <td>{{ index + 1 }}</td>
            <td><button class="referral-link" type="button" @click="emit('show-commissions', item.userId)">{{ item.name || `用户 #${item.userId}` }}</button><small>{{ item.phoneMasked }} · {{ item.tenantName || '—' }}</small></td>
            <td class="mg-mono"><span>{{ item.code }}</span><small>{{ managementDate(item.codeCreatedAt) }} 领取</small></td>
            <td class="referral-money">{{ item.inviteeCount }}</td>
            <td class="referral-money">{{ item.payingInviteeCount }}</td>
            <td class="referral-money"><strong>{{ yuan(item.commissionMinor) }}</strong></td>
            <td class="referral-money">{{ yuan(item.frozenMinor) }} / {{ yuan(item.availableMinor) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.referral-money { text-align: right; white-space: nowrap; }
.referral-link { padding: 0; border: 0; background: none; color: var(--mg-accent); font: inherit; text-align: left; cursor: pointer; }
.referral-link:hover { text-decoration: underline; }
</style>
