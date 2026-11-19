import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { refreshBilling, requireAccount } from '../stores/billing'
import { uploadImageReference } from '../services/imageCreation'
import { imageInspirationApi, inspirationRecordFiles, inspirationStyle, isImageInspirationDone } from '../services/imageInspirations'
import { videoHistoryTime } from '../services/videoConversations'
import { auth } from '../stores/auth'
import { useGenerationWallet } from './useGenerationWallet'

/**
 * 灵感绘图「灵感方案」：按参考图、绘图风格和一句话出 3 个画面描述，「用这个」填进画面描述。
 * 出过的方案存在服务端，「灵感记录」里可以再看、再用。进阶版的方案面板和新手版对话页共用。
 * props.studio 是 useImageCreation('POSTER') 的返回值。
 */
export function useImageInspiration(props) {
  const studio = computed(() => props.studio)
  const config = ref(null)
  const idea = ref('')
  const working = ref(false)
  const error = ref('')
  const result = ref(null)
  const showResults = ref(false)
  const appliedIndex = ref(-1)
  const resultImages = ref([])
  const resultReferences = ref([])
  // 结果页标题用：这批方案当初的一句话、风格、参考图张数和时间。
  const resultInfo = ref(null)
  // 正在看的是灵感记录里的哪一批；刚出的方案为 null。
  const record = ref(null)
  const records = ref([])
  const recordsPage = ref(0)
  const recordsHasMore = ref(false)
  const recordsLoading = ref(false)
  const recordsError = ref('')
  // 服务商负责人可以选团队额度；余额读得到且不够时先弹充值窗口，不发请求。
  const { wallet, label: walletLabel, shortage } = useGenerationWallet()
  const balanceShortage = ref(null)
  let requestKey = ''
  let signature = ''
  let disposed = false

  const credits = computed(() => config.value?.credits ?? 1)
  const unavailable = computed(() => config.value && !config.value.configured)
  const planFiles = computed(() => studio.value.files.slice(0, config.value?.maxImages ?? 6))
  const styleName = computed(() => inspirationStyle(studio.value.style))
  const missingRecordImages = computed(() => record.value?.images.filter(image => !image.url).length || 0)
  const resultMeta = computed(() => [resultInfo.value?.style, resultInfo.value?.imageCount ? `${resultInfo.value.imageCount} 张参考图` : '',
    resultInfo.value?.createdAt ? videoHistoryTime(resultInfo.value.createdAt) : ''].filter(Boolean).join(' · '))

  const fetchRecords = async page => {
    recordsLoading.value = true
    recordsError.value = ''
    try {
      const data = await imageInspirationApi.history(page)
      if (disposed) return
      const items = data.items || []
      records.value = page === 0 ? items : [...records.value, ...items.filter(item => !records.value.some(known => known.id === item.id))]
      recordsPage.value = page
      recordsHasMore.value = page + 1 < data.totalPages
    } catch (e) {
      recordsError.value = e.message || '灵感记录暂时无法加载'
    } finally {
      recordsLoading.value = false
    }
  }
  // 排队读取：出完方案后的刷新排在进入页面时那次读取后面，才读得到刚出的这批。
  let recordsQueue = Promise.resolve()
  const loadRecords = (page = 0) => (recordsQueue = recordsQueue.then(() => fetchRecords(page)))

  // 出方案时离开页面，回来接着等这一批，出完直接打开；只记在当前浏览器标签页里。
  const pendingKey = `image-inspiration-pending:${auth.user?.id || 'session'}`
  const rememberPending = id => {
    try { if (id) sessionStorage.setItem(pendingKey, String(id)); else sessionStorage.removeItem(pendingKey) }
    catch { /* 存不了只是回来后接不上，方案仍会出现在灵感记录里 */ }
  }
  const pendingId = () => {
    try { return Number(sessionStorage.getItem(pendingKey)) || null }
    catch { return null }
  }

  const wait = ms => new Promise(resolve => window.setTimeout(resolve, ms))

  // 轮询到出完为止；中途离开页面返回 null，记录留着等回来再接上。
  const settle = async batch => {
    rememberPending(batch.id)
    while (!isImageInspirationDone(batch)) {
      await wait(2000)
      if (disposed) return null
      batch = await imageInspirationApi.get(batch.id)
    }
    rememberPending(null)
    refreshBilling()
    return batch
  }

  const run = async (previousId = null) => {
    if (working.value || unavailable.value) return
    error.value = ''
    try { await requireAccount() }
    catch (e) { error.value = e.message; return }
    if (!studio.value.files.length && !idea.value.trim() && !studio.value.brief?.trim()) { error.value = '请先在左侧添加参考图，或写一句想要的画面。'; return }
    if ((balanceShortage.value = shortage(credits.value))) return
    working.value = true
    try {
      // 和生成图片一样：没上传过的参考图先上传，记下素材编号，之后生成图片时不再重复上传。
      const references = [...planFiles.value]
      for (const file of references) if (!file.assetId) file.assetId = await uploadImageReference(file.file)
      const body = { imageAssetIds: references.map(file => file.assetId), style: styleName.value, idea: idea.value.trim(), previousId, sourceBrief: studio.value.brief?.trim() || '' }
      // 网络中断后重试同一份请求沿用原编号，不会重复预占烟火。
      const payer = wallet.value
      if (JSON.stringify({ ...body, payer }) !== signature) { requestKey = `image-inspiration-${crypto.randomUUID()}`; signature = JSON.stringify({ ...body, payer }) }
      const images = references.map(file => file.preview)
      let batch = await imageInspirationApi.create({ requestKey, ...body }, payer)
      refreshBilling()
      batch = await settle(batch)
      if (!batch) return
      signature = ''
      if (batch.status === 'FAILED') { error.value = batch.errorMessage || '灵感方案没有完成，烟火已退回，请重试。'; return }
      result.value = batch
      resultImages.value = images
      resultReferences.value = references.map(file => ({ assetId: file.assetId, name: file.name, url: file.preview }))
      resultInfo.value = { idea: body.idea, sourceBrief: body.sourceBrief, style: body.style, imageCount: images.length, createdAt: batch.createdAt }
      record.value = null
      appliedIndex.value = -1
      showResults.value = true
      loadRecords()
    } catch (e) {
      error.value = e.message || '灵感方案暂时失败，请稍后重试。'
    } finally {
      working.value = false
    }
  }

  // 回车出方案（要扣烟火）；输入法还在选字时的回车只是确认候选，不能当成提交。
  const onIdeaEnter = event => {
    if (event.isComposing || event.keyCode === 229) return
    event.preventDefault()
    run()
  }

  const openRecord = item => {
    if (working.value) return
    result.value = item
    resultImages.value = item.images.map(image => image.url)
    resultInfo.value = { idea: item.idea, style: item.style, imageCount: item.images.length, createdAt: item.createdAt }
    record.value = item
    appliedIndex.value = -1
    idea.value = item.idea || ''
    showResults.value = true
  }
  const resume = async id => {
    working.value = true
    let batch = null
    try { batch = await settle(await imageInspirationApi.get(id)) }
    catch { rememberPending(null) } // 读不到（例如换了账号）就不再接，灵感记录里仍能找到
    finally { working.value = false }
    if (disposed || !batch) return
    if (batch.status === 'FAILED') { error.value = batch.errorMessage || '灵感方案没有完成，烟火已退回，请重试。'; return }
    await loadRecords()
    const item = records.value.find(entry => entry.id === batch.id)
    if (item && !disposed) openRecord(item)
  }

  onMounted(() => {
    imageInspirationApi.config().then(data => { if (!disposed) config.value = data }).catch(() => {})
    loadRecords()
    const pending = pendingId()
    if (pending) resume(pending)
  })
  onBeforeUnmount(() => { disposed = true })

  /**
   * 「用这个」只填画面描述；记录里的方案按当初的参考图写，左侧图片不同就一起换回来。
   * context 默认是当前这一批；对话页里点更早的一批时传那一批的 record / references / info。
   */
  const use = (plan, index, context = { record: record.value, references: resultReferences.value, info: resultInfo.value }) => {
    if (studio.value.locked) return false
    let files
    try { files = inspirationRecordFiles(context.record || { images: context.references }, studio.value.files) }
    catch (e) { error.value = e.message; return false }
    const original = [context.info?.sourceBrief || plan.sourceBrief, context.info?.idea].filter(Boolean).join('\n')
    const brief = original ? `原始需求（最后一句为最新要求）：\n${original}\n画面方案：${plan.prompt}` : plan.prompt
    if (brief.length > 2000) { error.value = '方案超过画面描述的长度限制，请精简后使用。'; return false }
    if (files) {
      studio.value.files.forEach(file => { if (file.file) URL.revokeObjectURL(file.preview) })
      studio.value.files = files
    }
    studio.value.brief = brief
    appliedIndex.value = index
    return true
  }

  return {
    config, idea, working, error, result, showResults, appliedIndex, resultImages, resultReferences, resultInfo, record,
    records, recordsPage, recordsHasMore, recordsLoading, recordsError, credits, unavailable, planFiles, styleName,
    missingRecordImages, resultMeta, loadRecords, run, onIdeaEnter, openRecord, use, wallet, walletLabel, balanceShortage,
  }
}
