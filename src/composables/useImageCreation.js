import { computed, reactive, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { imageApi, uploadImageReference, uploadLibraryImageReference, saveGeneratedImageToLibrary, downloadImage } from '../services/imageCreation'
import { IMAGE_QUALITIES, POSTER_PURPOSE_GROUPS, imageDimensions, isImageActive, validateImageFile, supportsImageSize } from '../domain/imageCreation'
import { readUser } from '../utils/tokenStore'

export function useImageCreation(workflow) {
  const route = useRoute()
  const router = useRouter()
  const product = computed(() => workflow === 'PRODUCT_SET')
  const brief = ref(typeof route.query.prompt === 'string' ? route.query.prompt : '')
  const ratio = ref(product.value ? '1:1' : '3:4')
  const quality = ref('1080P')
  const count = ref(product.value ? 3 : 1)
  const purpose = ref(product.value ? '美团 / 大众点评商品展示' : '朋友圈活动宣传')
  const style = ref('帮我搭配')
  const files = ref([])
  const busy = ref(false)
  const error = ref('')
  const capabilities = ref(null)
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
  const assetSaving = ref(new Set())
  let pollTimer
  let alive = true
  let refreshSequence = 0
  const owner = readUser()
  const pendingKey = 'image-pending:' + (owner?.userId || owner?.id || 'session') + ':' + workflow
  const dimensions = computed(() => imageDimensions(quality.value, ratio.value))
  const configured = computed(() => capabilities.value?.configured === true)
  const supportsRatio = value => {
    const ratios = capabilities.value?.qualityRatios
    if (!ratios) return true
    return Object.values(ratios).some(values => values?.includes(value))
  }
  function syncHighestQuality() {
    const supported = IMAGE_QUALITIES.filter(tier => supportsImageSize(capabilities.value, tier, ratio.value))
    if (supported.length) quality.value = supported.at(-1)
  }
  const active = computed(() => isImageActive(current.value?.status))
  const locked = computed(() => busy.value || !!pending.value)
  const workspaceTab = ref('inspiration')
  function persistPending(value) {
    pending.value = value
    if (value) sessionStorage.setItem(pendingKey, JSON.stringify(value))
    else sessionStorage.removeItem(pendingKey)
  }
  async function refreshHistory(reset = true) {
    try {
      const page = reset ? 0 : historyPage.value + 1
      const result = await imageApi.history(workflow, page)
      if (!alive) return
      history.value = reset ? result.items : [...history.value, ...result.items]
      historyPage.value = page; historyMore.value = page + 1 < result.totalPages
    } catch (e) { if (alive) error.value = e.message }
  }
  async function load(id) {
    clearTimeout(pollTimer)
    const sequence = ++refreshSequence
    try {
      const versions = await imageApi.thread(id)
      if (!alive || sequence !== refreshSequence) return
      const result = versions.at(-1)
      if (!result) throw new Error('这条创作记录不存在')
      if (result.workflow !== workflow) throw new Error('这条记录属于另一个图片工作区')
      if (current.value?.id !== result.id) workspaceTab.value = 'result'
      thread.value = versions
      current.value = result
      if (isImageActive(result.status)) pollTimer = setTimeout(() => load(id), 3000)
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
  function addLibraryAsset(asset) {
    if (locked.value || files.value.some(file => file.libraryId === asset.id)) return
    error.value = ''
    if (files.value.length >= 6) { error.value = '最多添加 6 张参考图片'; return }
    files.value.push({ file: null, name: asset.name, role: 'SUBJECT', preview: asset.preview,
      assetId: typeof asset.id === 'number' ? asset.id : null, libraryId: asset.id, libraryUrl: asset.preview })
  }
  function removeFile(index) {
    if (locked.value) return
    if (files.value[index].file) URL.revokeObjectURL(files.value[index].preview)
    files.value.splice(index, 1)
  }
  async function deliver(operation) {
    persistPending(operation)
    let result
    if (operation.kind === 'create') result = await imageApi.create(operation.data)
    if (operation.kind === 'revision') result = await imageApi.revision(operation.id, operation.data)
    if (operation.kind === 'answer') result = await imageApi.answer(operation.id, operation.data)
    if (operation.kind === 'text') result = await imageApi.text(operation.id, operation.itemId, operation.data)
    if (operation.kind === 'regenerate') result = await imageApi.regenerate(operation.id, operation.itemId, operation.data)
    persistPending(null)
    if (!alive) return
    current.value = result; revision.value = ''; textEdit.value = null
    await openCreation(result.id)
    await refreshHistory()
  }
  async function run(action) {
    if (busy.value) return
    busy.value = true; error.value = ''
    try { await action() }
    catch (e) {
      error.value = e.message
      // Business rejection is definitive; a network/server failure may have accepted the request.
      if (typeof e.code === 'number' && e.code < 5000) persistPending(null)
    } finally { busy.value = false }
  }
  function generate() {
    if (active.value || (!configured.value && !pending.value)) return
    run(async () => {
      if (pending.value) return deliver(pending.value)
      if (!brief.value.trim() && !files.value.length) throw new Error('说一句需求，或上传商品照片')
      if (product.value && !files.value.some(f => f.role === 'SUBJECT')) throw new Error('先上传至少一张商品照片')
      const submittedBrief = brief.value.trim()
      const submittedFiles = [...files.value]
      if (!product.value) {
        lastPrompt.value = submittedBrief
        optimisticBrief.value = submittedBrief
        optimistic.value = true
        workspaceTab.value = 'result'
        brief.value = ''
        files.value = []
      }
      try {
        for (const file of submittedFiles) if (!file.assetId) file.assetId = file.file
          ? await uploadImageReference(file.file) : await uploadLibraryImageReference(file.libraryUrl, file.name)
        await deliver({ kind: 'create', data: {
          requestKey: crypto.randomUUID(), workflow: workflow, brief: submittedBrief,
          references: submittedFiles.map(f => ({ assetId: f.assetId, role: f.role })),
          ratio: ratio.value, quality: quality.value, count: product.value ? Number(count.value) : 1,
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
    })
  }
  function revise() {
    if (!revision.value.trim()) return
    run(() => deliver({ kind: current.value.status === 'NEEDS_INPUT' ? 'answer' : 'revision', id: current.value.id,
      data: { requestKey: crypto.randomUUID(), instruction: revision.value.trim() } }))
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
    } }))
  }
  function retry(item) {
    run(async () => {
      current.value = item ? await imageApi.retry(current.value.id, item)
        : await imageApi.retryPlan(current.value.id, current.value.taskId)
      await load(current.value.id)
    })
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
      data: { requestKey: crypto.randomUUID(), headline: editHeadline.value, caption: editCaption.value } }))
  }
  function download(item) {
    run(() => downloadImage(item.url, '一方志-' + current.value.id + '-' + (item.ordinal + 1) + '.png'))
  }
  function saveToLibrary(item) {
    if (!item?.url || assetSaving.value.has(item.id)) return
    assetSaving.value = new Set(assetSaving.value).add(item.id)
    run(async () => {
      await saveGeneratedImageToLibrary(item.url, '一方志-' + current.value.id + '-' + (item.ordinal + 1))
      item.assetSaved = true
    }).finally(() => {
      const next = new Set(assetSaving.value)
      next.delete(item.id)
      assetSaving.value = next
    })
  }
  function regenerate(item, variation = null) {
    run(() => deliver({ kind: 'regenerate', id: current.value.id, itemId: item.id,
      data: { requestKey: crypto.randomUUID(), variation } }))
  }
  onMounted(async () => {
    try {
      const saved = sessionStorage.getItem(pendingKey)
      if (saved) pending.value = JSON.parse(saved)
      capabilities.value = await imageApi.capabilities()
      syncHighestQuality()
    } catch (e) { error.value = e.message }
    if (!alive) return
    await refreshHistory()
    if (route.query.creation) await load(route.query.creation)
  })
  watch(() => route.query.creation, id => { if (id && String(current.value?.id) !== String(id)) load(id) })
  watch(purpose, value => {
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
    product, brief, ratio, quality, count, purpose, style, files, busy, error,
    capabilities, current, thread, history, historyMore, historyOpen, revision, textEdit, editHeadline,
    editCaption, pending, lastPrompt, optimistic, optimisticBrief, dimensions, configured, active, locked, workspaceTab, supportsRatio,
    refreshHistory, load, openCreation, renameCreation, addFiles, addLibraryAsset, removeFile, generate, revise,
    retry, cancel, editText, saveText, download, saveToLibrary, regenerate, revisePoster, resetDraft, recallLastPrompt,
  })
}
