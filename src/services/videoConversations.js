export const videoModels = [
  { id: 'SEEDANCE_2_5', label: 'Seedance 2.5', maxDurationSeconds: 30, resolutions: ['480p', '720p', '1080p'] },
  { id: 'SEEDANCE_2_0', label: 'Seedance 2.0', maxDurationSeconds: 15, resolutions: ['480p', '720p', '1080p', '4K'] },
  { id: 'SEEDANCE_2_0_MINI', label: 'Seedance 2.0 Mini', maxDurationSeconds: 15, resolutions: ['480p', '720p'] },
  { id: 'SEEDANCE_2_0_FAST', label: 'Seedance 2.0 Fast', maxDurationSeconds: 15, resolutions: ['480p', '720p'] },
]

export const videoModelLabel = id => videoModels.find(model => model.id === id)?.label || id
export const isVideoInProgress = workflow => Boolean(workflow && !['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(workflow.status))
export const videoConversationTitle = prompt => prompt.trim().replace(/\s+/g, ' ').slice(0, 28) || '新的视频创作'

export const videoCompletionNotice = () => '视频已完成并保存到作品库。'

// Copy the attachment entries as well as the settings: editing a recalled form must
// never change the inputs belonging to an earlier generation.
export function snapshotVideoForm(form) {
  return {
    prompt: form.prompt,
    format: form.format,
    duration: form.duration,
    resolution: form.resolution,
    selectedModel: form.selectedModel,
    referenceImages: form.referenceImages.map(entry => ({ ...entry })),
    referenceVideo: form.referenceVideo ? { ...form.referenceVideo } : null,
  }
}

export function videoFormFromWorkflow(workflow) {
  const entry = reference => ({ id: `asset-${reference.assetId}`, assetId: reference.assetId, name: reference.name, url: reference.url, file: null })
  return {
    prompt: workflow.prompt,
    format: workflow.ratio,
    duration: workflow.durationSeconds,
    resolution: workflow.resolution,
    selectedModel: workflow.model,
    referenceImages: (workflow.referenceImages || []).map(entry),
    referenceVideo: workflow.referenceVideo ? entry(workflow.referenceVideo) : null,
  }
}
