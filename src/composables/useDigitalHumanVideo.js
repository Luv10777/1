import { computed, onBeforeUnmount, ref } from 'vue'
import { isVideoInProgress } from '../services/videoConversations'
import { videoApi, uploadVideoReference } from '../services/videoWorkflow'

export function useDigitalHumanVideo() {
  const workflow = ref(null)
  const submitting = ref(false)
  const previewStarted = ref(false)
  const submittedForm = ref(null)
  const error = ref('')
  const isGenerating = computed(() => submitting.value || isVideoInProgress(workflow.value))
  const outputUrl = computed(() => workflow.value?.outputUrl || '')
  const stageLabel = computed(() => submitting.value ? '正在提交创作' : ({ SUBMIT: '正在提交创作', POLL: '画面渲染中', IMPORT: '正在保存成片', QA: '正在检查成片' }[workflow.value?.stage] || '画面渲染中'))
  const notice = computed(() => error.value || (submitting.value ? '正在上传参考素材并提交视频任务…' : workflow.value?.status === 'SUCCEEDED' ? '视频已完成并保存到作品库。' : ''))
  let timer
  let disposed = false
  let requestKey = ''
  let payloadSignature = ''

  const updateWorkflow = result => {
    workflow.value = result
    error.value = ['FAILED', 'CANCELED'].includes(result.status) ? result.error || '视频生成失败，请稍后重试。' : ''
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
  const start = async input => {
    if (isGenerating.value || disposed) return
    const previousWorkflow = workflow.value
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
        model: input.model, ratio: input.ratio, durationSeconds: input.durationSeconds, resolution: input.resolution }
      const signature = JSON.stringify(payload)
      // Reuse the key after an uncertain submission; a known completed task starts a new generation.
      if (!requestKey || payloadSignature !== signature || previousWorkflow) requestKey = `video-${crypto.randomUUID()}`
      payloadSignature = signature
      const result = await videoApi.create({ requestKey, ...payload })
      if (disposed) return
      updateWorkflow(result)
      if (isVideoInProgress(result)) schedulePoll()
    } catch (failure) {
      if (!disposed) error.value = failure.message || '视频任务提交失败，请稍后重试。'
    } finally {
      submitting.value = false
    }
  }
  onBeforeUnmount(() => { disposed = true; window.clearTimeout(timer) })
  return { workflow, isGenerating, previewStarted, submittedForm, outputUrl, stageLabel, notice, error, start }
}
