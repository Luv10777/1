<script setup>
import { computed, ref } from 'vue'
import { Check, Plus, RefreshCw, X } from 'lucide-vue-next'
import { managementDate } from '../../src/domain/platformManagement.js'
import { number, partnerRoles } from '../../src/domain/partnerOps.js'
import { loadPartner, loadTeam, partner } from '../partnerStore.js'
import MemberDrawer from '../components/MemberDrawer.vue'
import TenantDrawer from '../components/TenantDrawer.vue'
import AddByUidModal from '../components/AddByUidModal.vue'

// 团队（只给负责人）：列表看每个成员的业绩，点一行打开成员详情；添加 BD 用弹窗先查后加。
const loading = ref(false)
const error = ref('')
const success = ref('')
const adding = ref(false)
const openId = ref(null)
const openTenant = ref(null)
const openMember = computed(() => partner.team.find(m => m.userId === openId.value) || null)
const bdCount = computed(() => partner.team.filter(m => m.role === 'BD').length)

async function load() {
  loading.value = true
  error.value = ''
  try { await loadTeam() }
  catch (failure) { error.value = failure.message || '团队暂时无法读取。' }
  finally { loading.value = false }
}
function added(team) {
  partner.team = team
  adding.value = false
  success.value = '已添加 BD，对方用自己的一方志账号登录本后台即可'
}
function removed(member) {
  openId.value = null
  success.value = `已移除 BD「${member.name || member.uid}」`
}
function showTenant(tenantId) { openId.value = null; openTenant.value = tenantId }
async function tenantUpdated() { await Promise.all([loadTeam(), loadPartner(true)]).catch(() => {}) }
load()
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><h1>团队</h1><p>BD 用自己的一方志账号登录本后台，只看得到自己名下的商户和自己生成的激活码；可以加商户、生成激活码、给商户续期，不能分烟火。点一行查看成员业绩、名下商户，或移除 BD。</p></div>
      <div class="management-heading-actions">
        <button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button>
        <button class="mg-button mg-primary" type="button" @click="adding = true"><Plus :size="15" />添加 BD</button>
      </div>
    </header>
    <div v-if="success" class="mg-feedback success" role="status"><Check :size="16" />{{ success }}<button type="button" aria-label="关闭提示" @click="success = ''"><X :size="15" /></button></div>

    <section class="management-list" :aria-busy="loading" aria-label="团队成员">
      <div v-if="error" class="mg-state mg-error" role="alert"><p>{{ error }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>
      <template v-else>
        <div class="management-table-wrap">
          <table class="management-table">
            <thead><tr><th>成员</th><th>角色</th><th class="code-number">名下商户</th><th class="code-number">生成激活码</th><th class="code-number">已兑换 / 未使用</th><th class="code-number">直接续期</th><th>加入时间</th></tr></thead>
            <tbody>
              <tr v-for="m in partner.team" :key="m.userId" class="partner-row" tabindex="0" @click="openId = m.userId" @keydown.enter="openId = m.userId">
                <td><span>{{ m.name || '未填写姓名' }}</span><small>UID {{ m.uid || '—' }}</small></td>
                <td><span class="partner-pill" :class="{ bd: m.role === 'BD' }">{{ partnerRoles[m.role] || m.role }}</span></td>
                <td class="code-number mg-mono">{{ m.role === 'BD' ? number(m.tenantCount) : '全部' }}</td>
                <td class="code-number mg-mono">{{ number(m.codes?.generated) }}</td>
                <td class="code-number mg-mono">{{ number(m.codes?.redeemed) }} / {{ number(m.codes?.unused) }}</td>
                <td class="code-number mg-mono">{{ number(m.codes?.renewals) }}</td>
                <td class="mg-time">{{ managementDate(m.createdAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="!bdCount && !loading" class="mg-state"><h3>还没有 BD</h3><p>点右上角「添加 BD」，输入对方的 UID。</p></div>
      </template>
    </section>

    <MemberDrawer :member="openMember" @close="openId = null" @removed="removed" @open-tenant="showTenant" />
    <TenantDrawer :tenant-id="openTenant" @close="openTenant = null" @updated="tenantUpdated" />
    <AddByUidModal :open="adding" mode="bd" @close="adding = false" @added="added" />
  </div>
</template>
