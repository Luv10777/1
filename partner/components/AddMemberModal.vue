<script setup>
import { reactive, watch } from 'vue'
import { partnerApi } from '../../src/services/partnerApi.js'
import { phoneError } from '../../src/utils/authValidation.js'
import { defaultPermissions, grantError } from '../../src/domain/partnerOps.js'
import { partner } from '../partnerStore.js'
import PartnerPanel from './PartnerPanel.vue'
import PermissionPicker from './PermissionPicker.vue'

/**
 * 添加成员：按手机号在团队账户里建一个员工账号（已是团队账户员工的直接加进来），勾选权限。
 * 注册了别的商户的手机号加不进来，要换一个号。
 */
const props = defineProps({ open: { type: Boolean, default: false } })
const emit = defineEmits(['close', 'added'])
const form = reactive({ phone: '', name: '', permissions: defaultPermissions(), cap: '', error: '', busy: false })
watch(() => props.open, open => { if (open) Object.assign(form, { phone: '', name: '', permissions: defaultPermissions(), cap: '', error: '', busy: false }) })

function check() {
  const cap = form.permissions.distribute && String(form.cap).trim() ? Number(form.cap) : null
  if (phoneError(form.phone)) return { error: phoneError(form.phone) }
  if (!form.name.trim()) return { error: '请填写姓名。' }
  if (cap != null && (!Number.isInteger(cap) || cap < 1)) return { error: '每月上限请填正整数，或者空着表示不限。' }
  return { error: grantError(partner.overview, null, form.permissions, null, cap), cap }
}

async function add() {
  const { error, cap } = check()
  form.error = error
  if (error) return
  form.busy = true
  try {
    const team = await partnerApi.addMember({ phone: form.phone.trim(), name: form.name.trim(), permissions: { ...form.permissions }, monthlyCap: cap })
    emit('added', team, form.name.trim())
  } catch (failure) {
    form.error = failure.message || '添加失败，请稍后再试。'
  } finally { form.busy = false }
}
</script>

<template>
  <PartnerPanel modal :open="open" :busy="form.busy" title="添加成员" @close="emit('close')">
    <p class="mg-muted">成员会成为团队账户的员工：用这个手机号登录一方志，创作时用团队烟火，右上角能进服务商后台。手机号已经注册了别的商户的，加不进来，请换一个号。</p>
    <label class="partner-field">手机号<input v-model="form.phone" inputmode="tel" maxlength="11" placeholder="11 位手机号" autofocus :disabled="form.busy" /></label>
    <label class="partner-field">姓名<input v-model="form.name" maxlength="20" placeholder="一方志账号名，例如：周晓" :disabled="form.busy" /><small>他第一次进服务商后台时会再确认自己在团队里的称呼。</small></label>
    <PermissionPicker v-model="form.permissions" v-model:cap="form.cap" :disabled="form.busy" />
    <p v-if="form.error" class="mg-inline-error" role="alert">{{ form.error }}</p>
    <template #actions>
      <button class="mg-button" type="button" :disabled="form.busy" @click="emit('close')">取消</button>
      <button class="mg-button mg-primary" type="button" :disabled="form.busy" @click="add">{{ form.busy ? '正在添加…' : '添加' }}</button>
    </template>
  </PartnerPanel>
</template>
