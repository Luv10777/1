import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { commerceApi } from '../services/commerceApi'

export function useCommercePayment(token) {
  const order = ref(null)
  const loading = ref(false)
  const error = ref('')
  const paymentLoading = ref(false)
  const paymentError = ref('')
  const pending = computed(() => order.value?.paymentMethod === 'ALIPAY' && order.value?.paymentState === 'PENDING')
  let generation = 0
  let refreshing = false
  let timer

  async function refreshPayment() {
    if (!pending.value || refreshing || loading.value) return
    refreshing = true
    const current = generation
    const lookup = token.value
    try {
      const result = await commerceApi.refreshOrder(lookup)
      if (current === generation) { order.value = result; paymentError.value = '' }
    } catch (failure) {
      if (current === generation) paymentError.value = failure.message || '支付状态暂时无法核实，请重新查询。'
    } finally { refreshing = false }
  }

  async function loadOrder() {
    const current = ++generation
    loading.value = true
    order.value = null
    error.value = ''
    paymentError.value = ''
    try {
      const result = await commerceApi.publicOrder(token.value)
      if (current === generation) order.value = result
    } catch (failure) {
      if (current === generation) error.value = failure.message || '无法读取订单状态。'
    } finally { if (current === generation) loading.value = false }
    if (current === generation) await refreshPayment()
  }

  async function openAlipay() {
    if (!pending.value || paymentLoading.value) return
    const tab = window.open('about:blank', '_blank')
    if (!tab) { paymentError.value = '浏览器拦截了支付窗口，请允许弹出窗口后重试。'; return }
    tab.opener = null
    const current = generation
    paymentLoading.value = true
    paymentError.value = ''
    try {
      const url = await commerceApi.alipay(token.value)
      if (current !== generation) { tab.close(); return }
      tab.location.href = url
    } catch (failure) {
      tab.close()
      if (current === generation) paymentError.value = failure.message || '支付宝收银台暂时无法打开。'
    } finally { paymentLoading.value = false }
  }

  function onVisible() { if (document.visibilityState === 'visible') void refreshPayment() }
  onMounted(() => {
    timer = window.setInterval(() => { if (document.visibilityState === 'visible') void refreshPayment() }, 5000)
    window.addEventListener('focus', refreshPayment)
    document.addEventListener('visibilitychange', onVisible)
  })
  onUnmounted(() => {
    generation++
    window.clearInterval(timer)
    window.removeEventListener('focus', refreshPayment)
    document.removeEventListener('visibilitychange', onVisible)
  })
  watch(token, loadOrder, { immediate: true })

  return { order, loading, error, paymentLoading, paymentError, pending, loadOrder, refreshPayment, openAlipay }
}
