<script setup>
import { onMounted, ref } from 'vue'
import { ChevronLeft, ChevronRight, RefreshCw, Search, Users, X } from 'lucide-vue-next'
import AppSelect from '../AppSelect.vue'
import { referralApi } from '../../services/referralApi'
import { managementDate } from '../../domain/platformManagement'
import { yuan } from '../../stores/billing'

// 邀请关系：每个商户归在谁名下。没有邀请人的归属公司。
const emit = defineEmits(['show-commissions'])
const scopes = { ALL: '全部商户', INVITED: '有邀请人', COMPANY: '归属公司' }
const rows = ref([])
const total = ref(null)
const totalPages = ref(0)
const page = ref(0)
const keyword = ref('')
const scope = ref('ALL')
const loading = ref(false)
const error = ref('')
let version = 0

async function load() {
  const attempt = ++version
  loading.value = true
  error.value = ''
  try {
    const result = await referralApi.relations({ keyword: keyword.value.trim(), scope: scope.value, page: page.value, size: 20 })
    if (attempt !== version) return
    rows.value = result.items
    total.value = result.total
    totalPages.value = result.totalPages
  } catch (failure) {
    if (attempt === version) error.value = failure.message || '邀请关系暂时无法读取'
  } finally {
    if (attempt === version) loading.value = false
  }
}
function search() { page.value = 0; load() }
function movePage(next) { page.value = next; load() }
onMounted(load)
</script>

<template>
  <section class="management-list" :aria-busy="loading" aria-label="邀请关系">
    <form class="management-filters" @submit.prevent="search">
      <label class="mg-search"><Search :size="17" /><input v-model="keyword" placeholder="搜索商户、姓名、手机号或邀请码" aria-label="搜索" maxlength="40" /><button v-if="keyword" type="button" aria-label="清空搜索" @click="keyword = ''; search()"><X :size="14" /></button></label>
      <label class="mg-select"><span class="sr-only">归属</span><AppSelect v-model="scope" @change="search"><option v-for="(label, value) in scopes" :key="value" :value="value">{{ label }}</option></AppSelect></label>
      <button class="mg-button mg-primary" type="submit" :disabled="loading">查询</button>
    </form>
    <div v-if="error" class="mg-state mg-error" role="alert"><Users :size="30" /><h3>暂时无法读取</h3><p>{{ error }}</p><button class="mg-button" type="button" :disabled="loading" @click="load">重新读取</button></div>
    <div v-else-if="loading && !rows.length" class="mg-state" role="status"><RefreshCw :size="27" class="mg-spinning" /><p>正在读取邀请关系…</p></div>
    <div v-else-if="!rows.length" class="mg-state"><Users :size="32" /><h3>没有匹配的商户</h3><p>调整关键词或筛选条件后重新查询。</p></div>
    <div v-else class="management-table-wrap">
      <table class="management-table">
        <thead><tr><th>商户</th><th>注册时间</th><th>归属</th><th>邀请码</th><th class="referral-money">已返利订单实付</th><th class="referral-money">带来返利</th></tr></thead>
        <tbody>
          <tr v-for="item in rows" :key="item.tenantId">
            <td><span>{{ item.tenantName || `商户 #${item.tenantId}` }}</span><small>{{ item.ownerName || '—' }} · {{ item.ownerPhoneMasked }}</small></td>
            <td class="mg-time">{{ managementDate(item.registeredAt) }}</td>
            <td v-if="item.referrerUserId"><button class="referral-link" type="button" @click="emit('show-commissions', item.referrerUserId)">{{ item.referrerName || `用户 #${item.referrerUserId}` }}</button><small>{{ item.referrerPhoneMasked }} · {{ item.referrerTenantName || '—' }}</small></td>
            <td v-else><span class="mg-status"><i />公司</span></td>
            <td class="mg-mono">{{ item.code || '—' }}</td>
            <td class="referral-money"><span>充值 {{ yuan(item.topupPaidMinor) }}</span><small>会员 {{ yuan(item.membershipPaidMinor) }}</small></td>
            <td class="referral-money"><strong>{{ item.referrerUserId ? yuan(item.commissionMinor) : '—' }}</strong></td>
          </tr>
        </tbody>
      </table>
    </div>
    <footer v-if="!error" class="management-pagination"><span>{{ total == null ? '正在读取' : `共 ${total} 个商户` }}</span><div><button class="mg-page-button" type="button" :disabled="page === 0 || loading" aria-label="上一页" @click="movePage(page - 1)"><ChevronLeft :size="16" /></button><span>{{ totalPages ? page + 1 : 0 }} / {{ totalPages }}</span><button class="mg-page-button" type="button" :disabled="page + 1 >= totalPages || loading" aria-label="下一页" @click="movePage(page + 1)"><ChevronRight :size="16" /></button></div></footer>
  </section>
</template>

<style scoped>
.referral-money { text-align: right; white-space: nowrap; }
.referral-link { padding: 0; border: 0; background: none; color: var(--mg-accent); font: inherit; text-align: left; cursor: pointer; }
.referral-link:hover { text-decoration: underline; }
</style>
