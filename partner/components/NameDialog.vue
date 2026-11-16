<script setup>
import { ref, watch } from 'vue'
import { ArrowRight } from 'lucide-vue-next'
import { partnerApi } from '../../src/services/partnerApi.js'
import { displayNameError } from '../../src/domain/partnerOps.js'
import { partner } from '../partnerStore.js'
import PartnerPanel from './PartnerPanel.vue'

/**
 * 团队称呼：第一次进服务商后台时必须填（required，不能关掉），之后在右上角点名字可以改。
 * 先填好账号名，直接确定也行。称呼会出现在激活码生成人、分配记录操作人、跟进记录里，团队内不能重名。
 */
const props = defineProps({ open: { type: Boolean, default: false }, required: { type: Boolean, default: false } })
const emit = defineEmits(['close'])
const name = ref('')
const error = ref('')
const busy = ref(false)

watch(() => props.open, open => {
  if (!open) return
  name.value = partner.overview?.displayName || partner.overview?.userName || ''
  error.value = ''
}, { immediate: true })

function close() { if (!props.required) emit('close') }

async function save() {
  error.value = displayNameError(name.value)
  if (error.value) return
  busy.value = true
  try {
    partner.overview = await partnerApi.setDisplayName(name.value.trim())
    emit('close')
  } catch (failure) {
    error.value = failure.message || '保存失败，请稍后再试。'
  } finally { busy.value = false }
}
</script>

<template>
  <PartnerPanel modal :open="open" :busy="busy || required" :title="required ? '在团队里怎么称呼你？' : '修改团队称呼'" :eyebrow="partner.overview?.partner.name || ''" @close="close">
    <form class="partner-name-form" @submit.prevent="save">
      <label class="partner-field">称呼<input v-model="name" maxlength="20" placeholder="例如：小周" autofocus :disabled="busy" /><small>1 到 20 个字。团队成员会在操作记录里看到这个名字，不影响你在一方志的账号名。</small></label>
      <p v-if="error" class="mg-inline-error" role="alert">{{ error }}</p>
    </form>
    <template #actions>
      <button v-if="!required" class="mg-button" type="button" :disabled="busy" @click="close">取消</button>
      <button class="mg-button mg-primary" type="button" :disabled="busy" @click="save">{{ busy ? '正在保存…' : required ? '进入服务商后台' : '保存' }}<ArrowRight v-if="required && !busy" :size="15" /></button>
    </template>
  </PartnerPanel>
</template>
