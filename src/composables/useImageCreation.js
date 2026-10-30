import { computed, reactive, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { imageApi, uploadImageReference, downloadImage } from '../services/imageCreation'
import { IMAGE_QUALITIES, POSTER_PURPOSE_GROUPS, imageDimensions, isImageActive, validateImageFile, supportsImageSize } from '../domain/imageCreation'
import { readUser } from '../utils/tokenStore'
import { billing, refreshBilling, requireAccount } from '../stores/billing'
import { auth } from '../stores/auth'

export function useImageCreation(workflow) {
  const route = useRoute()
  const router = useRouter()
  const product = computed(() => workflow === 'PRODUCT_SET')
  const brief = ref(typeof route.query.prompt === 'string' ? route.query.prompt : '')
  const ratio = ref(product.value ? '1:1' : '3:4')
  const quality = ref('2K')
  const count = ref(product.value ? 3 : 1)
  const platform = ref(product.value ? 'LOCAL' : 'LOCAL')
  const imageType = ref(product.value ? 'PRODUCT_MAIN' : 'POSTER')
  const purpose = ref(product.value ? '美团 / 大众点评商品展示' : '朋友圈活动宣传')
  const style = ref('帮我搭配')
  const files = ref([])
  const busy = ref(false)
  const error = ref('')
  const capabilities = ref(null)
  const capabilityState = ref('checking')
  const capabilityError = ref('')
  const current = ref(null)
  const thread = ref([])
  const history = ref([])
  const historyPage = ref(0)
  const historyMore = ref(false)
  const historyOpen = ref(false)
  const revision = ref('')
  const textEdit = ref(null)
  const editHeadline = ref('')
  const editCaption = ref('')
  const pending = ref(null)
  const lastPrompt = ref('')
  const optimistic = ref(false)
  const optimisticBrief = ref('')
  let pollTimer
  let alive = true
  let refreshSequence = 0
  let pollError = false
  const previewTracking = new Set()
  const previewReports = new Set()
  const pendingPreviewReports = new Map()
  const failedPreviews = new Set()
  const previewKey = item => `${item.id}:${item.generation}`
  const owner = readUser()
  const pendingKey = 'image-pending:' + (owner?.userId || owner?.id || 'session') + ':' + workflow
  const dimensions = computed(() => imageDimensions(quality.value, ratio.value))
  // 单价以服务端账单摘要为准；未读到时为 null，页面不显示具体数字。
  const imageCredits = computed(() => billing.summary?.imageCredits ?? null)
  const estimatedCredits = computed(() => imageCredits.value == null ? null : (product.value ? Number(count.value) : 1) * imageCredits.value)
  const configured = computed(() => capabilityState.value === 'ready' && capabilities.value?.configured === true)
  const requiresLogin = computed(() => !auth.isAuthenticated)
  const supportsRatio = value => {
    const ratios = capabilities.value?.qualityRatios
    if (!ratios) return true
    return Object.values(ratios).some(values => values?.includes(value))
  }
  const supportsQuality = value => supportsImageSize(capabilities.value, value, ratio.value)
  function syncHighestQuality() {
    if (supportsQuality(quality.value)) return
    const supported = IMAGE_QUALITIES.filter(tier => supportsQuality(tier))
    if (supported.length) quality.value = supported.at(-1)
  }
  const active = computed(() => isImageActive(current.value?.status))
  const latestItem = item => thread.value.flatMap(turn => turn.items || []).find(latest => latest.id === item?.id) || item
  const canUseAsset = item => !latestItem(item)?.providerImageUrl || !!latestItem(item)?.persisted
  async function reportImageVisible(item) {
    const turn = thread.value.find(entry => entry.items?.some(image => image.id === item.id))
    const key = previewKey(item)
    if (!alive || !turn || !previewTracking.has(turn.id) || item.previewedAt || previewReports.has(key)) return
    pendingPreviewReports.set(key, item)
    previewReports.add(key)
    try {
      await imageApi.previewLoaded(turn.id, item)
      pendingPreviewReports.delete(key)
    }
    catch { previewReports.delete(key) }
  }
  function imageLoadFailed(item) {
    if (!item.providerImageUrl || item.persisted) return
    failedPreviews.add(previewKey(item))
    latestItem(item).url = null
  }
  const locked = computed(() => busy.value || !!pending.value)
  const workspaceTab = ref('inspiration')
  const posterPlatform = purposeValue => {
    if (purposeValue.includes('小红书')) return 'XIAOHONGSHU'
    if (purposeValue.includes('抖音')) return purposeValue.includes('团购') ? 'DOUYIN_GROUP' : 'DOUYIN'
    if (purposeValue.includes('视频号') || purposeValue.includes('朋友圈') || purposeValue.includes('微信群')) return 'WECHAT'
    if (purposeValue.includes('美团') || purposeValue.includes('点评')) return 'MEITUAN'
    if (purposeValue.includes('外卖')) return 'ELEME'
    if (purposeValue.includes('门店')) return 'OFFLINE'
    return 'LOCAL'
  }
  function persistPending(value) {
    pending.value = value
    if (value) sessionStorage.setItem(pendingKey, JSON.stringify(value))
    else sessionStorage.removeItem(pendingKey)
  }
  async function refreshHistory(reset = true) {
    if (!auth.isAuthenticated) return
    try {
      const page = reset ? 0 : historyPage.value + 1
      const result = await imageApi.history(workflow, page)
      if (!alive) return
      history.value = reset ? result.items : [...history.value, ...result.items]
      historyPage.value = page; historyMore.value = page + 1 < result.totalPages
    } catch (e) { if (alive) error.value = e.message }
  }
  async function load(id) {
    if (!auth.isAuthenticated) return
    clearTimeout(pollTimer)
    const sequence = ++refreshSequence
    try {
      const versions = await imageApi.thread(id)
      if (!alive || sequence !== refreshSequence) return
      const result = versions.at(-1)
      if (!result) throw new Error('这条创作记录不存在')
      if (result.workflow !== workflow) throw new Error('这条记录属于另一个图片工作区')
      for (const turn of versions) {
        if (isImageActive(turn.status)) previewTracking.add(turn.id)
        for (const item of turn.items || []) {
          if (item.providerImageUrl && !item.persisted && failedPreviews.has(previewKey(item))) item.url = null
        }
      }
      if (current.value?.id !== result.id) workspaceTab.value = 'result'
      thread.value = versions
      current.value = result
      // Retry a lost visibility receipt even when the image does not load again.
      for (const item of pendingPreviewReports.values()) reportImageVisible(item)
      refreshBilling()
      if (pollError) { error.value = ''; pollError = false }
      const waitingForStorage = versions.some(turn => turn.items?.some(item => item.providerImageUrl && !item.persisted && !item.downloadFailed))
      if (isImageActive(result.status) || waitingForStorage) pollTimer = setTimeout(() => load(id), 2000)
      else await refreshHistory()
    } catch (e) {
      if (!alive || sequence !== refreshSequence) return
      // Do not leave a terminal or auth-failed request looking like it is still running.
      // The next page load/login will fetch the authoritative server state again.
      clearTimeout(pollTimer)
      if (e.code === 1401 && current.value?.id === id) {
        current.value = {
          ...current.value,
          status: 'INTERRUPTED',
          error: '登录状态已过期，请重新登录后刷新这条任务。',
        }
      }
      error.value = e.message
      if ((isImageActive(current.value?.status) || thread.value.some(turn => turn.items?.some(item => item.providerImageUrl && !item.persisted && !item.downloadFailed)))
          && String(current.value.id) === String(id)
          && (typeof e.code !== 'number' || e.code >= 5000)) {
        pollError = true
        pollTimer = setTimeout(() => load(id), 5000)
      }
    }
  }
  async function openCreation(id) {
    historyOpen.value = false; error.value = ''; workspaceTab.value = 'result'
    await router.replace({ query: { creation: String(id) } })
    await load(id)
  }
  async function renameCreation(id, title) {
    const saved = await imageApi.rename(id, title)
    history.value = history.value.map(entry => entry.id === id ? { ...entry, title: saved } : entry)
    return saved
  }
  function addFiles(event) {
    if (locked.value) return
    const selected = Array.from(event.target?.files || event.dataTransfer?.files || [])
    error.value = ''
    try {
      if (files.value.length + selected.length > 6) throw new Error('最多添加 6 张参考图片')
      selected.forEach(validateImageFile)
      for (const file of selected) files.value.push({ file, name: file.name, role: 'SUBJECT', preview: URL.createObjectURL(file), assetId: null })
    } catch (e) { error.value = e.message }
    if (event.target?.value) event.target.value = ''
  }
  function removeFile(index) {
    if (locked.value) return
    if (files.value[index].file) URL.revokeObjectURL(files.value[index].preview)
    files.value.splice(index, 1)
  }
  async function deliver(operation) {
    workspaceTab.value = 'result'
    persistPending(operation)
    let result
    if (operation.kind === 'create') result = await imageApi.create(operation.data)
    if (operation.kind === 'revision') result = await imageApi.revision(operation.id, operation.data)
    if (operation.kind === 'answer') result = await imageApi.answer(operation.id, operation.data)
    if (operation.kind === 'text') result = await imageApi.text(operation.id, operation.itemId, operation.data)
    if (operation.kind === 'regenerate') result = await imageApi.regenerate(operation.id, operation.itemId, operation.data)
    persistPending(null)
    if (!alive) return
    previewTracking.add(result.id)
    current.value = result; revision.value = ''; textEdit.value = null
    await openCreation(result.id)
    await refreshHistory()
  }
  async function run(action, creation = false) {
    if (busy.value) return
    busy.value = true; error.value = ''
    try {
      if (creation) await requireAccount()
      if (!alive) return
      await action()
    }
    catch (e) {
      error.value = e.message
      // Business rejection is definitive; a network/server failure may have accepted the request.
      if (typeof e.code === 'number' && (e.code < 5000 || (e.code >= 9001 && e.code <= 9006))) persistPending(null)
    } finally { busy.value = false; refreshBilling() }
  }
  function generate() {
    if (!auth.requireLogin()) return
    if (active.value || (!configured.value && !pending.value)) return
    run(async () => {
      if (pending.value) return deliver(pending.value)
      if (!brief.value.trim() && !files.value.length) throw new Error('说一句需求，或上传商品照片')
      if (product.value && !files.value.some(f => f.role === 'SUBJECT')) throw new Error('先上传至少一张商品照片')
      const submittedBrief = brief.value.trim()
      const submittedFiles = [...files.value]
      workspaceTab.value = 'result'
      if (!product.value) {
        lastPrompt.value = submittedBrief
        optimisticBrief.value = submittedBrief
        optimistic.value = true
        brief.value = ''
        files.value = []
      }
      try {
        for (const file of submittedFiles) if (!file.assetId) file.assetId = await uploadImageReference(file.file)
        await deliver({ kind: 'create', data: {
          requestKey: crypto.randomUUID(), workflow: workflow, brief: submittedBrief,
          references: submittedFiles.map(f => ({ assetId: f.assetId, role: f.role })),
          ratio: ratio.value, quality: quality.value, count: product.value ? Number(count.value) : 1,
          platform: platform.value, imageType: imageType.value,
          purpose: purpose.value, style: style.value,
        } })
        if (!product.value) submittedFiles.forEach(file => { if (file.file) URL.revokeObjectURL(file.preview) })
      } catch (error) {
        if (!product.value) {
          if (alive) {
            brief.value = submittedBrief
            files.value = submittedFiles
          } else submittedFiles.forEach(file => { if (file.file) URL.revokeObjectURL(file.preview) })
        }
        throw error
      } finally {
        optimistic.value = false
      }
    }, true)
  }
  function revise() {
    if (!revision.value.trim()) return
    run(() => deliver({ kind: current.value.status === 'NEEDS_INPUT' ? 'answer' : 'revision', id: current.value.id,
      data: { requestKey: crypto.randomUUID(), instruction: revision.value.trim() } }), true)
  }
  function revisePoster(item, changes) {
    if (!item || !changes) return
    const instructions = []
    if (changes.headline != null || changes.caption != null) {
      instructions.push(`请将海报文字严格修改为：标题「${changes.headline || ''}」；活动说明「${changes.caption || ''}」。除文字外保持商品事实不变。`)
    }
    if (changes.options?.includes('LAYOUT')) instructions.push('换一种版式，改变主体与文字区的构图关系。')
    if (changes.options?.includes('SCENE')) instructions.push('换一个有依据的场景与光线，保持商品身份真实。')
    if (changes.options?.includes('MESSAGE')) instructions.push('换一个传播角度，调整信息强调与视线引导。')
    if (changes.instruction?.trim()) instructions.push(changes.instruction.trim())
    if (!instructions.length) return
    run(() => deliver({ kind: 'revision', id: current.value.id, data: {
      requestKey: crypto.randomUUID(), instruction: instructions.join('\n'), variation: changes.options?.find(option => ['LAYOUT', 'SCENE', 'MESSAGE'].includes(option)) || null,
    } }), true)
  }
  function retry(item) {
    run(async () => {
      current.value = item ? await imageApi.retry(current.value.id, item)
        : await imageApi.retryPlan(current.value.id, current.value.taskId)
      await load(current.value.id)
    }, true)
  }
  function cancel() {
    if (!current.value || !active.value) return
    run(async () => {
      current.value = await imageApi.cancel(current.value.id)
      await load(current.value.id)
    })
  }
  function editText(item) { textEdit.value = item; editHeadline.value = item.headline; editCaption.value = item.caption }
  function saveText() {
    run(() => deliver({ kind: 'text', id: current.value.id, itemId: textEdit.value.id,
      data: { requestKey: crypto.randomUUID(), headline: editHeadline.value, caption: editCaption.value } }), true)
  }
  function download(item) {
    if (!canUseAsset(item)) return
    run(() => downloadImage(latestItem(item).url, '一方志-' + current.value.id + '-' + (item.ordinal + 1) + '.png'))
  }
  function regenerate(item, variation = null) {
    run(() => deliver({ kind: 'regenerate', id: current.value.id, itemId: item.id,
      data: { requestKey: crypto.randomUUID(), variation } }), true)
  }
  async function loadCapabilities() {
    if (!auth.isAuthenticated) return
    capabilityState.value = 'checking'
    capabilityError.value = ''
    try {
      capabilities.value = await imageApi.capabilities()
      capabilityState.value = 'ready'
      syncHighestQuality()
    } catch (e) {
      capabilityState.value = 'error'
      capabilityError.value = e.message || '无法连接图片创作服务'
    }
  }
  onMounted(async () => {
    if (!auth.isAuthenticated) return
    try {
      const saved = sessionStorage.getItem(pendingKey)
      if (saved) pending.value = JSON.parse(saved)
    } catch { /* A malformed pending draft should not block service checks. */ }
    await loadCapabilities()
    if (!alive) return
    await refreshHistory()
    if (route.query.creation) await load(route.query.creation)
  })
  watch(() => route.query.creation, id => { if (id && String(current.value?.id) !== String(id)) load(id) })
  watch(purpose, value => {
    if (!product.value) {
      platform.value = posterPlatform(value)
      imageType.value = 'POSTER'
    }
    if (!product.value) {
      const option = POSTER_PURPOSE_GROUPS.flatMap(group => group.options).find(option => option.label === value)
      if (option && supportsRatio(option.ratio)) ratio.value = option.ratio
      syncHighestQuality()
    }
  })
  watch(ratio, syncHighestQuality)
  onBeforeUnmount(() => {
    alive = false; refreshSequence++; clearTimeout(pollTimer)
    files.value.forEach(f => { if (f.file) URL.revokeObjectURL(f.preview) })
  })

  function resetDraft() {
    if (locked.value) return
    files.value.forEach(file => { if (file.file) URL.revokeObjectURL(file.preview) })
    files.value = []; brief.value = ''
    ratio.value = product.value ? '1:1' : '3:4'
    syncHighestQuality()
    style.value = '帮我搭配'
  }
  function recallLastPrompt() {
    if (!locked.value && lastPrompt.value) brief.value = lastPrompt.value
  }
  return reactive({
    product, brief, ratio, quality, count, platform, imageType, purpose, style, files, busy, error,
    capabilities, capabilityState, capabilityError, current, thread, history, historyMore, historyOpen, revision, textEdit, editHeadline,
    editCaption, pending, lastPrompt, optimistic, optimisticBrief, dimensions, configured, active, locked, workspaceTab, supportsRatio,
    refreshHistory, load, openCreation, renameCreation, addFiles, removeFile, generate, revise,
    retry, cancel, editText, saveText, download, regenerate, revisePoster, resetDraft, recallLastPrompt, canUseAsset,
    reportImageVisible, imageLoadFailed,
    supportsQuality, loadCapabilities, billing, imageCredits, estimatedCredits, requiresLogin,
  })
}
