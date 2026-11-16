<script setup>
import { reactive, ref, onMounted } from 'vue'
import { partnerApi } from '../../src/services/partnerApi.js'
import { followChannels, merchantStages, localDateTime, instantFromLocal } from '../../src/domain/backoffice.js'
import { managementDate } from '../../src/domain/platformManagement.js'
import '../../src/backoffice.css'
const props = defineProps({ tenantId: { type: Number, required: true } })
const emit = defineEmits(['updated'])
const crm = ref(null)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const page = ref(0)
const profile = reactive({ contactName: '', contactPhone: '', industry: '', region: '', stage: 'ACTIVE', next: '', note: '' })
const follow = reactive({ requestKey: crypto.randomUUID(), channel: 'WECHAT', content: '', stage: 'ACTIVE', next: '' })
function fill(result) {
  crm.value = result
  Object.assign(profile, result.profile, { next: localDateTime(result.profile.nextFollowUpAt) })
  follow.stage = result.profile.stage
  follow.next = localDateTime(result.profile.nextFollowUpAt)
}
async function load() {
  loading.value = true; error.value = ''
  try { fill(await partnerApi.crm(props.tenantId, { page: page.value, size: 20 })) }
  catch (failure) { error.value = failure.message || '商户档案读取失败' }
  finally { loading.value = false }
}
async function save() {
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const saved = await partnerApi.saveProfile(props.tenantId, { contactName: profile.contactName, contactPhone: profile.contactPhone, industry: profile.industry, region: profile.region, stage: profile.stage, nextFollowUpAt: instantFromLocal(profile.next), note: profile.note, version: crm.value.profile.version })
    fill({ ...crm.value, profile: saved }); notice.value = '商户档案已保存'; emit('updated')
  } catch (failure) { error.value = failure.message || '档案保存失败' }
  finally { busy.value = false }
}
async function record() {
  busy.value = true; error.value = ''; notice.value = ''
  try {
    fill(await partnerApi.followUp(props.tenantId, { requestKey: follow.requestKey, channel: follow.channel, content: follow.content, stage: follow.stage, nextFollowUpAt: instantFromLocal(follow.next), version: crm.value.profile.version }))
    page.value = 0; follow.content = ''; follow.requestKey = crypto.randomUUID(); notice.value = '跟进记录已保存，下次跟进安排已更新'; emit('updated')
  } catch (failure) { error.value = failure.message || '跟进记录保存失败' }
  finally { busy.value = false }
}
function move(next) { page.value = next; load() }
onMounted(load)
</script>
<template>
  <section class="mg-detail-section" aria-labelledby="crm-title">
    <h3 id="crm-title">商户档案与跟进</h3>
    <p v-if="loading" class="bo-muted" role="status">正在读取商户档案…</p>
    <div v-if="error" class="mg-feedback mg-error" role="alert">{{ error }} <button type="button" class="mg-text-button" :disabled="busy || loading" @click="load">刷新档案</button></div>
    <p v-if="notice" class="mg-feedback success" role="status">{{ notice }}</p>
    <template v-if="crm">
      <form class="bo-form" @submit.prevent="save">
        <label class="bo-field">联系人<input v-model="profile.contactName" maxlength="80" :disabled="busy" /></label>
        <label class="bo-field">联系电话<input v-model="profile.contactPhone" maxlength="40" :disabled="busy" /></label>
        <label class="bo-field">行业<input v-model="profile.industry" maxlength="80" :disabled="busy" /></label>
        <label class="bo-field">所在地区<input v-model="profile.region" maxlength="120" :disabled="busy" /></label>
        <label class="bo-field">合作状态<select v-model="profile.stage" :disabled="busy"><option v-for="(label, key) in merchantStages" :key="key" :value="key">{{ label }}</option></select></label>
        <label class="bo-field">下次跟进时间<input v-model="profile.next" type="datetime-local" :disabled="busy" /></label>
        <label class="bo-field bo-wide">商户备注<textarea v-model="profile.note" rows="3" maxlength="2000" :disabled="busy" placeholder="经营情况、需求及需要持续关注的事项" /></label>
        <div class="bo-actions bo-wide"><span class="bo-muted">{{ crm.profile.updatedAt ? `更新于 ${managementDate(crm.profile.updatedAt)}` : '尚未建立档案' }}</span><button class="mg-button" type="submit" :disabled="busy || loading">保存档案</button></div>
      </form>
      <h4>记录本次跟进</h4>
      <form class="bo-form" @submit.prevent="record">
        <label class="bo-field">沟通方式<select v-model="follow.channel" :disabled="busy"><option v-for="(label, key) in followChannels" :key="key" :value="key">{{ label }}</option></select></label>
        <label class="bo-field">本次沟通后的合作状态<select v-model="follow.stage" :disabled="busy"><option v-for="(label, key) in merchantStages" :key="key" :value="key">{{ label }}</option></select></label>
        <label class="bo-field bo-wide">沟通内容与处理结果<textarea v-model="follow.content" required rows="3" maxlength="2000" :disabled="busy" placeholder="记录商户反馈、已完成的处理及双方约定" /></label>
        <label class="bo-field">预约下次跟进<input v-model="follow.next" type="datetime-local" :disabled="busy" /></label>
        <div class="bo-actions"><button class="mg-button mg-primary" type="submit" :disabled="busy || loading">{{ busy ? '正在保存…' : '保存跟进记录' }}</button></div>
      </form>
      <ul class="bo-records" aria-label="历史跟进记录"><li v-for="item in crm.followUps.items" :key="item.id"><strong>{{ followChannels[item.channel] }} · {{ item.actorName }}</strong><span class="bo-muted"> · {{ managementDate(item.createdAt) }}</span><p>{{ item.content }}</p><span class="bo-muted">{{ merchantStages[item.stage] }} · {{ item.nextFollowUpAt ? `下次跟进 ${managementDate(item.nextFollowUpAt)}` : '本次未安排后续跟进' }}</span></li></ul>
      <p v-if="!crm.followUps.total" class="bo-muted">尚无跟进记录</p>
      <div v-if="crm.followUps.total > 20" class="bo-actions"><button class="mg-button" :disabled="!page || busy || loading" @click="move(page - 1)">上一页</button><span class="bo-muted">{{ page + 1 }} / {{ Math.ceil(crm.followUps.total / 20) }}</span><button class="mg-button" :disabled="(page + 1) * 20 >= crm.followUps.total || busy || loading" @click="move(page + 1)">下一页</button></div>
    </template>
  </section>
</template>
