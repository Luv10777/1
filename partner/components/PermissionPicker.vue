<script setup>
import { permissionItems } from '../../src/domain/partnerOps.js'
import { isOwner } from '../partnerStore.js'

/**
 * 勾选成员权限，勾了「分配烟火」时填每月上限（空着表示不限）。只有负责人能动的几项，成员看得到但改不了。
 * v-model 是 { tenants, codes, distribute, finance, team }，cap 是每月上限的输入框文字。
 */
const permissions = defineModel({ type: Object, required: true })
const cap = defineModel('cap', { type: String, default: '' })
defineProps({ disabled: { type: Boolean, default: false } })
</script>

<template>
  <fieldset class="partner-perms" :disabled="disabled">
    <legend class="partner-field-label">权限</legend>
    <label v-for="item in permissionItems" :key="item.key" :class="{ locked: item.ownerOnly && !isOwner() }">
      <input v-model="permissions[item.key]" type="checkbox" :disabled="item.ownerOnly && !isOwner()" />
      <span><b>{{ item.label }}</b><small>{{ item.hint }}{{ item.ownerOnly && !isOwner() ? '（只有负责人能设）' : '' }}</small></span>
    </label>
    <label v-if="permissions.distribute" class="partner-field">每月最多分出（烟火，空着表示不限）<input v-model="cap" type="number" min="1" step="1" inputmode="numeric" placeholder="例如 5000" :disabled="!isOwner()" /></label>
  </fieldset>
</template>
