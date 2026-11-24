<script setup>
import { ref, watch } from 'vue'
import { Check, PencilLine } from 'lucide-vue-next'

/**
 * 后台的备注名（像微信给好友起备注）：管理后台给账号、服务商后台给名下商户。
 * save 是 async (remark) => void，传空字符串表示删掉备注；失败时抛错，错误信息显示在输入框下面。
 */
const props = defineProps({
  value: { type: String, default: '' },
  save: { type: Function, required: true },
  disabled: { type: Boolean, default: false },
  placeholder: { type: String, default: '例如：杭州西湖店、老客户' },
})
const editing = ref(false)
const draft = ref('')
const busy = ref(false)
const error = ref('')
const done = ref(false)
watch(() => props.value, () => { editing.value = false; error.value = '' })

function start() { draft.value = props.value || ''; error.value = ''; done.value = false; editing.value = true }
async function submit() {
  busy.value = true
  error.value = ''
  try {
    await props.save(draft.value.trim())
    editing.value = false
    done.value = true
  } catch (failure) { error.value = failure.message || '备注保存失败，请稍后再试。' }
  finally { busy.value = false }
}
</script>

<template>
  <form v-if="editing" class="mg-billing-row" @submit.prevent="submit">
    <label class="mg-amount"><span class="sr-only">备注名</span><input v-model="draft" maxlength="30" :placeholder="placeholder" :disabled="busy" autofocus /></label>
    <button class="mg-button mg-primary" type="submit" :disabled="busy">{{ busy ? '正在保存…' : '保存' }}</button>
    <button class="mg-button" type="button" :disabled="busy" @click="editing = false">取消</button>
  </form>
  <div v-else class="mg-billing-row">
    <span>{{ value || '未设置' }}</span>
    <button class="mg-text-button" type="button" :disabled="disabled" @click="start"><PencilLine :size="13" />{{ value ? '修改' : '设置备注名' }}</button>
    <span v-if="done" class="mg-billing-done" role="status"><Check :size="14" />已保存</span>
  </div>
  <p v-if="error" class="mg-inline-error" role="alert">{{ error }}</p>
  <p v-if="editing" class="mg-muted">最多 30 字，只在后台显示，对方看不到。清空后保存即删除备注。</p>
</template>
