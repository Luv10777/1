<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ChevronDown, Plus } from 'lucide-vue-next'
import { billingApi, number, yuan } from '../stores/billing'
const emit = defineEmits(['updated'])
const open = ref(false)
const busy = ref(false)
const error = ref('')
const plans = ref([])
const blank = () => ({ code: '', name: '', description: '', price: '', durationDays: '', giftCredits: '', allianceQuota: '', imageEnabled: false, videoEnabled: false, liveEnabled: false, published: false })
const form = reactive(blank())
const load = async () => {
  try { plans.value = await billingApi.operatorPlans() }
  catch (e) { error.value = e.message || '套餐目录暂时无法读取' }
}
const save = async () => {
  busy.value = true; error.value = ''
  try {
    const priceMinor = Math.round(Number(form.price) * 100)
    if (!Number.isInteger(priceMinor) || priceMinor < 1) throw new Error('请填写有效的套餐价格')
    await billingApi.createPlan({ code: form.code, name: form.name, description: form.description, priceMinor, durationDays: Number(form.durationDays), giftCredits: Number(form.giftCredits), imageEnabled: form.imageEnabled, videoEnabled: form.videoEnabled, liveEnabled: form.liveEnabled, published: form.published, allianceQuotaMinor: Math.round(Number(form.allianceQuota || 0) * 100) })
    Object.assign(form, blank()); await load(); emit('updated')
  } catch (e) { error.value = e.message || '套餐保存失败' }
  finally { busy.value = false }
}
const publish = async (entry) => {
  busy.value = true; error.value = ''
  try { await billingApi.publish(entry.plan.id, !entry.published); await load(); emit('updated') }
  catch (e) { error.value = e.message || '套餐状态更新失败' }
  finally { busy.value = false }
}
onMounted(load)
</script>
<template>
  <section class="yanhuo-operations">
    <button class="yanhuo-operations-toggle" type="button" :aria-expanded="open" aria-controls="plan-management" @click="open = !open"><span>套餐管理 <small>平台运营</small></span><ChevronDown :size="18" /></button>
    <div v-show="open" id="plan-management">
      <div v-if="error" class="yanhuo-alert" role="alert">{{ error }}</div>
      <div v-for="entry in plans" :key="entry.plan.id" class="yanhuo-operator-plan"><div><strong>{{ entry.plan.name }}</strong><small>{{ yuan(entry.plan.priceMinor) }} / {{ entry.plan.durationDays }} 天 · {{ number(entry.plan.giftCredits) }} 烟火{{ entry.plan.liveEnabled ? ' · 含直播' : '' }}{{ entry.plan.allianceQuotaMinor ? ` · 联盟券 ${yuan(entry.plan.allianceQuotaMinor)}` : '' }} · {{ entry.published ? '已上架' : '未上架' }}</small></div><button class="yanhuo-link" type="button" :disabled="busy" @click="publish(entry)">{{ entry.published ? '下架' : '上架' }}</button></div>
      <form class="yanhuo-plan-form" @submit.prevent="save">
        <h3>新增套餐版本</h3>
        <label>套餐标识<input v-model="form.code" required pattern="[A-Z0-9_-]{2,60}" maxlength="60" aria-describedby="plan-code-note"><small id="plan-code-note">大写字母、数字或下划线；续订沿用同一标识。</small></label>
        <label>套餐名称<input v-model="form.name" required maxlength="100"></label>
        <label>价格（元）<input v-model="form.price" type="number" min="0.01" max="1000000" step="0.01" required></label>
        <label>有效期（天）<input v-model="form.durationDays" type="number" min="1" max="3660" step="1" required></label>
        <label>赠送烟火<input v-model="form.giftCredits" type="number" min="0" max="100000000" step="1" required></label>
        <label>联盟券额度（元）<input v-model="form.allianceQuota" type="number" min="0" max="1000000" step="0.01" placeholder="0 表示不送"><small>每个会员期可领取的联盟券面值，不累积。</small></label>
        <label class="full">套餐说明<textarea v-model="form.description" maxlength="600" rows="2" /></label>
        <fieldset class="full yanhuo-plan-permissions"><legend>创作权益</legend><label><input v-model="form.imageEnabled" type="checkbox">图片创作</label><label><input v-model="form.videoEnabled" type="checkbox">视频创作</label><label><input v-model="form.liveEnabled" type="checkbox">直播</label><label><input v-model="form.published" type="checkbox">保存后上架</label></fieldset>
        <p class="yanhuo-note full">调整套餐请新增版本，并先下架同标识的旧版本。目录修改不影响现有会员；续订新版本后，当前权益立即按新版本更新。</p>
        <button class="yanhuo-primary" type="submit" :disabled="busy"><Plus :size="16" />{{ busy ? '正在保存...' : '保存套餐' }}</button>
      </form>
    </div>
  </section>
</template>
