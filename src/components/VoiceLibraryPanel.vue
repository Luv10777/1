<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { voiceApi, voiceFileType, measureVoiceFile, voiceDurationProblem } from '../services/voiceApi.js'
import { createVoiceRecorder } from '../services/voiceRecorder.js'
import { PERSONA_STYLES } from '../services/liveApi.js'
import { auth } from '../stores/auth.js'
import { stores as merchantStores } from '../stores/merchantContext.js'

const props = defineProps({ storeId: { type: [Number, String], default: null }, voiceRoles: { type: Array, default: () => [] } })
const emit = defineEmits(['loaded', 'change'])
// 主播在话术里怎么自称、用什么语气。只有称呼和几种固定风格，不接受任意文字进提示词。
const persona = defineModel('persona', { type: Object, default: () => ({ name: '', style: PERSONA_STYLES[0] }) })
const personaName = computed({
  get: () => persona.value?.name || '',
  // 称呼会原样写进提示词，所以只留汉字和字母。
  set: value => { persona.value = { ...persona.value, name: String(value).replace(/[^\p{Script=Han}A-Za-z]/gu, '').slice(0, 12) } },
})
const setStyle = style => { persona.value = { ...persona.value, style } }
// 声音样本归商户：新建、改名、克隆、删除和调整开放门店只有老板能做，能进这家店的人都可以用。
const isOwner = computed(() => auth.isOwner)
const capabilities = ref({ configured: false, builtInVoices: [], message: '正在读取语音服务状态…' })
const capabilitiesLoaded = ref(false)
const samples = ref([])
const loading = ref(false)
const busy = ref('')
const error = ref('')
const notice = ref('')
const formOpen = ref(false)
const nameInput = ref(null)
const uploadEntry = ref(null)
const renameInput = ref(null)
const name = ref('')
const file = ref(null)
const consent = ref(false)
const fileInput = ref(null)
const editingId = ref(null)
const editingName = ref('')
const playingId = ref('')
const previewBusy = ref('')
const recordingState = ref('idle')
const recordingDialog = ref(null)
const recordingError = ref('')
const samplePlayer = ref(null)
const recordSeconds = ref(0)
const sampleUrl = ref('')
const recordingBusy = computed(() => recordingState.value !== 'idle')
const recorder = createVoiceRecorder({
  onState: state => { recordingState.value = state },
  onTick: seconds => { recordSeconds.value = seconds },
  onComplete: recorded => { file.value = recorded; error.value = ''; closeRecording() },
  onError: message => { recordingError.value = message },
})
const pollRunning = ref(false)
let pollingPaused = false
let scopeTicket = 0
let playbackTicket = 0
let previewAudio = null
// 试听用的是一句固定文案。同一个音色合成过一次就留着，再点不用重新合成、重新计费。
const previewCache = new Map()
const clearPreviewCache = () => { for (const url of previewCache.values()) URL.revokeObjectURL(url); previewCache.clear() }

const stateLabels = { PENDING_UPLOAD: '上传未完成', UPLOADED: '待训练 · 样本已保存', CLONING: '训练处理中', READY: '克隆完成', FAILED: '克隆失败', DELETING: '删除未完成' }
const displayName = (id, index) => ({ longanyang: '龙安洋' }[id] || (capabilities.value.builtInVoices.length === 1 ? '默认音色' : `预设音色 ${index + 1}`))
const voices = computed(() => [
  ...(capabilities.value.builtInVoices || []).map((id, index) => ({ id: `builtin:${id}`, builtInVoice: id, name: displayName(id, index), builtin: true, status: 'READY' })),
  ...samples.value.map(sample => ({ ...sample, id: `sample:${sample.id}`, sampleId: sample.id, builtin: false })),
].map(voice => ({ ...voice, role: props.voiceRoles.find(item => item.id === voice.id)?.role || 'none', usable: ready(voice) })))
const modelMatches = voice => voice.builtin || capabilities.value.providerCode !== 'dashscope-cosyvoice' || voice.providerVoiceId?.startsWith(`${capabilities.value.model}-`)
const ready = voice => capabilities.value.configured && voice.status === 'READY' && modelMatches(voice)
const sampleAvailable = voice => !voice.builtin && !['PENDING_UPLOAD', 'DELETING'].includes(voice.status)
const cohostCount = computed(() => voices.value.filter(voice => voice.role === 'cohost').length)
const previewKey = voice => `${voice.id}:${ready(voice) ? 'speech' : 'sample'}`
const isPlaying = voice => playingId.value.startsWith(`${voice.id}:`)
const previewLabel = voice => previewBusy.value === previewKey(voice) ? '正在准备试听' : playingId.value === previewKey(voice) ? `停止试听 ${voice.name}` : `试听${ready(voice) ? '合成音色' : '原样本'} ${voice.name}`
const currentScope = (ticket, storeId) => ticket === scopeTicket && storeId === props.storeId
const publishLoaded = () => emit('loaded', voices.value, capabilitiesLoaded.value)

