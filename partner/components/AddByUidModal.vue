<script setup>
import { computed, reactive, watch } from 'vue'
import { Search } from 'lucide-vue-next'
import { partnerApi, validUid } from '../../src/services/partnerApi.js'
import { lookupVerdict } from '../../src/domain/partnerOps.js'
import PartnerPanel from './PartnerPanel.vue'

/**
 * 按 UID 添加名下商户：先查询确认对象（商户名、打码的姓名、现在的归属），能添加时再点确认。
 * 添加成功后通过 added 把结果交回页面。
 */
const props = defineProps({ open: { type: Boolean, default: false } })
const emit = defineEmits(['close', 'added'])

const form = reactive({ uid: '', found: null, error: '', busy: false })
const verdict = computed(() => lookupVerdict(form.found))
watch(() => props.open, open => { if (open) Object.assign(form, { uid: '', found: null, error: '', busy: false }) })
watch(() => form.uid, () => { form.found = null; form.error = '' })

async function lookup() {
  form.error = validUid(form.uid) ? '' : 'UID 是 8 位数字，请让对方在一方志「套餐与权益」页查看。'
  if (form.error) return
  form.busy = true
  try { form.found = await partnerApi.lookup(form.uid.trim()) }
  catch (failure) { form.error = failure.message || '查询失败，请稍后再试。' }
  finally { form.busy = false }
}

async function add() {
  form.busy = true
  try {
    const result = await partnerApi.addTenant(form.uid.trim())
    emit('added', result)
  } catch (failure) {
    form.error = failure.message || '添加失败，请稍后再试。'
  } finally { form.busy = false }
}
</script>

<template>
  <PartnerPanel modal :open="open" :busy="form.busy" title="添加商户" @close="emit('close')">
    <p class="mg-muted">输入商户任一账号的 UID，先查询确认是哪家商户。</p>
    <form class="partner-inline-field" @submit.prevent="lookup">
      <label class="partner-field">UID<input v-model="form.uid" inputmode="numeric" maxlength="8" placeholder="8 位数字" autofocus :disabled="form.busy" /></label>
      <button class="mg-button" type="submit" :disabled="form.busy"><Search :size="15" />查询</button>
    </form>
    <div v-if="form.found" class="partner-found" :class="verdict.can ? 'ok' : 'blocked'" role="status">
      <strong>{{ form.found.tenantName || '未命名商户' }}</strong>
      <span class="mg-muted">联系人 {{ form.found.userName || '—' }} · UID {{ form.found.uid }}</span>
      <p>{{ verdict.text }}</p>
    </div>
    <p v-if="form.error" class="mg-inline-error" role="alert">{{ form.error }}</p>
    <template #actions>
      <button class="mg-button" type="button" :disabled="form.busy" @click="emit('close')">取消</button>
      <button class="mg-button mg-primary" type="button" :disabled="form.busy || !verdict.can" @click="add">{{ form.busy && form.found ? '正在添加…' : '添加到名下' }}</button>
    </template>
  </PartnerPanel>
</template>
