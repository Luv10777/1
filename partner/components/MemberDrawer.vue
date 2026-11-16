<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { BarChart3, ShieldCheck, UserMinus } from 'lucide-vue-next'
import { partnerApi } from '../../src/services/partnerApi.js'
import { managementDate } from '../../src/domain/platformManagement.js'
import { displayNameError, grantError, number, partnerRoles, permissionText } from '../../src/domain/partnerOps.js'
import { auth } from '../../src/stores/auth.js'
import { can, loadPartner, partner } from '../partnerStore.js'
import PartnerPanel from './PartnerPanel.vue'
import ConfirmBox from './ConfirmBox.vue'
import PermissionPicker from './PermissionPicker.vue'

/**
 * 团队成员详情：他做了什么（加的商户、激活码、本月分配）。有团队管理权限的人可以改他的权限和称呼、把他移出团队；
 * 负责人只能由平台调整，自己的权限不能自己改（称呼在右上角改）。
 */
const props = defineProps({ member: { type: Object, default: null } })
const emit = defineEmits(['close', 'removed', 'saved'])

const busy = ref(false)
const edit = reactive({ permissions: {}, cap: '', displayName: '', error: '' })
const removal = reactive({ confirming: false, error: '' })
const editable = computed(() => Boolean(props.member) && can('team') && props.member.role !== 'OWNER' && props.member.userId !== auth.user?.id)
const codes = computed(() => props.member?.codes || { generated: 0, redeemed: 0, unused: 0, voided: 0, renewals: 0 })
const label = computed(() => props.member?.displayName || props.member?.name || String(props.member?.uid || ''))

watch(() => props.member?.userId, () => {
  const m = props.member
  if (!m) return
  Object.assign(edit, { permissions: { ...m.permissions }, cap: m.monthlyCap ? String(m.monthlyCap) : '', displayName: m.displayName || '', error: '' })
  Object.assign(removal, { confirming: false, error: '' })
}, { immediate: true })

async function save() {
  const m = props.member
  const cap = edit.permissions.distribute && String(edit.cap).trim() ? Number(edit.cap) : null
  edit.error = cap != null && (!Number.isInteger(cap) || cap < 1) ? '每月上限请填正整数，或者空着表示不限。'
    : edit.displayName.trim() && edit.displayName.trim() !== (m.displayName || '') ? displayNameError(edit.displayName) : ''
  edit.error ||= grantError(partner.overview, m.permissions, edit.permissions, m.monthlyCap ?? null, cap)
  if (edit.error) return
  busy.value = true
  try {
    const nameChanged = edit.displayName.trim() && edit.displayName.trim() !== (m.displayName || '')
    partner.team = await partnerApi.updateMember(m.userId, { permissions: { ...edit.permissions }, monthlyCap: cap, displayName: nameChanged ? edit.displayName.trim() : null })
    emit('saved', `已保存「${edit.displayName.trim() || label.value}」的设置`)
  } catch (failure) {
    edit.error = failure.message || '保存失败，请稍后再试。'
  } finally { busy.value = false }
}

async function remove() {
  busy.value = true
  // 团队刷新后这个成员就不在列表里了，props.member 会变空，先记下来。
  const member = props.member
  try {
    partner.team = await partnerApi.removeMember(member.userId)
    emit('removed', member)
    await loadPartner(true)
  } catch (failure) {
    removal.error = failure.message || '移除失败，请稍后再试。'
    removal.confirming = false
  } finally { busy.value = false }
}
</script>

<template>
  <PartnerPanel :open="Boolean(member)" :busy="busy" :title="label || '成员'" :eyebrow="member ? `UID ${member.uid || '—'}` : ''" @close="emit('close')">
    <template v-if="member" #summary>
      <span class="partner-pill" :class="{ bd: member.role === 'OWNER' }">{{ partnerRoles[member.role] || member.role }}</span>
      <span v-if="member.name && member.name !== member.displayName">账号名 {{ member.name }}</span>
      <span>加入于 {{ managementDate(member.createdAt) }}</span>
      <span v-if="member.phone">手机 {{ member.phone }}</span>
    </template>
    <template v-if="member">
      <section class="mg-detail-section">
        <h3><BarChart3 :size="17" />做了什么</h3>
        <div class="partner-stats">
          <div><span>按 UID 加的商户</span><strong>{{ number(member.tenantsAdded) }}</strong></div>
          <div><span>本月分出烟火</span><strong>{{ number(member.distributedThisMonth) }}</strong></div>
          <div><span>生成激活码</span><strong>{{ number(codes.generated) }}</strong></div>
          <div><span>已兑换</span><strong>{{ number(codes.redeemed) }}</strong></div>
          <div><span>未使用</span><strong>{{ number(codes.unused) }}</strong></div>
          <div><span>直接续期</span><strong>{{ number(codes.renewals) }}</strong></div>
        </div>
        <p class="mg-muted">激活码数含直接续期自动生成的码。名下商户整个团队共有，不分到个人。</p>
      </section>

      <section class="mg-detail-section" aria-labelledby="perm-title">
        <h3 id="perm-title"><ShieldCheck :size="17" />权限</h3>
        <template v-if="editable">
          <label class="partner-field">团队称呼<input v-model="edit.displayName" maxlength="20" :placeholder="member.displayName ? '' : '他还没填，可以先帮他填'" :disabled="busy" /></label>
          <PermissionPicker v-model="edit.permissions" v-model:cap="edit.cap" :disabled="busy" />
          <div class="partner-section-actions"><button class="mg-button mg-primary" type="button" :disabled="busy" @click="save">保存</button></div>
          <p v-if="edit.error" class="mg-inline-error" role="alert">{{ edit.error }}</p>
        </template>
        <template v-else>
          <p>{{ permissionText(member) }}<template v-if="member.monthlyCap"> · 每月最多分出 {{ number(member.monthlyCap) }} 烟火</template></p>
          <p class="mg-muted">{{ member.role === 'OWNER' ? '负责人拥有全部权限，由平台开通。' : member.userId === auth.user?.id ? '自己的权限不能自己改；称呼在右上角点名字修改。' : '有「团队管理」权限的人才能改。' }}</p>
        </template>
      </section>

      <section v-if="editable" class="mg-detail-section" aria-labelledby="remove-title">
        <h3 id="remove-title"><UserMinus :size="17" />移出团队</h3>
        <p class="mg-muted">移出后他的一方志账号会停用：不能再登录、不能再用团队烟火，手机号释放。他生成的激活码照常可用，加过的商户仍在名下。</p>
        <ConfirmBox v-if="removal.confirming" danger :busy="busy" confirm-text="确认移除" @confirm="remove" @cancel="removal.confirming = false">
          <p>把「{{ label }}」移出团队，并停用他的一方志账号。</p>
        </ConfirmBox>
        <div v-else class="partner-section-actions"><button class="mg-button mg-danger" type="button" :disabled="busy" @click="removal.confirming = true">移出团队</button></div>
        <p v-if="removal.error" class="mg-inline-error" role="alert">{{ removal.error }}</p>
      </section>
    </template>
    <template #actions><button class="mg-button" type="button" :disabled="busy" @click="emit('close')">关闭</button></template>
  </PartnerPanel>
</template>