function stopPreview() {
  playbackTicket++
  if (previewAudio) { previewAudio.onended = null; previewAudio.onerror = null; previewAudio.pause(); previewAudio.src = ''; previewAudio = null }
  playingId.value = ''
  previewBusy.value = ''
}

async function load() {
  const ticket = ++scopeTicket
  const storeId = props.storeId
  stopPreview()
  clearPreviewCache()
  closeRecording()
  pollingPaused = false
  loading.value = true
  samples.value = []
  emit('loaded', [], false)
  busy.value = ''
  error.value = ''
  notice.value = ''
  formOpen.value = false
  file.value = null
  consent.value = false
  name.value = ''
  editingId.value = null
  capabilities.value = { configured: false, builtInVoices: [], message: '正在读取语音服务状态…' }
  capabilitiesLoaded.value = false
  if (!storeId) { loading.value = false; emit('loaded', []); return }
  const result = await Promise.allSettled([voiceApi.capabilities(), voiceApi.list(storeId)])
  if (!currentScope(ticket, storeId)) return
  if (result[0].status === 'fulfilled') { capabilities.value = result[0].value; capabilitiesLoaded.value = true }
  else capabilities.value.message = '语音服务状态读取失败，当前不能克隆或合成。'
  if (result[1].status === 'fulfilled') { samples.value = result[1].value; publishLoaded() }
  if (result.some(item => item.status === 'rejected')) error.value = result.filter(item => item.status === 'rejected').map(item => item.reason.message).join('；')
  loading.value = false
}

async function selectFile(event) {
  const input = event.target
  const next = input.files?.[0]
  if (!next) return
  const ticket = scopeTicket
  try {
    voiceFileType(next)
    const problem = voiceDurationProblem(await measureVoiceFile(next))
    if (ticket !== scopeTicket) return
    if (problem) throw new Error(problem)
    file.value = next
    error.value = ''
  } catch (failure) {
    if (ticket !== scopeTicket) return
    error.value = failure.message
    file.value = null
    input.value = ''
  }
}

function openRecording() {
  stopPreview()
  samplePlayer.value?.pause()
  recordingError.value = ''
  recordSeconds.value = 0
  recordingDialog.value?.showModal()
}

function startRecording() {
  recordingError.value = ''
  recorder.start()
}

function closeRecording() {
  recorder.cancel()
  recordingDialog.value?.close()
  recordingError.value = ''
}

async function pollClones() {
  if (pollRunning.value || pollingPaused || busy.value || loading.value || !capabilities.value.configured) return
  const pending = samples.value.filter(sample => sample.status === 'CLONING' && sample.providerVoiceId)
  if (!pending.length) return
  const ticket = scopeTicket
  const storeId = props.storeId
  pollRunning.value = true
  try {
    for (const sample of pending) {
      const updated = await voiceApi.refresh(sample.id)
      if (!currentScope(ticket, storeId)) return
      samples.value = samples.value.map(item => item.id === updated.id ? updated : item)
      if (updated.status === 'READY') notice.value = '声音克隆完成，可以试听并设为主播。'
      publishLoaded()
    }
  } catch (failure) {
    if (currentScope(ticket, storeId)) { pollingPaused = true; error.value = `${failure.message}，可点击“查询训练状态”重试。` }
  } finally { pollRunning.value = false }
}
const pollTimer = setInterval(pollClones, 5000)

async function openForm() {
  formOpen.value = true
  await nextTick()
  nameInput.value?.focus()
}

async function closeForm() {
  if (busy.value) return
  closeRecording()
  formOpen.value = false
  file.value = null
  consent.value = false
  name.value = ''
  await nextTick()
  uploadEntry.value?.focus()
}

async function startRename(voice) {
  editingId.value = voice.sampleId
  editingName.value = voice.name
  await nextTick()
  renameInput.value?.[0]?.focus()
}

async function uploadSample(train = false) {
  if (!file.value || !consent.value || !name.value.trim() || busy.value || recordingBusy.value) return
  const ticket = scopeTicket
  const storeId = props.storeId
  busy.value = 'upload'
  error.value = ''
  try {
    const sample = await voiceApi.upload(storeId, { name: name.value, file: file.value, consent: consent.value })
    if (!currentScope(ticket, storeId)) return
    samples.value.unshift(sample)
    name.value = ''; file.value = null; consent.value = false; formOpen.value = false
    if (fileInput.value) fileInput.value.value = ''
    notice.value = '样本已保存，授权确认时间和操作人已记录。'
    publishLoaded()
    if (train) {
      busy.value = `clone:sample:${sample.id}`
      const trained = await voiceApi.clone(sample.id)
      if (!currentScope(ticket, storeId)) return
      samples.value = samples.value.map(item => item.id === trained.id ? trained : item)
      pollingPaused = false
      notice.value = trained.status === 'READY' ? '声音克隆完成，可以试听并设为主播。' : '训练已提交，正在等待音色服务处理。'
      publishLoaded()
    }
  } catch (failure) {
    if (currentScope(ticket, storeId)) {
      error.value = failure.message
      try { const list = await voiceApi.list(storeId); if (currentScope(ticket, storeId)) { samples.value = list; publishLoaded() } } catch { /* Preserve the original upload failure. */ }
    }
  } finally { if (currentScope(ticket, storeId)) busy.value = '' }
}

