<script setup>
import { ref } from 'vue'
import { Check, Plus, RefreshCw, X } from 'lucide-vue-next'
import { partnerApi, validUid } from '../../src/services/partnerApi.js'
import { managementDate } from '../../src/domain/platformManagement.js'
import { partnerRoles } from '../../src/domain/partnerOps.js'
import { loadPartner, loadTeam, partner } from '../partnerStore.js'

const loading = ref(false)
const busy = ref(false)
const error = ref('')
const success = ref('')
const uid = ref('')
const addError = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try { await loadTeam() }
  catch (failure) { error.value = failure.message || '团队暂时无法读取。' }
  finally { loading.value = false }
}

async function add() {
  if (busy.value) return
  addError.value = validUid(uid.value) ? '' : 'UID 是 8 位数字，请让对方在一方志「套餐与权益」页查看。'
  if (addError.value) return
  busy.value = true
  try {
    partner.team = await partnerApi.addBd(uid.value.trim())
    success.value = '已添加 BD，对方用自己的账号登录本后台即可'
    uid.value = ''
  } catch (failure) {
    addError.value = failure.message || '添加失败，请稍后再试。'
  } finally { busy.value = false }
}

async function remove(member) {
  if (busy.value) return
  if (!window.confirm(`移除 BD「${member.name || member.uid}」？他名下的 ${member.tenantCount || 0} 个商户会回到你直接管，他生成的激活码照常可用。`)) return
  busy.value = true
  try {
    partner.team = await partnerApi.removeBd(member.userId)
    success.value = `已移除「${member.name || member.uid}」`
    await loadPartner(true)
  } catch (failure) {
    error.value = failure.message || '移除失败，请稍后再试。'
  } finally { busy.value = false }
}
load()
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><h1>团队</h1><p>BD 用自己的一方志账号登录本后台，只看得到自己名下的商户和自己生成的激活码；可以加商户、生成激活码、给商户续期，不能分烟火。商户在 BD 之间调配请到「名下商户」。</p></div>
      <div class="management-heading-actions"><button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button></div>
    </header>
    <div v-if="success" class="mg-feedback success" role="status"><Check :size="16" />{{ success }}<button type="button" aria-label="关闭提示" @click="success = ''"><X :size="15" /></button></div>

    <section class="management-list" aria-labelledby="add-bd-title">
      <div class="mg-list-caption"><h2 id="add-bd-title">添加 BD</h2></div>
      <form class="partner-form" @submit.prevent="add">
        <label>BD 的 UID<input v-model="uid" inputmode="numeric" maxlength="8" placeholder="8 位数字" /></label>
        <button class="mg-button mg-primary" type="submit" :disabled="busy"><Plus :size="15" />添加</button>
        <p v-if="addError" class="mg-error" role="alert">{{ addError }}</p>
      </form>
    </section>

    <section class="management-list" :aria-busy="loading" aria-label="团队成员">
      <div v-if="error" class="mg-state mg-error" role="alert"><p>{{ error }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>
      <div v-else class="management-table-wrap">
        <table class="management-table">
          <thead><tr><th>成员</th><th>角色</th><th class="code-number">名下商户</th><th>加入时间</th><th class="mg-table-action">操作</th></tr></thead>
          <tbody>
            <tr v-for="member in partner.team" :key="member.userId">
              <td><span>{{ member.name || '未命名' }}</span><small>UID {{ member.uid || '—' }}</small></td>
              <td>{{ partnerRoles[member.role] || member.role }}</td>
              <td class="code-number mg-mono">{{ member.role === 'BD' ? member.tenantCount : '全部' }}</td>
              <td class="mg-time">{{ managementDate(member.createdAt) }}</td>
              <td class="mg-table-action"><button v-if="member.role === 'BD'" class="mg-text-button" type="button" :disabled="busy" @click="remove(member)">移除</button><template v-else>—</template></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>
