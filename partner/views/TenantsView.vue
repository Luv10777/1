<script setup>
import AppSelect from '../../src/components/AppSelect.vue'
import { computed, reactive, ref } from 'vue'
import { Check, ChevronLeft, ChevronRight, Coins, Plus, RefreshCw, Search, X } from 'lucide-vue-next'
import { partnerApi, tenantSources } from '../../src/services/partnerApi.js'
import { managementDate } from '../../src/domain/platformManagement.js'
import { expiryFilters, membershipState, number } from '../../src/domain/partnerOps.js'
import { bds, isOwner, loadPartner, loadTeam, partner } from '../partnerStore.js'
import TenantDrawer from '../components/TenantDrawer.vue'
import AddByUidModal from '../components/AddByUidModal.vue'
import BatchTransferModal from '../components/BatchTransferModal.vue'

// 名下商户：列表只负责浏览和筛选；点一行打开详情抽屉，续期、分烟火、调整归属都在抽屉里做。
const records = ref([])
const total = ref(0)
const page = ref(0)
const keyword = ref('')
const expiry = ref('')
const bdFilter = ref('')
const loading = ref(false)
const error = ref('')
const success = ref('')
const pages = computed(() => Math.ceil(total.value / 20))
const owner = computed(() => isOwner())
const available = computed(() => partner.overview?.partner.creditsAvailable || 0)

const openTenant = ref(null)
const adding = ref(false)
// 勾选要批量分烟火的商户，翻页也保留。
const picked = reactive(new Map())
const batch = ref([])
const allPicked = computed(() => records.value.length > 0 && records.value.every(r => picked.has(r.tenantId)))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const query = { page: page.value, size: 20, keyword: keyword.value.trim(), expiry: expiry.value }
    if (owner.value && bdFilter.value !== '') query.bdUserId = bdFilter.value
    const result = await partnerApi.tenants(query)
    records.value = result.items
    total.value = result.total
  } catch (failure) {
    records.value = []
    error.value = failure.message || '名下商户暂时无法读取。'
  } finally { loading.value = false }
}
function search() { page.value = 0; load() }
function movePage(next) { page.value = next; load() }
function replace(row) { records.value = records.value.map(r => r.tenantId === row.tenantId ? row : r) }

function togglePick(item) { picked.has(item.tenantId) ? picked.delete(item.tenantId) : picked.set(item.tenantId, item) }
function togglePage() {
  if (allPicked.value) records.value.forEach(r => picked.delete(r.tenantId))
  else records.value.forEach(r => picked.set(r.tenantId, r))
}

async function added(tenant) {
  adding.value = false
  success.value = `已添加「${tenant.tenantName || '商户'}」`
  await Promise.all([search(), loadPartner(true), owner.value ? loadTeam() : null])
}
function batchDone(updated) {
  updated.forEach(replace)
  updated.forEach(row => picked.delete(row.tenantId))
  if (updated.length) success.value = `已给 ${updated.length} 个商户分配烟火`
}
function closeBatch() { batch.value = [] }
async function tenantUpdated(row) {
  replace(row)
  if (owner.value) await loadTeam().catch(() => {})
}