async function mutate(voice, operation) {
  if (busy.value) return
  const ticket = scopeTicket
  const storeId = props.storeId
  busy.value = `${operation}:${voice.id}`
  error.value = ''
  stopPreview()
  try {
    if (operation === 'delete') {
      await voiceApi.delete(voice.sampleId)
      if (!currentScope(ticket, storeId)) return
      samples.value = samples.value.filter(sample => sample.id !== voice.sampleId)
      notice.value = '样本和关联克隆音色已删除，授权审计记录将保留。'
    } else {
      const sample = operation === 'clone' ? await voiceApi.clone(voice.sampleId) : operation === 'refresh' ? await voiceApi.refresh(voice.sampleId) : await voiceApi.rename(voice.sampleId, editingName.value.trim())
      if (!currentScope(ticket, storeId)) return
      samples.value = samples.value.map(item => item.id === sample.id ? sample : item)
      editingId.value = null
      pollingPaused = false
      notice.value = operation !== 'rename' ? (sample.status === 'READY' ? '声音克隆完成，可以合成试听。' : '已查询训练状态，请查看音色状态。') : '名称已更新。'
    }
    publishLoaded()
  } catch (failure) {
    if (currentScope(ticket, storeId)) {
      error.value = failure.message
      try { const list = await voiceApi.list(storeId); if (currentScope(ticket, storeId)) { samples.value = list; publishLoaded() } } catch { /* The operation error remains visible. */ }
    }
  } finally { if (currentScope(ticket, storeId)) busy.value = '' }
}
function remove(voice) {
  const role = voice.role === 'host' ? '它是当前配置的主播，删除后需要重新选择主播。' : voice.role === 'cohost' ? '它是当前配置的助播，删除后不再参与轮换。' : ''
  if (window.confirm(`删除“${voice.name}”的声音样本及关联克隆音色？${role}删除后不能恢复，授权审计记录将保留。`)) mutate(voice, 'delete')
}

async function preview(voice, original = false) {
  const key = `${voice.id}:${original ? 'sample' : 'speech'}`
  if (playingId.value === key) { stopPreview(); return }
  stopPreview()
  const requestTicket = ++playbackTicket
  const ticket = scopeTicket
  const storeId = props.storeId
  previewBusy.value = key
  error.value = ''
  try {
    let source
    if (original) source = (await voiceApi.download(voice.sampleId)).downloadUrl
    else {
      // 重新克隆会换一个音色 ID，所以把它算进缓存的键里。
      const cacheKey = `${voice.id}:${voice.providerVoiceId || ''}`
      source = previewCache.get(cacheKey)
      if (!source) {
        const audio = await voiceApi.synthesize(storeId, { text: '欢迎来到我们的直播间，今天为大家介绍门店的商品，有任何问题都可以留言。', ...(voice.builtin ? { builtInVoice: voice.builtInVoice } : { sampleId: voice.sampleId }) })
        if (!currentScope(ticket, storeId)) return
        const bytes = Uint8Array.from(atob(audio.audioBase64), character => character.charCodeAt(0))
        source = URL.createObjectURL(new Blob([bytes], { type: audio.mimeType || 'audio/wav' }))
        previewCache.set(cacheKey, source)
      }
    }
    if (!currentScope(ticket, storeId) || requestTicket !== playbackTicket) return
    const audio = new Audio(source)
    previewAudio = audio
    audio.onended = () => { if (previewAudio === audio) stopPreview() }
    audio.onerror = () => { if (previewAudio === audio) { stopPreview(); error.value = '音频无法播放，请检查网络后重新试听。' } }
    await audio.play()
    if (!currentScope(ticket, storeId) || requestTicket !== playbackTicket) { audio.pause(); return }
    playingId.value = key
  } catch (failure) {
    if (currentScope(ticket, storeId) && requestTicket === playbackTicket) { stopPreview(); error.value = failure.message || '试听失败，请重试。' }
  } finally { if (requestTicket === playbackTicket) previewBusy.value = '' }
}

