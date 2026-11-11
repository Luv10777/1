<script setup>
import { computed, reactive, ref } from 'vue'
import { Check, ChevronLeft, ChevronRight, Plus, RefreshCw, Search, X } from 'lucide-vue-next'
import { partnerApi, tenantSources, validUid } from '../../src/services/partnerApi.js'
import { managementDate } from '../../src/domain/platformManagement.js'
import { loadPartner, partner } from '../partnerStore.js'

const number = value => Number(value || 0).toLocaleString('zh-CN')
const records = ref([])
const total = ref(0)
const page = ref(0)
const keyword = ref('')
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const success = ref('')
const uid = ref('')
const addError = ref('')
const pages = computed(() => Math.ceil(total.value / 20))
// 每个商户一份分配表单；requestKey 在提交失败时保留，用同一个再提交不会重复扣烟火。
const transfers = reactive({})
const freshTransfer = () => ({ credits: '', requestKey: 'transfer-' + crypto.randomUUID(), error: '' })

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await partnerApi.tenants({ page: page.value, size: 20, keyword: keyword.value.trim() })
    for (const item of result.items) transfers[item.tenantId] ||= freshTransfer()
    records.value = result.items
    total.value = result.total
  } catch (failure) {
    records.value = []
    error.value = failure.message || '名下商户暂时无法读取。'
  } finally { loading.value = false }
}
function search() { page.value = 0; load() }
function movePage(next) { page.value = next; load() }

async function add() {
  if (busy.value) return
  addError.value = validUid(uid.value) ? '' : 'UID 是 8 位数字，请让商户在「套餐与权益」页查看。'
  if (addError.value) return
  busy.value = true
  try {
    const tenant = await partnerApi.addTenant(uid.value.trim())
    success.value = `已添加「${tenant.tenantName || '商户'}」`
    uid.value = ''
    await Promise.all([search(), loadPartner(true)])
  } catch (failure) {
    addError.value = failure.message || '添加失败，请稍后再试。'
  } finally { busy.value = false }
}

async function transfer(item) {
  const form = transfers[item.tenantId]
  const credits = Number(form.credits)
  form.error = Number.isInteger(credits) && credits >= 1 && credits <= 1000000 ? '' : '请输入 1 至 1,000,000 的整数。'
  if (form.error || busy.value) return
  if (!window.confirm(`把 ${number(credits)} 烟火分给「${item.tenantName || '商户'}」？分出后不能撤回。`)) return
  busy.value = true
  try {
    const updated = await partnerApi.transfer(item.tenantId, { requestKey: form.requestKey, credits })
    records.value = records.value.map(row => row.tenantId === updated.tenantId ? updated : row)
    success.value = `已给「${item.tenantName || '商户'}」分配 ${number(credits)} 烟火`
    transfers[item.tenantId] = freshTransfer()
    await loadPartner(true)
  } catch (failure) {
    form.error = failure.message || '分配失败，请稍后用同一金额再试一次。'
  } finally { busy.value = false }
}
const membership = item => item.memberActive == null ? '—' : item.memberActive ? `${item.planName || '会员'} · ${managementDate(item.memberUntil).slice(0, 10)} 到期` : '未激活'
load()
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><h1>名下商户</h1><p>输入商户的 UID 添加到名下（已归属其他服务商的不能添加），之后可以把烟火分给他们。可分配烟火：{{ number(partner.overview?.partner.creditsAvailable) }}</p></div>
      <div class="management-heading-actions"><button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button></div>
    </header>
    <div v-if="success" class="mg-feedback success" role="status"><Check :size="16" />{{ success }}<button type="button" aria-label="关闭提示" @click="success = ''"><X :size="15" /></button></div>

    <section class="management-list" aria-labelledby="add-title">
      <div class="mg-list-caption"><h2 id="add-title">添加商户</h2></div>
      <form class="partner-form" @submit.prevent="add">
        <label>商户的 UID<input v-model="uid" inputmode="numeric" maxlength="8" placeholder="8 位数字" /></label>
        <button class="mg-button mg-primary" type="submit" :disabled="busy"><Plus :size="15" />添加到名下</button>
        <p v-if="addError" class="mg-error" role="alert">{{ addError }}</p>
      </form>
    </section>

    <section class="management-list" :aria-busy="loading" aria-label="名下商户列表">
      <form class="management-filters" @submit.prevent="search">
        <label class="mg-search"><Search :size="17" /><input v-model="keyword" placeholder="搜索商户名、联系人或 UID" aria-label="搜索商户" maxlength="60" /></label>
        <button class="mg-button mg-primary" type="submit" :disabled="loading">查询</button>
      </form>
      <div v-if="error" class="mg-state mg-error" role="alert"><p>{{ error }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>
      <div v-else-if="!records.length && !loading" class="mg-state"><h3>{{ keyword ? '没有匹配的商户' : '还没有名下商户' }}</h3><p>在上方输入商户的 UID 添加；商户兑换了你生成的激活码也会自动归到你名下。</p></div>
      <div v-else class="management-table-wrap">
        <table class="management-table">
          <thead><tr><th>商户</th><th>联系人</th><th>会员</th><th class="code-number">烟火余额</th><th>归属方式</th><th class="mg-table-action">分配烟火</th></tr></thead>
          <tbody>
            <tr v-for="item in records" :key="item.tenantId">
              <td><span>{{ item.tenantName || '—' }}</span><small>商户 #{{ item.tenantId }}</small></td>
              <td><span>{{ item.ownerName || '—' }}</span><small>UID {{ item.ownerUid || '—' }}</small></td>
              <td>{{ membership(item) }}</td>
              <td class="code-number mg-mono">{{ item.creditsAvailable == null ? '—' : number(item.creditsAvailable) }}</td>
              <td><span>{{ tenantSources[item.source] || item.source }}</span><small>{{ managementDate(item.boundAt) }}</small></td>
              <td class="mg-table-action">
                <form class="partner-inline" @submit.prevent="transfer(item)"><input v-model="transfers[item.tenantId].credits" type="number" min="1" max="1000000" step="1" placeholder="烟火数" :aria-label="`分给${item.tenantName || '商户'}的烟火`" /><button class="mg-button" type="submit" :disabled="busy">分配</button></form>
                <small v-if="transfers[item.tenantId].error" class="mg-error" role="alert">{{ transfers[item.tenantId].error }}</small>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer v-if="pages > 1" class="management-pagination"><span>共 {{ number(total) }} 个商户</span><div><button class="mg-page-button" type="button" :disabled="page === 0 || loading" aria-label="上一页" @click="movePage(page - 1)"><ChevronLeft :size="16" /></button><span>{{ page + 1 }} / {{ pages }}</span><button class="mg-page-button" type="button" :disabled="page + 1 >= pages || loading" aria-label="下一页" @click="movePage(page + 1)"><ChevronRight :size="16" /></button></div></footer>
    </section>
  </div>
</template>
