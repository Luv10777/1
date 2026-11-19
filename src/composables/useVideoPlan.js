import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { refreshBilling, requireAccount } from '../stores/billing'
import { isVideoPlanDone, videoPlanApi, videoPlanForm, videoPlanLetters, videoPlanRecordImages, videoPlanRequestedSeconds, videoPlanStyles } from '../services/videoPlans'
import { videoHistoryTime } from '../services/videoConversations'
import { FREE_SCENE, findVideoScene, videoSceneKey, videoSceneName } from '../services/videoScenes'
import { auth } from '../stores/auth'
import { useGenerationWallet } from './useGenerationWallet'

/**
 * 视频「帮我出方案」：按参考素材、风格、场景和一句话出 3 个版本，「用这个」通过 emit('apply') 填进视频表单。
 * 出过的方案存在服务端，「灵感记录」里可以再看、再用。进阶版的视频方案面板和新手版对话页共用。
 * props：images、video、upload、maxDuration、duration、ratio、brief、ratios、locked、scene、scenes（同 VideoPlanPanel）。
 * emit：'apply'(form, letter, images, planId)、'scene'(sceneKey)。
 */
export function useVideoPlan(props, emit) {
  const config = ref(null)
  // 服务商负责人可以选团队额度；余额读得到且不够时先弹充值窗口，不发请求。
  const { wallet, label: walletLabel, shortage } = useGenerationWallet()
  const balanceShortage = ref(null)
  const style = ref('')
  const customStyle = ref('')
  const customOpen = ref(false)
  const idea = ref('')
  const working = ref(false)
  const error = ref('')
  const clarification = ref(null)
  const answer = ref('')
  const phase = ref('CONCEPT')
  const phaseLabel = computed(() => ({ CONCEPT: '正在梳理需求与创意', DRAFT: '正在编写完整剧本', REVIEW: '正在审稿和完善剧本' })[phase.value] || '正在准备剧本')
  watch(() => [idea.value, props.brief], () => { clarification.value = null; answer.value = '' }, { flush: 'sync' })
  const result = ref(null)
  const showResults = ref(false)
  const appliedIndex = ref(-1)
  const resultImages = ref([])
  const resultReferences = ref([])
  // 正在看的是灵感记录里的哪一批；刚出的方案为 null。
  const record = ref(null)
  const records = ref([])
  const recordsPage = ref(0)
  const recordsHasMore = ref(false)
  const recordsLoading = ref(false)
  const recordsError = ref('')
  let requestKey = ''
  let signature = ''
  let disposed = false

  const credits = computed(() => config.value?.credits ?? 1)
  const styleText = computed(() => (customOpen.value ? customStyle.value : style.value).trim())
  const unavailable = computed(() => config.value && !config.value.configured)
  const planImages = computed(() => props.images.slice(0, config.value?.maxImages ?? 30))
  const missingRecordImages = computed(() => record.value?.images.filter(image => !image.url).length || 0)
  // 这批方案实际用的场景（「换一批」沿用上一批的），以服务端记录为准。
  const currentScene = computed(() => findVideoScene(props.scenes, props.scene))
  const resultSceneName = computed(() => videoSceneName(props.scenes, result.value?.sceneKey))
  // 结果页标题用：这批方案当初的一句话、风格、参考图张数和时间。
  const resultInfo = ref(null)
  const resultMeta = computed(() => [resultSceneName.value, resultInfo.value?.style,
    resultInfo.value?.imageCount ? `${resultInfo.value.imageCount} 张参考图` : '',
    resultInfo.value?.createdAt ? videoHistoryTime(resultInfo.value.createdAt) : ''].filter(Boolean).join(' · '))

  const fetchRecords = async page => {
    recordsLoading.value = true
    recordsError.value = ''
    try {
      const data = await videoPlanApi.history(page)
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
  const pendingKey = `video-plan-pending:${auth.user?.id || 'session'}`
  const rememberPending = id => {
    try { if (id) sessionStorage.setItem(pendingKey, String(id)); else sessionStorage.removeItem(pendingKey) }
    catch { /* 存不了只是回来后接不上，方案仍会出现在灵感记录里 */ }
  }
  const pendingPlanId = () => {
    try { return Number(sessionStorage.getItem(pendingKey)) || null }
    catch { return null }
  }

  const pickStyle = value => { customOpen.value = false; style.value = style.value === value ? '' : value }
  const openCustom = () => { customOpen.value = true; style.value = '' }
  const wait = ms => new Promise(resolve => window.setTimeout(resolve, ms))

  // 轮询到出完为止；中途离开页面返回 null，记录留着等回来再接上。
  const settle = async plan => {
    rememberPending(plan.id)
    while (!isVideoPlanDone(plan)) {
      phase.value = plan.phase || 'CONCEPT'
      await wait(2000)
      if (disposed) return null
      plan = await videoPlanApi.get(plan.id)
    }
    rememberPending(plan.clarification?.question ? plan.id : null)
    refreshBilling()
    return plan
  }

  const askDetails = plan => {
    if (!plan.clarification?.question) return false
    idea.value = plan.clarification.originalIdea || idea.value
    clarification.value = plan.clarification
    answer.value = ''
    if (plan.clarification.style) {
      customOpen.value = !videoPlanStyles.includes(plan.clarification.style)
      style.value = customOpen.value ? '' : plan.clarification.style
      customStyle.value = customOpen.value ? plan.clarification.style : ''
    }
    showResults.value = false
    return true
  }

  const run = async (previousPlanId = null) => {
    if (working.value || unavailable.value) return
    error.value = ''
    if (clarification.value && !answer.value.trim()) { error.value = '请先补充下面的活动信息，再生成剧本。'; return }
    try { await requireAccount() }
    catch (e) { error.value = e.message; return }
    if (!props.images.length && !idea.value.trim() && !props.brief.trim()) { error.value = '请先在左侧添加参考图，或写一句想要的效果。'; return }
    const seconds = videoPlanRequestedSeconds(idea.value) ?? videoPlanRequestedSeconds(props.brief) ?? props.duration
    if (!Number.isInteger(seconds) || seconds < 5 || seconds > props.maxDuration) { error.value = `你要求 ${seconds} 秒，当前模型最长支持 ${props.maxDuration} 秒。请先调整时长或切换模型，不会自动缩短方案。`; return }
    if ((balanceShortage.value = shortage(credits.value))) return
    working.value = true
    phase.value = 'CONCEPT'
    try {
      const references = [...planImages.value]
      const imageAssetIds = await Promise.all(references.map(entry => props.upload(entry, 'IMAGE')))
      const body = { imageAssetIds, referenceVideo: Boolean(props.video), style: styleText.value, idea: idea.value.trim(), previousPlanId,
        durationSeconds: seconds, maxDurationSeconds: props.maxDuration, ratio: props.ratio || 'auto',
        sourceBrief: clarification.value?.sourceBrief || props.brief.trim(),
        ...(answer.value.trim() && { clarificationAnswer: answer.value.trim() }),
        ...(videoSceneKey(props.scene) !== FREE_SCENE && { sceneKey: props.scene }) }
      // 网络中断后重试同一份请求沿用原编号，不会重复预占烟火。
      const payer = wallet.value
      if (JSON.stringify({ ...body, payer }) !== signature) { requestKey = `video-plan-${crypto.randomUUID()}`; signature = JSON.stringify({ ...body, payer }) }
      const images = references.map(entry => entry.url)
      let plan = await videoPlanApi.create({ requestKey, ...body }, payer)
      refreshBilling()
      plan = await settle(plan)
      if (!plan) return
      signature = ''
      if (plan.status === 'FAILED') { if (!askDetails(plan)) error.value = plan.errorMessage || '出方案没有完成，烟火已退回，请重试。'; return }
      clarification.value = null
      result.value = plan
      resultImages.value = images
      resultReferences.value = references.map((entry, index) => ({ assetId: imageAssetIds[index], name: entry.name, url: entry.url }))
      resultInfo.value = { idea: body.idea, sourceBrief: body.sourceBrief, clarificationAnswer: body.clarificationAnswer, style: body.style, imageCount: images.length, createdAt: plan.createdAt }
      record.value = null
      appliedIndex.value = -1
      showResults.value = true
      loadRecords()
    } catch (e) {
      error.value = e.message || '出方案暂时失败，请稍后重试。'
    } finally {
      working.value = false
    }
  }

  // 打开一批记录时把当时的风格和想法也带回来，「换一批」「改设置」都接着这批的要求。
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
    customOpen.value = Boolean(item.style) && !videoPlanStyles.includes(item.style)
    style.value = customOpen.value ? '' : item.style || ''
    customStyle.value = customOpen.value ? item.style : ''
    idea.value = item.idea || ''
    clarification.value = null
    answer.value = item.plans?.[0]?.clarificationAnswer || ''
    // 工作台的场景一起切回这批当时的场景，之后的生成和这批方案用同一个场景。
    emit('scene', videoSceneKey(item.sceneKey))
    showResults.value = true
  }
  const resume = async id => {
    working.value = true
    let plan = null
    try { plan = await settle(await videoPlanApi.get(id)) }
    catch { rememberPending(null) } // 方案读不到（例如换了账号）就不再接，灵感记录里仍能找到
    finally { working.value = false }
    if (disposed || !plan) return
    if (plan.status === 'FAILED') { if (!askDetails(plan)) error.value = plan.errorMessage || '出方案没有完成，烟火已退回，请重试。'; return }
    await loadRecords()
    const item = records.value.find(entry => entry.id === plan.id)
    if (item && !disposed) openRecord(item)
  }

  onMounted(() => {
    videoPlanApi.config().then(data => { if (!disposed) config.value = data }).catch(() => {})
    loadRecords()
    const pending = pendingPlanId()
    if (pending) resume(pending)
  })
  onBeforeUnmount(() => { disposed = true })


  // context 默认是当前这一批；对话页里点更早的一批时传那一批的 record / references / info / resultId。
    const use = (plan, index, context = { record: record.value, references: resultReferences.value, info: resultInfo.value, resultId: result.value?.id }) => {
      if (props.locked) return false
      try {
        const form = videoPlanForm(plan, { maxDuration: props.maxDuration, ratios: props.ratios, idea: context.info?.idea || '', sourceBrief: context.info?.sourceBrief || plan.sourceBrief || '' })
        const images = videoPlanRecordImages(context.record || { images: context.references }, props.images)
        emit('apply', form, videoPlanLetters[index], images, context.resultId)
        appliedIndex.value = index
        error.value = ''
        return true
      } catch (e) { error.value = e.message; return false }
    }

  return {
    config, style, customStyle, customOpen, idea, working, error, clarification, answer, phase, phaseLabel, result, showResults,
    appliedIndex, resultImages, resultReferences, resultInfo, record, records, recordsPage, recordsHasMore, recordsLoading, recordsError,
    credits, styleText, unavailable, planImages, missingRecordImages, currentScene, resultSceneName, resultMeta,
    loadRecords, pickStyle, openCustom, run, onIdeaEnter, openRecord, use, wallet, walletLabel, balanceShortage,
  }
}