// 老板勾选或取消一家门店。把当前门店取消后，这个声音就不再出现在本页，所以改完重新读一次列表。
async function toggleStore(voice, storeId, open) {
  if (busy.value) return
  const ticket = scopeTicket
  const scopeStoreId = props.storeId
  const next = open ? [...new Set([...(voice.storeIds || []), storeId])] : (voice.storeIds || []).filter(id => id !== storeId)
  busy.value = `stores:${voice.id}`; error.value = ''; notice.value = ''
  try {
    await voiceApi.setStores(voice.sampleId, next)
    if (!currentScope(ticket, scopeStoreId)) return
    notice.value = open ? '已开放给所选门店。' : '已停止向该门店开放。'
  } catch (failure) {
    if (currentScope(ticket, scopeStoreId)) error.value = failure.message || '开放门店保存失败，请重试。'
  } finally {
    if (currentScope(ticket, scopeStoreId)) {
      try { samples.value = await voiceApi.list(scopeStoreId); publishLoaded() } catch { /* 保存结果已提示，列表下次刷新时对齐。 */ }
      busy.value = ''
    }
  }
}
// 一个主播，可以有多个助播。再点一次已选中的角色就是取消。
function setRole(voice, role) {
  if (!ready(voice)) return
  const next = voices.value
    .map(item => ({ id: item.id, role: item.id === voice.id ? (item.role === role ? 'none' : role) : role === 'host' && item.role === 'host' ? 'none' : item.role }))
    .filter(item => item.role !== 'none')
  emit('change', { voiceRoles: next })
}
const consentDate = value => value ? new Date(value).toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '—'
watch(() => props.storeId, load, { immediate: true })
watch(file, value => {
  if (sampleUrl.value) URL.revokeObjectURL(sampleUrl.value)
  sampleUrl.value = value ? URL.createObjectURL(value) : ''
})
onBeforeUnmount(() => { scopeTicket++; stopPreview(); clearPreviewCache(); recorder.cancel(); clearInterval(pollTimer); if (sampleUrl.value) URL.revokeObjectURL(sampleUrl.value) })
</script>

