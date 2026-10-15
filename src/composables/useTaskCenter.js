import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { taskUsageApi } from '../services/taskUsage'
import { taskLogQuery } from '../domain/taskUsage'
import { refreshBilling } from '../stores/billing'

export function useTaskCenter() {
  const filters = reactive({ from: '', until: '', module: '', model: '', status: '' })
  const result = ref(null), loading = ref(false), error = ref('')
  const expandedId = ref(''), detail = ref(null), detailLoading = ref(false), detailError = ref('')
  let applied = { page: 0, size: 20 }, sequence = 0, detailSequence = 0, disposed = false, timer

  async function loadDetail(id = expandedId.value) {
    const round = ++detailSequence
    detailLoading.value = true; detailError.value = ''
    try {
      const data = await taskUsageApi.detail(id)
      if (!disposed && round === detailSequence) detail.value = data
    } catch (failure) {
      if (!disposed && round === detailSequence) { detail.value = null; detailError.value = failure.message || '明细暂时无法读取' }
    } finally { if (!disposed && round === detailSequence) detailLoading.value = false }
  }
  async function toggleDetail(id) {
    detailSequence++; detail.value = null; detailError.value = ''
    expandedId.value = expandedId.value === id ? '' : id
    if (expandedId.value) await loadDetail(id)
  }
  async function load(page = applied.page) {
    clearTimeout(timer)
    const round = ++sequence, query = { ...applied, page }
    loading.value = true; error.value = ''
    try {
      const data = await taskUsageApi.logs(query)
      if (disposed || round !== sequence) return
      if (page > 0 && data.records.totalPages <= page) return load(Math.max(0, data.records.totalPages - 1))
      result.value = data; applied = query
      refreshBilling()
      if (expandedId.value) {
        if (data.records.items.some(item => item.id === expandedId.value)) loadDetail(expandedId.value)
        else { expandedId.value = ''; detailSequence++; detail.value = null }
      }
    } catch (failure) {
      if (!disposed && round === sequence) { result.value = null; error.value = failure.message || '任务日志暂时无法读取' }
    } finally {
      if (!disposed && round === sequence) {
        loading.value = false
        if (result.value?.summary.heldCredits > 0 || result.value?.records.items.some(row => row.status === 'RUNNING')) timer = setTimeout(() => load(), 10000)
      }
    }
  }
  function search() {
    try { applied = taskLogQuery(filters) }
    catch (failure) { error.value = failure.message; return }
    expandedId.value = ''; detailSequence++; detail.value = null
    return load(0)
  }
  function reset() {
    Object.assign(filters, { from: '', until: '', module: '', model: '', status: '' })
    return search()
  }
  onMounted(() => load())
  onBeforeUnmount(() => { disposed = true; sequence++; detailSequence++; clearTimeout(timer) })
  return { filters, result, loading, error, expandedId, detail, detailLoading, detailError, load, search, reset, toggleDetail, loadDetail }
}
