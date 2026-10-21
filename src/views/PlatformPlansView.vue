<script setup>
import { computed, reactive, ref } from 'vue'
import { Check, ChevronRight, CopyPlus, Layers, Plus, RefreshCw, X } from 'lucide-vue-next'
import { planAdminApi as api } from '../services/planAdminApi'
import '../platform-management.css'

const listings = ref([])
const loading = ref(false)
const error = ref('')
const forbidden = ref(false)
const success = ref('')
const busy = ref(false)
const formOpen = ref(false)
const formError = ref('')
const blank = () => ({ code: '', name: '', description: '', price: '', durationDays: '30', giftCredits: '0', allianceQuota: '0', imageEnabled: true, videoEnabled: true, liveEnabled: false, published: false, replacePublished: true, lockedCode: false })
const form = reactive(blank())

const money = minor => `¥${(Number(minor || 0) / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
const number = value => Number(value || 0).toLocaleString('zh-CN')
const date = value => value ? new Date(value).toLocaleString('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }) : '—'
const rights = plan => [plan.imageEnabled && '图片', plan.videoEnabled && '视频', plan.liveEnabled && '直播'].filter(Boolean).join(' · ') || '—'

/** 按套餐标识分组：上架版本在前，其余按创建时间倒序。 */
const groups = computed(() => {
  const map = new Map()
  for (const entry of listings.value) {
    if (!map.has(entry.plan.code)) map.set(entry.plan.code, [])
    map.get(entry.plan.code).push(entry)
  }
  return [...map.entries()].map(([code, versions]) => ({
    code,
    current: versions.find(v => v.published) || null,
    versions: versions.sort((a, b) => Number(b.published) - Number(a.published) || new Date(b.createdAt) - new Date(a.createdAt)),
  })).sort((a, b) => Number(Boolean(b.current)) - Number(Boolean(a.current)) || (a.current?.plan.priceMinor ?? 0) - (b.current?.plan.priceMinor ?? 0))
})
const codeTaken = computed(() => !form.lockedCode && listings.value.some(entry => entry.plan.code === form.code.trim()))
const codePublished = computed(() => listings.value.some(entry => entry.plan.code === form.code.trim() && entry.published))

async function load() {
  loading.value = true
  error.value = ''
  forbidden.value = false
  try { listings.value = await api.plans() }
  catch (failure) {
    if (failure.status === 403) forbidden.value = true
    else error.value = failure.message || '套餐暂时无法读取。'
  } finally { loading.value = false }
}

function openNew() { Object.assign(form, blank()); formError.value = ''; formOpen.value = true }

/** 基于某个版本新建：带出原内容，标识不变，改完保存就是这个套餐的新版本。 */
function openVersion(entry) {
  const plan = entry.plan
  Object.assign(form, blank(), {
    code: plan.code, name: plan.name, description: plan.description, price: (plan.priceMinor / 100).toFixed(2),
    durationDays: String(plan.durationDays), giftCredits: String(plan.giftCredits), allianceQuota: (plan.allianceQuotaMinor / 100).toFixed(2),
    imageEnabled: plan.imageEnabled, videoEnabled: plan.videoEnabled, liveEnabled: plan.liveEnabled, lockedCode: true,
  })
  formError.value = ''
  formOpen.value = true
}

async function save() {
  if (busy.value) return
  formError.value = ''
  const priceMinor = Math.round(Number(form.price) * 100)
  const allianceQuotaMinor = Math.round(Number(form.allianceQuota || 0) * 100)
  if (!/^[A-Z0-9_-]{2,60}$/.test(form.code.trim())) { formError.value = '套餐标识只能用大写字母、数字、下划线或短横线，2–60 位。'; return }
  if (!Number.isInteger(priceMinor) || priceMinor < 1) { formError.value = '请填写有效的价格。'; return }
  if (!Number.isInteger(allianceQuotaMinor) || allianceQuotaMinor < 0) { formError.value = '联盟券额度不能小于 0。'; return }
  busy.value = true
  try {
    await api.create({
      code: form.code.trim(), name: form.name.trim(), description: form.description.trim(), priceMinor,
      durationDays: Number(form.durationDays), giftCredits: Number(form.giftCredits), imageEnabled: form.imageEnabled,
      videoEnabled: form.videoEnabled, liveEnabled: form.liveEnabled, published: form.published, allianceQuotaMinor,
      replacePublished: form.published && form.replacePublished,
    })
    success.value = form.published ? `「${form.name.trim()}」已保存并上架` : `「${form.name.trim()}」已保存，未上架`
    formOpen.value = false
    await load()
  } catch (failure) {
    formError.value = failure.message || '保存失败，请稍后再试。'
  } finally { busy.value = false }
}

async function togglePublish(group, entry) {
  if (busy.value) return
  const publishing = !entry.published
  const replacing = publishing && group.current && group.current.plan.id !== entry.plan.id
  if (!publishing && !window.confirm(`下架「${entry.plan.name}」后，商户在套餐页看不到它，也不能再订阅或续订这个套餐标识。已购会员不受影响。确定下架吗？`)) return
  if (replacing && !window.confirm(`上架这个版本，会同时下架当前上架的版本（${money(group.current.plan.priceMinor)}）。已购会员不受影响，之后续订按新版本。确定吗？`)) return
  busy.value = true
  error.value = ''
  try {
    await api.publish(entry.plan.id, publishing, Boolean(replacing))
    success.value = publishing ? `「${entry.plan.name}」已上架` : `「${entry.plan.name}」已下架`
    await load()
  } catch (failure) {
    error.value = failure.message || '操作失败，请稍后再试。'
  } finally { busy.value = false }
}

load()
</script>

<template>
  <div class="management-page plans-page">
    <header class="management-heading">
      <div><div class="management-breadcrumb"><span>平台管理</span><ChevronRight :size="13" /><span>套餐</span></div><h1>会员套餐</h1><p>商户在「套餐与权益」页看到的就是这里上架的套餐。已建版本不能修改，改价格或权益请基于原版本新建。</p></div>
      <div class="management-heading-actions">
        <button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button>
        <button v-if="!forbidden" class="mg-button mg-primary" type="button" @click="openNew"><Plus :size="15" />新建套餐</button>
      </div>
    </header>
    <div v-if="success" class="mg-feedback success" role="status"><Check :size="16" />{{ success }}<button type="button" aria-label="关闭提示" @click="success = ''"><X :size="15" /></button></div>

    <section v-if="formOpen" class="management-list plan-form-panel" aria-labelledby="plan-form-title">
      <div class="mg-list-caption"><h2 id="plan-form-title">{{ form.lockedCode ? `新建「${form.code}」的新版本` : '新建套餐' }}</h2><button class="mg-text-button" type="button" @click="formOpen = false">取消</button></div>
      <form class="plan-form" @submit.prevent="save">
        <label>套餐标识<input v-model="form.code" :readonly="form.lockedCode" required maxlength="60" placeholder="例如 FANGZHI" /><small>{{ codeTaken ? '这个标识已经存在：保存后会成为它的新版本。' : '续订按标识认：同一个套餐的不同版本用同一个标识。' }}</small></label>
        <label>套餐名称<input v-model="form.name" required maxlength="100" placeholder="商户看到的名称" /></label>
        <label>价格（元）<input v-model="form.price" type="number" min="0.01" max="1000000" step="0.01" required /></label>
        <label>有效期（天）<input v-model="form.durationDays" type="number" min="1" max="3660" step="1" required /></label>
        <label>赠送烟火<input v-model="form.giftCredits" type="number" min="0" max="100000000" step="1" required /></label>
        <label>联盟券额度（元）<input v-model="form.allianceQuota" type="number" min="0" max="1000000" step="0.01" /><small>每个会员期可领的联盟券面值，不累积。0 表示不送。</small></label>
        <label class="full">套餐说明<textarea v-model="form.description" maxlength="600" rows="2" placeholder="一句话说明适合谁，商户在套餐卡片上看到" /></label>
        <fieldset class="full plan-rights">
          <legend>权益</legend>
          <label><input v-model="form.imageEnabled" type="checkbox" />图片创作</label>
          <label><input v-model="form.videoEnabled" type="checkbox" />视频创作</label>
          <label><input v-model="form.liveEnabled" type="checkbox" />直播</label>
        </fieldset>
        <fieldset class="full plan-rights">
          <legend>上架</legend>
          <label><input v-model="form.published" type="checkbox" />保存后立即上架</label>
          <label v-if="form.published && codePublished"><input v-model="form.replacePublished" type="checkbox" />同时下架这个标识当前上架的版本（不勾会保存失败）</label>
        </fieldset>
        <p v-if="formError" class="mg-error full" role="alert">{{ formError }}</p>
        <div class="full plan-form-actions"><button class="mg-button" type="button" @click="formOpen = false">取消</button><button class="mg-button mg-primary" type="submit" :disabled="busy">{{ busy ? '保存中…' : '保存' }}</button></div>
      </form>
    </section>

    <section class="management-list" :aria-busy="loading" aria-label="套餐列表">
      <div v-if="forbidden" class="mg-state mg-error" role="alert"><Layers :size="30" /><h3>没有套餐管理权限</h3><p>套餐属于计费模块，需要把你的账号加入服务端配置 BILLING_OPERATOR_USER_IDS 后才能管理。</p></div>
      <div v-else-if="error" class="mg-state mg-error" role="alert"><Layers :size="30" /><h3>暂时无法读取</h3><p>{{ error }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>
      <div v-else-if="loading && !listings.length" class="mg-state" role="status"><RefreshCw :size="27" class="mg-spinning" /><p>正在读取套餐…</p></div>
      <div v-else-if="!listings.length" class="mg-state"><Layers :size="32" /><h3>还没有套餐</h3><p>新建套餐并上架后，商户就能在「套餐与权益」页订阅。</p></div>
      <div v-else class="management-table-wrap">
        <table class="management-table">
          <thead><tr><th>套餐 / 标识</th><th>价格 / 有效期</th><th>赠送烟火</th><th>权益</th><th>联盟券额度</th><th>状态</th><th>创建时间</th><th class="mg-table-action">操作</th></tr></thead>
          <tbody v-for="group in groups" :key="group.code">
            <tr v-for="(entry, index) in group.versions" :key="entry.plan.id" :class="{ 'plan-older': index > 0 }">
              <td><strong>{{ entry.plan.name }}</strong><small class="mg-mono">{{ index === 0 ? group.code : `${group.code} · 历史版本` }}</small><small v-if="entry.plan.description" class="plan-description">{{ entry.plan.description }}</small></td>
              <td class="mg-mono">{{ money(entry.plan.priceMinor) }}<small>{{ entry.plan.durationDays }} 天</small></td>
              <td class="mg-mono">{{ number(entry.plan.giftCredits) }}</td>
              <td>{{ rights(entry.plan) }}</td>
              <td class="mg-mono">{{ entry.plan.allianceQuotaMinor ? money(entry.plan.allianceQuotaMinor) : '—' }}</td>
              <td><span class="mg-status" :class="entry.published ? 'mg-approved' : ''"><i />{{ entry.published ? '上架中' : '未上架' }}</span></td>
              <td class="mg-time">{{ date(entry.createdAt) }}</td>
              <td class="mg-table-action">
                <button class="mg-text-button" type="button" :disabled="busy" @click="openVersion(entry)"><CopyPlus :size="13" />基于此版本新建</button>
                · <button class="mg-text-button" type="button" :disabled="busy" @click="togglePublish(group, entry)">{{ entry.published ? '下架' : group.current ? '替换上架' : '上架' }}</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.plan-form-panel { margin-bottom: 20px; }
.plan-form { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px 18px; padding: 18px 22px 22px; }
.plan-form label { display: grid; gap: 6px; color: var(--mg-muted); font-size: 12px; }
.plan-form input, .plan-form textarea { width: 100%; min-height: 36px; padding: 7px 10px; border: 1px solid #dfe4e9; border-radius: 5px; background: #fff; color: var(--mg-ink); font-size: 13px; }
.plan-form input[readonly] { background: var(--mg-soft); }
.plan-form small { color: var(--mg-muted); font-size: 12px; line-height: 1.6; }
.plan-form .full { grid-column: 1 / -1; }
.plan-rights { display: flex; flex-wrap: wrap; gap: 8px 20px; padding: 0; margin: 0; border: 0; }
.plan-rights legend { margin-bottom: 6px; color: var(--mg-muted); font-size: 12px; }
.plan-rights label { display: inline-flex; align-items: center; gap: 6px; color: var(--mg-ink); font-size: 13px; }
.plan-rights input { width: 15px; min-height: 0; }
.plan-form-actions { display: flex; justify-content: flex-end; gap: 10px; }
.plan-older td { background: var(--mg-soft); }
.plan-description { display: block; max-width: 280px; white-space: normal; }
@media (max-width: 900px) { .plan-form { grid-template-columns: minmax(0, 1fr); } }
</style>