<template>
  <section class="panel ls-section voice-library">
    <div class="panel-heading">
      <div><p class="eyebrow">STEP 01</p><h3>主播人设与声音</h3></div>
      <div class="ls-step-heading-actions">
        <button type="button" class="voice-refresh" :disabled="loading || !!busy" aria-label="刷新音色" title="刷新音色" @click="load"><span class="material-symbols-outlined" aria-hidden="true">refresh</span></button>
      </div>
    </div>
    <div class="voice-persona">
      <label class="voice-persona-name"><span>主播称呼</span><input v-model="personaName" maxlength="12" placeholder="选填，例如：小林" aria-describedby="voice-persona-hint"></label>
      <div class="voice-persona-style" role="group" aria-label="说话风格"><span>说话风格</span><div class="ls-tone-options"><button v-for="style in PERSONA_STYLES" :key="style" type="button" :class="{ on: persona.style === style }" :aria-pressed="persona.style === style" @click="setStyle(style)">{{ style }}</button></div></div>
    </div>
    <p id="voice-persona-hint" class="ls-section-note">称呼和风格用于自动讲解写话术；你手动填写的播报内容不会被改写。</p>
    <p class="ls-section-note">选一个音色做主播。再设助播的话，自动讲解每讲完一件商品，就在主播和助播之间换一个声音{{ cohostCount ? `（当前 ${cohostCount} 个助播）` : '' }}；手动播报始终用主播。</p>
    <p v-if="error" class="voice-error" role="alert">{{ error }}</p>
    <p v-else-if="notice" class="voice-notice" role="status">{{ notice }}</p>
    <p v-if="loading" class="ls-section-note" role="status">正在加载声音样本…</p>
    <p v-else-if="!capabilities.configured" class="voice-service-note">{{ capabilities.message }}</p>
    <div v-if="!loading" class="ls-voice-grid">
      <article v-for="voice in voices" :key="voice.id" class="ls-voice-card" :class="{ assigned: voice.role !== 'none', selected: voice.role === 'host' }">
        <div class="voice-card-meta">
          <span class="voice-kind" :class="{ clone: !voice.builtin }">{{ voice.builtin ? '预设音色' : '商户克隆' }}</span>
          <div v-if="!voice.builtin && isOwner" class="voice-manage">
            <button type="button" :disabled="!!busy" :aria-label="`编辑${voice.name}名称`" title="编辑名称" @click="startRename(voice)"><span class="material-symbols-outlined" aria-hidden="true">edit</span></button>
            <button type="button" :disabled="!!busy || (voice.status === 'CLONING' && !voice.stalled)" :aria-label="`删除${voice.name}样本`" title="删除音色" @click="remove(voice)"><span class="material-symbols-outlined" aria-hidden="true">delete</span></button>
          </div>
        </div>
        <form v-if="editingId === voice.sampleId && !voice.builtin" class="voice-rename" @submit.prevent="mutate(voice, 'rename')" @keydown.esc.prevent="editingId = null">
          <input ref="renameInput" v-model="editingName" maxlength="100" aria-label="音色名称" :disabled="!!busy" />
          <button type="submit" class="voice-text-button" :disabled="!editingName.trim() || !!busy">保存</button>
          <button type="button" class="voice-text-button muted" :disabled="!!busy" @click="editingId = null">取消</button>
        </form>
        <div v-else class="ls-voice-top">
          <span class="ls-voice-avatar" aria-hidden="true">{{ voice.name.slice(0, 1) }}</span>
          <div class="ls-voice-copy">
            <strong :title="voice.name">{{ voice.name }}</strong>
            <small :class="{ 'voice-error': voice.status === 'FAILED' || voice.stalled }">{{ voice.builtin ? (capabilities.configured ? '可试听并设为主播' : '等待语音服务配置') : voice.stalled ? '克隆请求中断' : stateLabels[voice.status] || voice.status }}</small>
          </div>
        </div>
        <p v-if="voice.errorMessage" class="voice-error">{{ voice.errorMessage }}</p>
        <p v-if="voice.stalled" class="voice-error">上次提交克隆时服务中断了，没有拿到结果。可以重新提交，或删除这个样本。</p>
        <p v-if="voice.status === 'READY' && !modelMatches(voice)" class="voice-error">此音色与当前模型不匹配，请切换原模型或重新创建。</p>
        <div class="ls-wave-row">
          <div class="ls-wave" :class="{ active: isPlaying(voice) }" aria-hidden="true"><i v-for="n in 22" :key="n" :style="{ animationDelay: `${n * 55}ms` }" /></div>
          <button type="button" class="ls-play" :class="{ playing: isPlaying(voice) }" :disabled="(!ready(voice) && !sampleAvailable(voice)) || !!busy || !!previewBusy" :aria-label="previewLabel(voice)" :title="previewLabel(voice)" :aria-busy="previewBusy === previewKey(voice)" @click="preview(voice, !ready(voice))">
            <span class="material-symbols-outlined" aria-hidden="true">{{ previewBusy === previewKey(voice) ? 'hourglass_top' : playingId === previewKey(voice) ? 'stop' : 'play_arrow' }}</span>
          </button>
        </div>
        <div v-if="!voice.builtin" class="voice-sample-actions">
          <button type="button" class="voice-text-button muted" :disabled="!sampleAvailable(voice) || !!busy || !!previewBusy" @click="preview(voice, true)">{{ previewBusy === `${voice.id}:sample` ? '加载中…' : playingId === `${voice.id}:sample` ? '停止原样本' : '试听原样本' }}</button>
          <button v-if="isOwner && (['UPLOADED', 'FAILED'].includes(voice.status) || voice.stalled)" type="button" class="voice-text-button" :disabled="!capabilities.configured || !!busy" @click="mutate(voice, 'clone')">{{ busy === `clone:${voice.id}` ? '提交中…' : voice.status === 'UPLOADED' ? '开始克隆训练' : '重试克隆' }}</button>
          <button v-else-if="voice.status === 'CLONING'" type="button" class="voice-text-button" :disabled="!capabilities.configured || !!busy || pollRunning" @click="mutate(voice, 'refresh')">查询训练状态</button>
        </div>
        <div class="ls-voice-foot">
          <span class="voice-role-label">{{ voice.role === 'host' ? '本场主讲' : voice.role === 'cohost' ? '助播音色' : '分配角色' }}</span>
          <div class="ls-role-toggle">
            <button type="button" :disabled="!ready(voice) || !!busy" :class="{ on: voice.role === 'host' }" :aria-pressed="voice.role === 'host'" @click="setRole(voice, 'host')">主播</button>
            <button type="button" :disabled="!ready(voice) || !!busy" :class="{ on: voice.role === 'cohost' }" :aria-pressed="voice.role === 'cohost'" title="自动讲解时和主播轮流讲" @click="setRole(voice, 'cohost')">助播</button>
          </div>
        </div>
        <details v-if="!voice.builtin" class="voice-audit voice-stores">
          <summary>开放门店 · {{ (voice.storeIds || []).length }} 家</summary>
          <template v-if="isOwner">
            <label v-for="store in merchantStores" :key="store.id" class="voice-store-option">
              <input type="checkbox" :checked="(voice.storeIds || []).includes(store.id)" :disabled="!!busy || ((voice.storeIds || []).length === 1 && voice.storeIds.includes(store.id))" @change="toggleStore(voice, store.id, $event.target.checked)" />
              <span>{{ store.name }}</span>
            </label>
            <p>勾选的门店可以用这个声音直播。至少保留一家；不再需要时请删除音色。</p>
          </template>
          <p v-else>由老板决定这个声音开放给哪些门店。</p>
        </details>
        <details v-if="!voice.builtin" class="voice-audit"><summary>授权记录</summary><p>{{ voice.consentText }}</p><dl><dt>确认时间</dt><dd>{{ consentDate(voice.consentAt) }}</dd><dt>操作人</dt><dd>用户 {{ voice.consentBy }}</dd></dl></details>
        <details v-if="voice.providerVoiceId" class="voice-audit"><summary>音色 ID</summary><p class="voice-id">{{ voice.providerVoiceId }}</p></details>
      </article>
      <article v-if="isOwner" class="ls-voice-card ls-clone-card" :class="{ open: formOpen }">
        <button v-if="!formOpen" ref="uploadEntry" type="button" class="ls-clone-entry" :disabled="!storeId || !!busy" @click="openForm">
          <span class="ls-clone-plus" aria-hidden="true">＋</span><strong>新建声音克隆</strong><small>录音或上传，创建专属音色</small>
        </button>
        <form v-else class="ls-clone-form" @submit.prevent="uploadSample(capabilities.configured)" @keydown.esc.prevent="closeForm">
          <div class="voice-form-heading"><strong>新建声音克隆</strong><button type="button" class="voice-refresh" :disabled="!!busy" aria-label="取消新建声音克隆" @click="closeForm"><span class="material-symbols-outlined" aria-hidden="true">close</span></button></div>
          <label class="ls-field"><span>音色名称</span><input ref="nameInput" v-model="name" maxlength="100" required :disabled="!!busy" placeholder="例如：门店老板·亲和" /></label>
          <div class="voice-record-controls">
            <button type="button" class="voice-record-button" :disabled="!!busy || recordingBusy" @click="openRecording"><span class="material-symbols-outlined" aria-hidden="true">mic</span>麦克风录音</button>
          </div>
          <label class="voice-file-source">
            <span class="material-symbols-outlined" aria-hidden="true">upload_file</span><span>{{ file ? file.name : '上传音频文件' }}</span>
            <input ref="fileInput" type="file" accept=".wav,.mp3,.m4a,audio/wav,audio/mpeg,audio/mp4" :disabled="!!busy || recordingBusy" aria-label="上传声音样本" @change="selectFile" />
          </label>
          <audio v-if="sampleUrl" ref="samplePlayer" :src="sampleUrl" class="voice-sample-preview" controls preload="metadata" aria-label="试听待提交样本" />
          <p class="voice-form-hint">WAV / MP3 / M4A · 不超过 10 MB · 5～60 秒<br />建议 10～20 秒清晰单人录音，无背景音乐。录音完成后可试听，再开始克隆训练。</p>
          <p v-if="!capabilities.configured" class="voice-form-hint">语音服务尚未配置，可先保存样本，配置完成后再训练。</p>
          <label class="voice-consent"><input v-model="consent" type="checkbox" :disabled="!!busy" />我确认这是本人声音，或已获得声音所有者授权用于声音克隆与直播播报。</label>
          <div class="voice-form-actions"><button type="button" class="ls-ghost compact" :disabled="!!busy" @click="closeForm">取消</button><button v-if="capabilities.configured" type="button" class="ls-ghost compact" :disabled="!name.trim() || !file || !consent || !!busy || recordingBusy" @click="uploadSample(false)">仅保存样本</button><button type="submit" class="primary-button compact" :disabled="!name.trim() || !file || !consent || !!busy || recordingBusy">{{ busy === 'upload' ? '正在保存…' : capabilities.configured ? '开始克隆训练' : '保存样本' }}</button></div>
        </form>
      </article>
    </div>
    <details v-if="!loading && capabilities.configured" class="voice-service-details"><summary>音色服务说明</summary><p>{{ capabilities.message }} 当前模型：{{ capabilities.model }}。克隆训练由音色服务处理，完成后可试听。</p></details>
  <Teleport to="body">
    <dialog ref="recordingDialog" class="voice-record-dialog" aria-labelledby="voice-record-title" aria-describedby="voice-record-description" @cancel.prevent="closeRecording">
      <div class="voice-record-heading">
        <h3 id="voice-record-title">录制声音样本</h3>
        <button type="button" class="voice-refresh" aria-label="关闭录音弹窗" @click="closeRecording"><span class="material-symbols-outlined" aria-hidden="true">close</span></button>
      </div>
      <p id="voice-record-description" class="voice-record-description">请在安静环境下自然朗读，建议录制 10～20 秒。30 秒自动结束。</p>
      <div class="voice-reading-prompt">
        <span>参考朗读文案</span>
        <p>欢迎来到我们的直播间，我来为大家介绍门店的特色商品。我们会认真解答每一个问题，也希望你能挑到适合自己的好东西。</p>
      </div>
      <div class="voice-record-status" :class="{ recording: recordingState === 'recording' }">
        <span class="material-symbols-outlined" aria-hidden="true">{{ recordingState === 'recording' ? 'mic' : 'mic_none' }}</span>
        <strong>{{ String(Math.floor(recordSeconds / 60)).padStart(2, '0') }}:{{ String(recordSeconds % 60).padStart(2, '0') }}</strong>
        <p role="status">{{ recordingState === 'recording' ? '正在录音，保持自然语速' : recordingState === 'starting' ? '正在打开麦克风…' : recordingState === 'processing' ? '正在处理录音…' : '准备好后，点击开始录音' }}</p>
      </div>
      <p v-if="recordingError" class="voice-error" role="alert">{{ recordingError }}</p>
      <div class="voice-record-footer">
        <button type="button" class="ls-ghost compact" @click="closeRecording">取消</button>
        <button v-if="recordingState === 'recording'" type="button" class="primary-button compact" @click="recorder.stop()">结束录音</button>
        <button v-else type="button" class="primary-button compact" :disabled="recordingBusy" autofocus @click="startRecording">{{ recordingState === 'starting' ? '等待麦克风…' : recordingState === 'processing' ? '正在处理…' : '开始录音' }}</button>
      </div>
    </dialog>
  </Teleport>
  </section>
