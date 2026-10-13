import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { auth } from '../stores/auth'

export function useBusinessSubmission(blankForm, submitRequest) {
  const form = reactive(blankForm())
  const editor = ref(null)
  const submitting = ref(false)
  const receipt = ref(null)
  const error = ref('')
  const baseline = ref(JSON.stringify(form))
  const dirty = computed(() => JSON.stringify(form) !== baseline.value)
  const account = computed(() => `${auth.tenantId}:${auth.user?.id}`)
  let revision = 0

  function reset() {
    Object.assign(form, blankForm())
    baseline.value = JSON.stringify(form)
    receipt.value = null
    error.value = ''
    submitting.value = false
  }

  function clearFieldError(event) {
    event.target.setCustomValidity?.('')
  }

  function validate() {
    for (const field of editor.value?.querySelectorAll('input[required], textarea[required]') || []) {
      field.setCustomValidity(field.type !== 'checkbox' && !field.value.trim() ? '请填写此项。' : '')
    }
    return Boolean(editor.value?.reportValidity())
  }

  async function submit() {
    if (submitting.value || receipt.value || !validate()) return
    error.value = ''
    submitting.value = true
    const attempt = revision
    const values = Object.fromEntries(Object.entries(form).map(([key, value]) => [key, typeof value === 'string' ? value.trim() : value]))
    try {
      const result = await submitRequest(values)
      if (attempt === revision) {
        receipt.value = result
        baseline.value = JSON.stringify(form)
      }
    } catch (failure) {
      if (attempt === revision) error.value = failure.message || '提交未完成，请稍后重试。'
    } finally {
      if (attempt === revision) submitting.value = false
    }
  }

  const beforeUnload = event => {
    if (!dirty.value && !submitting.value) return
    event.preventDefault()
    event.returnValue = ''
  }
  watch(account, () => { revision++; reset() })
  watch(form, () => { error.value = '' }, { flush: 'sync' })
  onMounted(() => window.addEventListener('beforeunload', beforeUnload))
  onBeforeUnmount(() => { revision++; window.removeEventListener('beforeunload', beforeUnload) })
  onBeforeRouteLeave(() => !submitting.value && (!dirty.value || window.confirm('当前内容尚未提交，离开后将丢失。确定离开吗？')))

  return { form, editor, submitting, receipt, error, reset, validate, submit, clearFieldError }
}
