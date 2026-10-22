import { computed, onBeforeUnmount, ref } from 'vue'
import { isVideoInProgress, videoCompletionNotice } from '../services/videoConversations'
import { getVideoQuote, videoApi, uploadVideoReference } from '../services/videoWorkflow'
import { refreshBilling } from '../stores/billing'

export function useDigitalHumanVideo() {
  const workflow = ref(null)
  const submitting = ref(false)
  const previewStarted = ref(false)
  const submittedForm = ref(null)
  const error = ref('')
  const quoted = ref(null)
  const quoting = ref(false)
  let quotedInput = null
  const isGenerating = computed(() => submitting.value || isVideoInProgress(workflow.value))
  const outputUrl = computed(() => workflow.value?.outputUrl || '')
  const stageLabel = computed(() => submitting.value ? '正在提交创作' : ({ SUBMIT: '正在提交创作', POLL: '画面渲染中', IMPORT: '正在保存成片', QA: '正在检查成片' }[workflow.value?.stage] || '画面渲染中'))
  const notice = computed(() => error.value || (submitting.value ? '正在上传参考素材并提交视频任务…' : workflow.value?.status === 'SUCCEEDED' ? videoCompletionNotice(workflow.value) : ''))
  let timer
  let disposed = false
  let requestKey = ''
  let payloadSignature = ''

  const updateWorkflow = result => {
    workflow.value = result
    refreshBilling()
    error.value = ['FAILED', 'CANCELLED'].includes(result.status) ? result.error || '视频生成失败，请稍后重试。' : ''
  }
  const schedulePoll = () => {
    window.clearTimeout(timer)
    if (!disposed) timer = window.setTimeout(pollWorkflow, 2500)
  }
  const pollWorkflow = async () => {
    try {
      const result = await videoApi.get(workflow.value.id)
      if (disposed) return
      updateWorkflow(result)
    } catch (failure) {
      if (disposed) return
      error.value = failure.message || '状态更新暂时中断，正在重试…'
    }
    if (isVideoInProgress(workflow.value)) schedulePoll()
  }
  const estimate = async input => {
    if (isGenerating.value || quoting.value || disposed) return
    quoting.value = true; error.value = ''; quoted.value = null
    try {
      const imageIds = await Promise.all(input.images.map(entry => uploadVideoReference(entry, 'IMAGE')))
      const videoId = input.video ? await uploadVideoReference(input.video, 'VIDEO') : null
      const payload = { prompt: input.prompt, referenceImageAssetIds: imageIds, referenceVideoAssetId: videoId,
        model: input.model, ratio: input.ratio, durationSeconds: input.durationSeconds, resolution: input.resolution,
        sourceModule: 'DIGITAL_HUMAN' }
      const signature = JSON.stringify(payload)
      if (!requestKey || payloadSignature !== signature || workflow.value) requestKey = `video-${crypto.randomUUID()}`
      payloadSignature = signature
      const result = await getVideoQuote({ requestKey, ...payload })
      if (!disposed) { quoted.value = { quote: result, requestKey, signature }; quotedInput = input }
      return result
    } catch (failure) { if (!disposed) error.value = failure.message || '费用暂时无法预估' }
    finally { quoting.value = false }
  }
  const start = async (input, approval) => {
    if (isGenerating.value || disposed) return
    if (!approval?.quote.available || !approval.quote.quoteId) { error.value = '请先预估并确认视频费用'; return }
    window.clearTimeout(timer)
    submitting.value = true
    previewStarted.value = true
    submittedForm.value = { ...input }
    workflow.value = null
    error.value = ''
    try {
      const imageIds = await Promise.all(input.images.map(entry => uploadVideoReference(entry, 'IMAGE')))
      const videoId = input.video ? await uploadVideoReference(input.video, 'VIDEO') : null
      if (disposed) return
      const payload = { prompt: input.prompt, referenceImageAssetIds: imageIds, referenceVideoAssetId: videoId,
        model: input.model, ratio: input.ratio, durationSeconds: input.durationSeconds, resolution: input.resolution,
        sourceModule: 'DIGITAL_HUMAN' }
      const signature = JSON.stringify(payload)
      // The approved key is also reused after an uncertain submission.
      if (approval.signature !== signature) throw new Error('配置已变化，请重新预估费用')
      requestKey = approval.requestKey
      payloadSignature = signature
      const result = await videoApi.create({ requestKey, ...payload, quoteId: approval.quote.quoteId })
      if (disposed) return
      updateWorkflow(result)
      if (isVideoInProgress(result)) schedulePoll()
    } catch (failure) {
      if (!disposed) error.value = failure.message || '视频任务提交失败，请稍后重试。'
    } finally {
      submitting.value = false
    }
  }
  const confirmQuote = async () => {
    if (!quoted.value?.quote.available || !quotedInput) return
    await start(quotedInput, quoted.value)
    if (workflow.value) quoted.value = null
  }
  const closeQuote = () => { if (!submitting.value) { quoted.value = null; quotedInput = null } }
  // 从创作记录打开一条已提交的任务：在预览区展示它，未结束的继续轮询。
  const open = result => {
    if (submitting.value || disposed) return
    window.clearTimeout(timer)
    previewStarted.value = true
    submittedForm.value = { model: result.model, ratio: result.ratio, durationSeconds: result.durationSeconds, resolution: result.resolution }
    updateWorkflow(result)
    if (isVideoInProgress(result)) schedulePoll()
  }
  const reset = () => {
    if (submitting.value) return
    window.clearTimeout(timer)
    previewStarted.value = false
    submittedForm.value = null
    workflow.value = null
    error.value = ''
  }
  onBeforeUnmount(() => { disposed = true; window.clearTimeout(timer) })
  return { workflow, isGenerating, previewStarted, submittedForm, outputUrl, stageLabel, notice, error, start, quoted, quoting, estimate, confirmQuote, closeQuote, open, reset }
}