</template>

<style scoped>
.voice-library { margin-bottom:20px; }
.voice-library .panel-heading { gap:12px; }
.voice-library .ls-step-heading-actions { flex-shrink:0; gap:8px; }
.voice-library .ls-step-heading-actions .primary-button.compact,
.voice-library .voice-form-actions .primary-button.compact,
.voice-library .ls-ghost.compact { min-height:30px; height:30px; padding:0 10px; font-size:12px; }
.voice-refresh { display:grid; place-items:center; width:28px; height:28px; padding:0; border:0; border-radius:4px; color:var(--ink-muted); background:transparent; }
.voice-refresh .material-symbols-outlined { font-size:18px; }
.voice-refresh:hover { background:var(--color-bg-subtle); color:var(--ink); }
.voice-library button:disabled { cursor:not-allowed; opacity:.45; }
.voice-library :is(button,input,summary):focus-visible { outline:2px solid var(--cinnabar); outline-offset:3px; }
.voice-service-note,.voice-error,.voice-notice { margin:10px 0 0; font-size:12px; line-height:1.7; overflow-wrap:anywhere; }
.voice-service-note { color:var(--ink-muted); }
.voice-error { color:var(--red); }.voice-notice { color:var(--green); }
.voice-library .ls-voice-grid { grid-template-columns:repeat(auto-fill,minmax(min(258px,100%),1fr)); align-items:start; }
.voice-library .ls-voice-card { min-width:0; min-height:191px; gap:12px; padding:14px 16px; border-radius:8px; cursor:default; }
.voice-library .ls-voice-card.selected { border-width:1px; border-color:var(--cinnabar); }
.voice-card-meta { display:flex; align-items:center; justify-content:space-between; gap:8px; min-height:19px; }
.voice-kind { padding:2px 5px; border-radius:3px; background:var(--color-bg-subtle); color:var(--ink-muted); font-size:10px; line-height:15px; }
.voice-kind.clone { color:var(--cinnabar); background:color-mix(in srgb,var(--cinnabar) 8%,transparent); }
.voice-manage { display:flex; gap:5px; }
.voice-manage button { display:grid; place-items:center; width:22px; height:22px; padding:0; border:0; border-radius:4px; background:transparent; color:var(--ink-muted); }
.voice-manage button:hover { color:var(--cinnabar); background:var(--color-bg-subtle); }
.voice-manage .material-symbols-outlined { font-size:15px; }
.voice-library .ls-voice-avatar { border-radius:8px; border-color:color-mix(in srgb,var(--cinnabar) 20%,transparent); background:color-mix(in srgb,var(--cinnabar) 9%,var(--night-panel)); color:var(--cinnabar); }
.voice-library .ls-wave { justify-content:space-between; }
.voice-library .ls-wave i { background:var(--line-strong); }
.voice-library .ls-wave.active i { background:var(--cinnabar); }
.voice-library .ls-play { width:27px; height:27px; }
.voice-library .ls-play .material-symbols-outlined { font-size:17px; }
.voice-library .ls-voice-foot { margin-top:auto; }
.voice-role-label { color:var(--ink-muted); font-size:11px; }
.voice-library .ls-role-toggle button { height:27px; padding:0 9px; font-size:11px; border-radius:4px; }
.voice-sample-actions { display:flex; justify-content:space-between; gap:10px; }
.voice-text-button { padding:0; border:0; background:transparent; color:var(--cinnabar); font-size:11px; white-space:nowrap; }
.voice-text-button.muted { color:var(--ink-muted); }
.voice-text-button:hover { text-decoration:underline; }
.voice-audit { font-size:10px; color:var(--ink-muted); }
.voice-audit summary,.voice-service-details summary { cursor:pointer; width:fit-content; }
.voice-store-option { display:flex; align-items:center; gap:6px; padding:3px 0; font-size:11px; color:var(--ink-soft); cursor:pointer; }
.voice-store-option input { margin:0; }
.voice-audit p { line-height:1.8; }.voice-audit dl { display:grid; grid-template-columns:auto 1fr; gap:7px; }.voice-audit dd { margin:0; }
.voice-persona { display:flex; flex-wrap:wrap; align-items:center; gap:12px 28px; margin-top:14px; padding:12px 14px; border-radius:8px; background:var(--color-bg-subtle); }
.voice-persona-name,.voice-persona-style { display:flex; align-items:center; gap:10px; color:var(--ink-soft); font-size:12px; }
.voice-persona-name input { width:150px; height:32px; padding:0 10px; border:1px solid var(--line-strong); border-radius:7px; color:var(--ink); background:var(--night-panel); font-size:12px; }
.voice-persona .ls-tone-options button { height:30px; padding:0 12px; font-size:12px; }
.voice-library input[type=checkbox] { accent-color:var(--cinnabar); }
.voice-library .ls-clone-card { background:transparent; }
.voice-library .ls-clone-entry { min-height:161px; }
.voice-library .ls-clone-card.open { border-color:var(--line-strong); }
.voice-form-heading { display:flex; align-items:center; justify-content:space-between; gap:8px; }
.voice-form-heading strong { font-size:13px; font-weight:500; }
.voice-file-source { position:relative; display:flex; align-items:center; justify-content:center; min-height:52px; gap:8px; padding:10px; border:1px dashed var(--line-strong); border-radius:6px; color:var(--ink-muted); font-size:12px; }
.voice-file-source > span:last-of-type { overflow-wrap:anywhere; min-width:0; }
.voice-file-source .material-symbols-outlined { flex-shrink:0; font-size:20px; }
.voice-file-source input { position:absolute; inset:0; width:100%; height:100%; opacity:0; cursor:pointer; }
.voice-file-source:focus-within { outline:2px solid var(--cinnabar); outline-offset:3px; }
.voice-form-hint { margin:0; color:var(--ink-muted); font-size:11px; line-height:1.8; }
.voice-consent { display:flex; align-items:flex-start; gap:7px; color:var(--ink-muted); font-size:11px; line-height:1.8; }
.voice-consent input { flex-shrink:0; margin-top:4px; }
.voice-form-actions { display:flex; flex-wrap:wrap; justify-content:flex-end; gap:8px; }
.voice-record-button { display:flex; align-items:center; justify-content:center; gap:8px; width:100%; min-height:42px; border:1px solid var(--line-strong); border-radius:6px; color:var(--ink); background:var(--color-bg-subtle); font-size:12px; }
.voice-record-button.recording { color:var(--red); border-color:var(--red); }
.voice-record-button .material-symbols-outlined { font-size:20px; }
.voice-sample-preview { width:100%; height:36px; }
.voice-id { overflow-wrap:anywhere; user-select:all; }
.voice-record-dialog { width:min(520px,calc(100vw - 32px)); max-height:calc(100dvh - 32px); box-sizing:border-box; padding:24px; border:1px solid var(--line-strong); border-radius:14px; color:var(--ink); background:var(--night-panel); box-shadow:0 24px 80px #0003; }
.voice-record-dialog::backdrop { background:rgba(18,27,33,.45); }
.voice-record-heading { display:flex; align-items:center; justify-content:space-between; gap:16px; }
.voice-record-heading h3 { margin:0; font-size:19px; }
.voice-record-description { margin:14px 0 18px; color:var(--ink-muted); font-size:13px; line-height:1.8; }
.voice-reading-prompt { padding:16px 18px; border:1px solid var(--line); border-radius:8px; background:var(--color-bg-subtle); }
.voice-reading-prompt > span { color:var(--ink-muted); font-size:12px; }
.voice-reading-prompt p { margin:8px 0 0; font-size:15px; line-height:2; }
.voice-record-status { display:flex; flex-direction:column; align-items:center; gap:10px; padding:24px 0; }
.voice-record-status > span { color:var(--ink-muted); font-size:32px; }
.voice-record-status.recording > span { color:var(--red); }
.voice-record-status strong { font-size:30px; font-weight:500; font-variant-numeric:tabular-nums; }
.voice-record-status p { margin:0; color:var(--ink-muted); font-size:12px; }
.voice-record-footer { display:flex; justify-content:flex-end; gap:10px; margin-top:16px; }
.voice-record-dialog button:disabled { opacity:.45; cursor:not-allowed; }
.voice-record-dialog button:focus-visible { outline:2px solid var(--cinnabar); outline-offset:3px; }
.voice-rename { display:flex; align-items:center; gap:7px; min-height:36px; }
.voice-rename input { min-width:0; flex:1; padding:7px; border:1px solid var(--line); border-radius:4px; background:var(--night); color:var(--ink); font-size:13px; }
.voice-service-details { margin-top:12px; color:var(--ink-muted); font-size:11px; }
.voice-service-details p { margin:7px 0 0; line-height:1.7; }
@media(max-width:600px) { .voice-library .panel-heading { align-items:flex-start; flex-wrap:wrap; }.voice-library .ls-voice-grid { grid-template-columns:1fr; } }
@media(prefers-reduced-motion:reduce) { .voice-library .ls-wave.active i { animation:none; }.voice-library .ls-voice-card { transition:none; } }
</style>
