<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { backofficeApi } from '../../src/services/backofficeApi.js'
import { adminRoles } from '../../src/domain/backoffice.js'
import { managementDate } from '../../src/domain/platformManagement.js'
import OperationsDialog from '../../src/components/OperationsDialog.vue'
import { validUid } from '../../src/services/partnerApi.js'
import '../../src/platform-management.css'
import '../../src/backoffice.css'
const assignments = ref([])
const logs = ref(null)
const loading = ref(false)
const error = ref('')
const rolesError = ref('')
const busy = ref(false)
const notice = ref('')
const page = ref(0)
const filters = reactive({ days: '30', keyword: '', result: '' })
const form = reactive({ uid: '', roles: [] })
const confirming = ref(false)
const pages = computed(() => Math.ceil((logs.value?.total || 0) / 20))
let generation = 0
async function loadLogs() {
  const current = ++generation; loading.value = true; error.value = ''
  try { const data = await backofficeApi.operations({ ...filters, page: page.value, size: 20 }); if (current === generation) logs.value = data }
  catch (failure) { if (current === generation) { logs.value = null; error.value = failure.message || '操作日志读取失败' } }
  finally { if (current === generation) loading.value = false }
}
async function loadRoles() { rolesError.value = ''; try { assignments.value = await backofficeApi.roles() } catch (failure) { rolesError.value = failure.message || '管理员角色读取失败' } }
function search() { page.value = 0; loadLogs() }
function move(next) { page.value = next; loadLogs() }
function edit(item) { form.uid = String(item.uid); form.roles = [...item.roles]; notice.value = '' }
function review() { rolesError.value = ''; if (!validUid(form.uid)) { rolesError.value = '请输入有效的八位用户 UID'; return }; confirming.value = true }
async function save() {
  busy.value = true; rolesError.value = ''; notice.value = ''
  try { assignments.value = await backofficeApi.assignRoles({ uid: Number(form.uid), roles: [...form.roles] }); notice.value = `UID ${form.uid} 的管理员角色已更新`; confirming.value = false; await loadLogs() }
  catch (failure) { rolesError.value = failure.message || '管理员角色更新失败'; confirming.value = false }
  finally { busy.value = false }
}
onMounted(() => { loadRoles(); loadLogs() })
</script>
<template>
  <div class="management-page">
    <header class="bo-heading"><div><h1>权限与操作日志</h1><p>按岗位分配后台权限，查询两个后台的操作结果和处理人</p></div><button class="mg-button" :disabled="loading || busy" @click="loadRoles(); loadLogs()">刷新</button></header>
    <section class="bo-panel">
      <h2 class="bo-compact-title">管理员角色</h2><p class="bo-muted">审核专员处理内容、入驻及联盟审核；财务专员管理订单、销售对账、服务商与结算；客服专员管理用户和实名资料。系统管理员拥有全部权限。</p>
      <p v-if="rolesError" class="mg-feedback mg-error" role="alert">{{ rolesError }}</p><p v-if="notice" class="mg-feedback success" role="status">{{ notice }}</p>
      <form class="bo-form" @submit.prevent="review"><label class="bo-field">用户 UID<input v-model="form.uid" required inputmode="numeric" pattern="[1-9][0-9]{7}" maxlength="8" placeholder="输入一方志账号的八位 UID" :disabled="busy" /></label><div class="bo-field"><span>分配角色（可多选）</span><div class="bo-checks"><label v-for="(label, key) in adminRoles" :key="key"><input v-model="form.roles" type="checkbox" :value="key" :disabled="busy" />{{ label }}</label></div></div><div class="bo-actions bo-wide"><span class="bo-muted">不选任何角色会移除该账号在此处获得的权限。</span><button class="mg-button mg-primary" type="submit" :disabled="busy">检查并保存</button></div></form>
      <div class="management-table-wrap"><table class="management-table"><thead><tr><th>管理员</th><th>UID</th><th>已分配角色</th><th>服务器配置授权</th><th>操作</th></tr></thead><tbody><tr v-for="item in assignments" :key="item.uid"><td>{{ item.name }}</td><td class="mg-mono">{{ item.uid }}</td><td>{{ item.roles.map(role => adminRoles[role]).join('、') || '未分配角色' }}</td><td>{{ [item.platformBootstrap ? '平台运营' : '', item.billingBootstrap ? '计费运营' : ''].filter(Boolean).join('、') || '—' }}</td><td><button class="mg-text-button" :disabled="busy" @click="edit(item)">调整角色</button></td></tr></tbody></table></div><p class="bo-muted">服务器配置的运营账号保留其原有权限。如需撤销这部分授权，应调整服务器配置。</p>
    </section>
    <section class="bo-panel">
      <h2 class="bo-compact-title">操作日志</h2><form class="management-filters" @submit.prevent="search"><label class="bo-field">记录时间<select v-model="filters.days" @change="search"><option value="7">近 7 天</option><option value="30">近 30 天</option><option value="90">近 90 天</option><option value="365">近一年</option></select></label><label class="bo-field">操作结果<select v-model="filters.result" @change="search"><option value="">全部结果</option><option value="SUCCESS">成功</option><option value="FAILED">失败</option></select></label><label class="bo-field">搜索<input v-model="filters.keyword" maxlength="160" placeholder="处理人、操作或对象编号" /></label><button class="mg-button" type="submit" :disabled="loading">查询</button></form><p class="bo-muted">从本功能上线起记录管理员和服务商后台的新增、修改及处理操作，保留原有业务流水供详细核对。</p>
      <p v-if="loading" role="status">正在读取操作日志…</p><p v-if="error" class="mg-feedback mg-error" role="alert">{{ error }}</p><div v-if="logs" class="management-table-wrap"><table class="management-table"><thead><tr><th>时间</th><th>操作人</th><th>后台</th><th>操作</th><th>对象 / 接口</th><th>结果</th></tr></thead><tbody><tr v-for="item in logs.items" :key="item.id"><td>{{ managementDate(item.createdAt) }}</td><td>{{ item.actorName }}<small>用户 #{{ item.actorUserId }}</small></td><td>{{ item.partnerId ? `服务商 #${item.partnerId}` : '管理员' }}</td><td>{{ item.action }}</td><td class="mg-mono">{{ item.method }} {{ item.path }}</td><td>{{ item.httpStatus < 300 ? '成功' : '失败' }}<small>HTTP {{ item.httpStatus }}</small></td></tr></tbody></table><p v-if="!logs.total && !loading" class="mg-state">尚无匹配的操作记录</p></div><footer v-if="logs" class="management-pagination"><span>共 {{ logs.total }} 条</span><div><button class="mg-button" :disabled="!page || loading" @click="move(page - 1)">上一页</button><span>{{ page + 1 }} / {{ Math.max(1, pages) }}</span><button class="mg-button" :disabled="page + 1 >= pages || loading" @click="move(page + 1)">下一页</button></div></footer>
    </section>
    <OperationsDialog :open="confirming" title="确认管理员权限变更" :busy="busy" @close="confirming = false"><p>用户 UID：<strong>{{ form.uid }}</strong></p><p>{{ form.roles.length ? `调整后的角色：${form.roles.map(role => adminRoles[role]).join('、')}` : '移除该账号在此处获得的全部管理员角色' }}</p><p v-if="form.roles.includes('ADMIN')" class="bo-muted">系统管理员可以调整其他管理员角色并执行财务操作。</p><div class="bo-actions"><button class="mg-button" :disabled="busy" @click="confirming = false">返回修改</button><button class="mg-button mg-primary" :disabled="busy" @click="save">{{ busy ? '正在保存…' : '确认保存' }}</button></div></OperationsDialog>
  </div>
</template>