const sourceText = item => tenantSources[item.source] || item.source
if (owner.value) loadTeam().catch(() => {})
load()
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><h1>{{ owner ? '名下商户' : '我名下的商户' }}</h1><p>点一行查看商户详情，在里面续期{{ owner ? '、分配烟火、调整归属的 BD' : '' }}。<template v-if="owner">可分配烟火：{{ number(available) }}。</template><template v-else>烟火由负责人分配。</template></p></div>
      <div class="management-heading-actions">
        <button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button>
        <button class="mg-button mg-primary" type="button" @click="adding = true"><Plus :size="15" />添加商户</button>
      </div>
    </header>
    <div v-if="success" class="mg-feedback success" role="status"><Check :size="16" />{{ success }}<button type="button" aria-label="关闭提示" @click="success = ''"><X :size="15" /></button></div>

    <section class="management-list" :aria-busy="loading" aria-label="名下商户列表">
      <form class="management-filters" @submit.prevent="search">
        <label class="mg-search"><Search :size="17" /><input v-model="keyword" placeholder="搜索商户名、联系人或 UID" aria-label="搜索商户" maxlength="60" /></label>
        <label class="mg-select"><span class="sr-only">会员状态</span><AppSelect v-model="expiry" @change="search"><option value="">全部会员状态</option><option v-for="(label, value) in expiryFilters" :key="value" :value="value">{{ label }}</option></AppSelect></label>
        <label v-if="owner" class="mg-select"><span class="sr-only">BD</span><AppSelect v-model="bdFilter" @change="search"><option value="">全部 BD</option><option value="0">我直接管的</option><option v-for="b in bds()" :key="b.userId" :value="String(b.userId)">{{ b.name || b.uid }}</option></AppSelect></label>
        <button class="mg-button mg-primary" type="submit" :disabled="loading">查询</button>
      </form>
      <div v-if="owner && picked.size" class="partner-toolbar">
        <span>已选 {{ picked.size }} 个商户</span>
        <button class="mg-text-button" type="button" @click="picked.clear()">清空</button>
        <button class="mg-button mg-primary" type="button" @click="batch = [...picked.values()]"><Coins :size="15" />批量分配烟火</button>
      </div>
      <div v-if="error" class="mg-state mg-error" role="alert"><p>{{ error }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>
      <div v-else-if="!records.length && !loading" class="mg-state"><h3>{{ keyword || expiry || bdFilter ? '没有匹配的商户' : '还没有名下商户' }}</h3><p>点右上角「添加商户」按 UID 添加；商户兑换了你生成的激活码也会自动归到你名下。</p></div>
      <div v-else class="management-table-wrap">
        <table class="management-table">
          <thead>
            <tr>
              <th v-if="owner" class="partner-check"><input type="checkbox" :checked="allPicked" aria-label="选择本页全部商户" @change="togglePage" /></th>
              <th>商户</th><th>联系人</th><th>会员</th><th class="code-number">烟火余额</th><th v-if="owner">归属</th><th>加入</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in records" :key="item.tenantId" class="partner-row" tabindex="0" @click="openTenant = item.tenantId" @keydown.enter="openTenant = item.tenantId">
              <td v-if="owner" class="partner-check" @click.stop><input type="checkbox" :checked="picked.has(item.tenantId)" :aria-label="`选择${item.tenantName || '商户'}`" @change="togglePick(item)" /></td>
              <td><span>{{ item.tenantName || '—' }}</span><small>商户 #{{ item.tenantId }}</small></td>
              <td><span>{{ item.ownerName || '—' }}</span><small>UID {{ item.ownerUid || '—' }}</small></td>
              <td :class="['partner-member', membershipState(item).tone]"><span>{{ membershipState(item).short }}</span><small v-if="item.memberUntil">{{ item.planName || '会员' }} · {{ managementDate(item.memberUntil).slice(0, 10) }}</small></td>
              <td class="code-number mg-mono">{{ item.creditsAvailable == null ? '—' : number(item.creditsAvailable) }}</td>
              <td v-if="owner"><span class="partner-pill" :class="{ bd: item.bdUserId }">{{ item.bdUserId ? `BD · ${item.bdName || '—'}` : '我直接管' }}</span></td>
              <td><span>{{ sourceText(item) }}</span><small>{{ managementDate(item.boundAt) }}</small></td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer v-if="pages > 1" class="management-pagination"><span>共 {{ number(total) }} 个商户</span><div><button class="mg-page-button" type="button" :disabled="page === 0 || loading" aria-label="上一页" @click="movePage(page - 1)"><ChevronLeft :size="16" /></button><span>{{ page + 1 }} / {{ pages }}</span><button class="mg-page-button" type="button" :disabled="page + 1 >= pages || loading" aria-label="下一页" @click="movePage(page + 1)"><ChevronRight :size="16" /></button></div></footer>
    </section>

    <TenantDrawer :tenant-id="openTenant" @close="openTenant = null" @updated="tenantUpdated" />
    <AddByUidModal :open="adding" mode="tenant" @close="adding = false" @added="added" />
    <BatchTransferModal :tenants="batch" @close="closeBatch" @done="batchDone" />
  </div>
</template>
