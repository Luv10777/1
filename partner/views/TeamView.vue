<script setup>
import { computed, ref } from 'vue'
import { Check, Plus, RefreshCw, X } from 'lucide-vue-next'
import { managementDate } from '../../src/domain/platformManagement.js'
import { number, partnerRoles, permissionText } from '../../src/domain/partnerOps.js'
import { can, loadTeam, partner } from '../partnerStore.js'
import MemberDrawer from '../components/MemberDrawer.vue'
import AddMemberModal from '../components/AddMemberModal.vue'

// 团队：成员都是团队账户的员工，在一方志创作用的是团队烟火；在这里按权限做事。点一行看详情，有团队管理权限的可以加人、改权限、移除。
const loading = ref(false)
const error = ref('')
const success = ref('')
const adding = ref(false)
const openId = ref(null)
const openMember = computed(() => partner.team.find(m => m.userId === openId.value) || null)

async function load() {
  loading.value = true
  error.value = ''
  try { await loadTeam() }
  catch (failure) { error.value = failure.message || '团队暂时无法读取。' }
  finally { loading.value = false }
}
function added(team, name) {
  partner.team = team
  adding.value = false
  success.value = `已添加「${name}」。对方用这个手机号登录一方志，就能在右上角进入服务商后台。`
}
function removed(member) {
  openId.value = null
  success.value = `已移除「${member.displayName || member.name || member.uid}」`
}
function saved(message) { success.value = message }
load()
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><h1>团队</h1><p>成员都是团队账户的员工：在一方志创作用的是团队烟火，在服务商后台按勾选的权限做事。名下商户整个团队共有。点一行查看成员做了什么{{ can('team') ? '，或改权限、移除' : '' }}。</p></div>
      <div class="management-heading-actions">
        <button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button>
        <button v-if="can('team')" class="mg-button mg-primary" type="button" @click="adding = true"><Plus :size="15" />添加成员</button>
      </div>
    </header>
    <div v-if="success" class="mg-feedback success" role="status"><Check :size="16" />{{ success }}<button type="button" aria-label="关闭提示" @click="success = ''"><X :size="15" /></button></div>

    <section class="management-list" :aria-busy="loading" aria-label="团队成员">
      <div v-if="error" class="mg-state mg-error" role="alert"><p>{{ error }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>
      <template v-else>
        <div class="management-table-wrap">
          <table class="management-table">
            <thead><tr><th>成员</th><th>角色</th><th>权限</th><th class="code-number">本月分配</th><th class="code-number">加的商户</th><th class="code-number">激活码 生成 / 兑换</th><th>加入时间</th></tr></thead>
            <tbody>
              <tr v-for="m in partner.team" :key="m.userId" class="partner-row" tabindex="0" @click="openId = m.userId" @keydown.enter="openId = m.userId">
                <td><span>{{ m.displayName || m.name || '未填写' }}</span><small>{{ m.displayName && m.name && m.name !== m.displayName ? `${m.name} · ` : '' }}UID {{ m.uid || '—' }}</small></td>
                <td><span class="partner-pill" :class="{ bd: m.role === 'OWNER' }">{{ partnerRoles[m.role] || m.role }}</span></td>
                <td>{{ permissionText(m) }}</td>
                <td class="code-number mg-mono">{{ number(m.distributedThisMonth) }}<small v-if="m.monthlyCap"> / {{ number(m.monthlyCap) }}</small></td>
                <td class="code-number mg-mono">{{ number(m.tenantsAdded) }}</td>
                <td class="code-number mg-mono">{{ number(m.codes?.generated) }} / {{ number(m.codes?.redeemed) }}</td>
                <td class="mg-time">{{ managementDate(m.createdAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="partner.team.length < 2 && !loading" class="mg-state"><h3>还没有其他成员</h3><p>{{ can('team') ? '点右上角「添加成员」，输入员工的手机号。' : '请负责人添加成员。' }}</p></div>
      </template>
    </section>

    <MemberDrawer :member="openMember" @close="openId = null" @removed="removed" @saved="saved" />
    <AddMemberModal :open="adding" @close="adding = false" @added="added" />
  </div>
</template>
