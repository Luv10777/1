<script setup>
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowRight, Volume2 } from 'lucide-vue-next'
import { liveApi, describeAutoScript, describePlayback, toSpeechFeedItem } from '../services/liveApi'
import { LivePlayerSession } from '../services/livePlayerSession'
import { inDesktop } from '../utils/desktop'
import { findDesktopDownloads } from '../services/desktopDownload'

const props = defineProps({
  sessionId: { type: Number, default: null },
  storeId: { type: Number, default: null },
  sessionStatus: { type: String, default: 'DRAFT' },
  // 开始本场的结果显示在页面顶部；这里再显示一次，按钮旁边就能看到为什么没开始。
  sessionError: { type: String, default: '' },
  // 开始前还缺的东西（主播音色、商品……）。开始后配置锁定，所以缺一样都不让点开始。
  blockers: { type: Array, default: () => [] },
  ensureSession: { type: Function, required: true },
  hostVoice: { type: Object, default: null },
  products: { type: Array, default: () => [] },
  view: { type: String, default: 'setup', validator: value => ['setup', 'live', 'review'].includes(value) },
})
const emit = defineEmits(['open-workspace', 'start-session', 'edit-step'])
const isDevelopment = import.meta.env.DEV
// 网页版读不到直播间弹幕，要靠桌面端。找得到安装包才给入口；已经在桌面端里就不用再提。
const desktopDownloads = ref({ windows: '', mac: '' })
// 两个系统的安装包都有时，把正在用的这台电脑的那个放在显眼的位置。
const onMac = /Macintosh|Mac OS X/.test(navigator.userAgent)
// 读取直播间弹幕要靠桌面软件，网页版浏览器做不到：只有在桌面端里打开时才加载这一块。
const LiveDanmakuPanel = inDesktop ? defineAsyncComponent(() => import('./LiveDanmakuPanel.vue')) : null
const idlePlayback = () => ({ status: 'idle', connectionError: '', current: null, pending: [], paused: false, loading: false, error: '' })
const busy = ref(false)
const notice = ref('')
const error = ref('')
const script = ref('')
const mode = ref('APPEND')
const mockQuestion = ref('')
const sending = ref(false)
const autoScript = ref(null)
const speechItems = ref([])
const playback = ref(idlePlayback())
const starting = ref(false)
const volume = ref(75)
let player = null
let pairingSessionId = null
let mounted = true
let generation = 0
let poll = null
const ended = computed(() => props.sessionStatus === 'ENDED')
const online = computed(() => playback.value.status === 'connected')
const connecting = computed(() => starting.value || ['connecting', 'reconnecting'].includes(playback.value.status))
const playbackView = computed(() => describePlayback(playback.value))
const autoSummary = computed(() => describeAutoScript(autoScript.value))
const feed = computed(() => speechItems.value.slice(0, 8).map(item => toSpeechFeedItem(item, props.products)))
const voiceInput = () => {
  const id = props.hostVoice?.id
  if (!id) throw new Error('请先在声音区选择已就绪的主讲音色')
  if (String(id).startsWith('sample:')) return { sampleId: Number(String(id).slice(7)) }
  if (String(id).startsWith('builtin:')) return { builtInVoice: String(id).slice(8) }
  throw new Error('当前为旧版演示音色，请重新选择真实音色')
}

/* ---------------- 本页播报 ---------------- */
// 声音就在这个页面播放：向后端要一张本场的播报凭证，连上播报通道。没有另外的设备要配对。
const playerFor = () => {
  if (!player) {
    player = new LivePlayerSession({
      baseUrl: import.meta.env.VITE_API_BASE_URL,
      issueToken: async () => liveApi.pairPlayer(pairingSessionId || props.sessionId || await props.ensureSession()),
      onChange: value => { if (mounted) playback.value = value },
    })
  }
  return player
}
const resetPlayer = () => {
  const previous = player
  player = null
  playback.value = idlePlayback()
  starting.value = false
  previous?.destroy()
}
/** 浏览器只在用户点击时允许出声。点击处理函数里先调它，之后再发请求也不会被拦。 */
const unlockPlayback = () => { playerFor().unlock().catch(() => {}) }
const startPlayback = async (sessionId = null) => {
  if (ended.value) return
  const ticket = generation
  pairingSessionId = sessionId
  starting.value = true
  error.value = ''
  try {
    const session = playerFor()
    await session.start()
    if (mounted && ticket === generation) session.setVolume(volume.value)
  } catch (reason) {
    if (mounted && ticket === generation) error.value = reason.message || '无法开始播报，请重试'
  } finally {
    pairingSessionId = null
    if (mounted && ticket === generation) starting.value = false
  }
}
const togglePause = async () => {
  try { await player?.togglePause() }
  catch (reason) { error.value = reason.message || '操作未完成，请重试' }
}
const retryAudio = () => player?.retry()
watch(volume, value => player?.setVolume(value))
const stopForEnd = async () => {
  await player?.stop()
  if (props.sessionId) await liveApi.revokePlayer(props.sessionId)
}
defineExpose({ stopForEnd, unlockPlayback, startPlayback })

/* ---------------- 讲解状态与播报记录 ---------------- */
const refresh = async () => {
  const id = props.sessionId
  const ticket = generation
  if (!id || !mounted || props.view !== 'live') return
  const [auto, items] = await Promise.allSettled([liveApi.autoScript(id), liveApi.speechItems(id)])
  if (!mounted || ticket !== generation || id !== props.sessionId) return
  // 单次轮询失败时保留上一次的讲解状态，界面不在开与关之间闪动。
  if (auto.status === 'fulfilled') autoScript.value = auto.value
  if (items.status === 'fulfilled') speechItems.value = items.value
}
// 有内容正在生成或合成时查得勤一些，其余时候不打扰后端。
const schedule = () => {
  clearTimeout(poll)
  if (!props.sessionId || !mounted || props.view !== 'live') return
  poll = setTimeout(async () => { await refresh(); schedule() }, feed.value.some(item => item.working) ? 2000 : 5000)
}
watch(() => [props.storeId, props.sessionId], ([store, id], previous = []) => {
  const [previousStore, previousId] = previous
  if (previous.length && (store !== previousStore || (previousId && id !== previousId))) {
    generation++
    busy.value = false
    sending.value = false
    notice.value = ''
    error.value = ''
    script.value = ''
    mockQuestion.value = ''
    mode.value = 'APPEND'
    // 换了场次：上一场的声音不能接着在这里播。
    resetPlayer()
  }
  autoScript.value = null
  speechItems.value = []
  clearTimeout(poll)
  if (id) refresh().then(schedule)
}, { immediate: true })
watch(() => props.view, () => { clearTimeout(poll); if (props.sessionId) refresh().then(schedule) })
const run = async action => {
  if (busy.value || ended.value) return
  busy.value = true; error.value = ''; notice.value = ''
  const ticket = generation
  try { await action(() => mounted && ticket === generation) }
  catch (reason) { if (mounted && ticket === generation) error.value = reason.message || '操作未完成，请重试' }
  finally { if (mounted && ticket === generation) busy.value = false }
}
const test = () => run(async current => {
  if (!online.value) throw new Error('请先点击开始播报')
  // 服务端生成的提示音，不调用语音合成。
  await liveApi.playerCommand(props.sessionId, { id: crypto.randomUUID(), mode: 'APPEND', text: '测试提示音', audioUrl: '/api/player/test-audio.wav', durationMillis: 1000, pauseOffsetsMillis: [] })
  if (current()) notice.value = '测试音已加入队列，约 12 秒。听不到时请检查电脑音量和浏览器的声音权限。'
})
const toggleAuto = () => run(async current => {
  const id = props.sessionId
  if (!id) return
  const next = autoScript.value?.enabled ? await liveApi.stopAutoScript(id) : await liveApi.startAutoScript(id)
  if (!current()) return
  autoScript.value = next
  notice.value = next.enabled ? '自动讲解已开启，第一段正在生成。' : '已停止生成新的讲解，已合成的片段会播完。'
  await refresh()
  schedule()
})
const send = (mock = false) => run(async current => {
  if (!online.value) throw new Error('请先点击开始播报')
  const text = (mock ? mockQuestion.value : script.value).trim()
  if (!text) throw new Error(mock ? '请输入测试弹幕' : '请输入播报内容')
  const voice = voiceInput()
  const id = await props.ensureSession()
  if (!current()) return
  sending.value = true
  try {
    const result = mock
      ? await liveApi.mockComment(id, { id: crypto.randomUUID(), text, ...voice })
      : await liveApi.speech(id, { id: crypto.randomUUID(), mode: mode.value, text, ...voice })
    if (!current()) return
    notice.value = mock
      ? ({
          answering: '已收到。AI 会先判断这条弹幕该不该回、怎么回，结果见上方弹幕流。',
          unanswered: '这条没有处理，原因见上方弹幕流。',
          // 这两类按规则直接放过，不问 AI，也不记入弹幕流。
          skipped: '这条属于打招呼、刷屏一类，不需要回复，没有交给 AI。',
          blocked: '这条含有辱骂、引流或被屏蔽的词，不会回复。',
        })[result.status] || '这条弹幕之前已经处理过，结果见上方弹幕流。'
      : '已提交合成，完成后自动加入播报队列，进度见下方播报记录。'
    await refresh()
    schedule()
  } finally { if (current()) sending.value = false }
})
onMounted(() => { if (!inDesktop) findDesktopDownloads().then((found) => { if (mounted) desktopDownloads.value = found }) })
onBeforeUnmount(() => { mounted = false; generation++; clearTimeout(poll); player?.destroy(); player = null })
</script>

<template>
  <section v-if="view !== 'review'" :id="view === 'setup' ? 'ls-step-launch' : undefined" class="panel ls-section audio-control" :class="{ 'audio-control-live': view === 'live' }">
    <template v-if="view === 'setup'">
      <div class="panel-heading"><div><p class="eyebrow">STEP 03</p><h3>启动直播</h3></div></div>
      <p class="audio-intro">在抖音 App 内用摄像头开播；AI 声音直接在这个网页播放，不需要另外连接设备。</p>
      <p class="audio-help">请按抖音当前规则，在抖音 App 中标识 AI 合成语音。</p>
      <ul v-if="sessionStatus === 'DRAFT' && blockers.length" class="audio-blockers" aria-label="开始前还需要完成">
        <li v-for="item in blockers" :key="item.key"><span>还需要{{ item.label }}</span><button v-if="item.step" type="button" class="audio-text-button" @click="emit('edit-step', item.step)">去完成<ArrowRight :size="13" /></button></li>
      </ul>
      <p v-if="sessionError" class="audio-error" role="alert">{{ sessionError }}</p>
      <div class="audio-launch-footer">
        <p>{{ sessionStatus === 'DRAFT' ? '开始后配置会锁定，并进入直播工作台。' : sessionStatus === 'ENDED' ? '这一场已结束。' : '本场已开始，可在工作台管理播报。' }}</p>
        <button v-if="sessionStatus === 'DRAFT'" type="button" class="primary-button compact" :disabled="!storeId || blockers.length > 0" @click="emit('start-session')">开始本场<ArrowRight :size="15" /></button>
        <button v-else-if="sessionStatus !== 'ENDED'" type="button" class="primary-button compact" :disabled="!storeId" @click="emit('open-workspace')">前往直播工作台<ArrowRight :size="15" /></button>
      </div>
      <div v-if="desktopDownloads.windows || desktopDownloads.mac" class="audio-desktop">
        <div class="audio-desktop-head">
          <div><strong>让 AI 回答直播间弹幕<span>试用</span></strong><p>网页版读不到抖音直播间里的弹幕。装上桌面端、在桌面端里开播，AI 才能看到观众发的弹幕并回答。商品、话术和问答仍在这里配置，桌面端只负责开播。<template v-if="!desktopDownloads.mac">目前只有 Windows 版。</template><template v-else-if="!desktopDownloads.windows">目前只有 Mac 版。</template></p></div>
          <div class="audio-desktop-actions">
            <a class="ls-ghost compact" href="yifangzhi://live">打开桌面端</a>
            <a v-if="desktopDownloads.windows" :class="onMac && desktopDownloads.mac ? 'ls-ghost compact' : 'primary-button compact'" :href="desktopDownloads.windows" download>下载 Windows 版</a>
            <a v-if="desktopDownloads.mac" :class="onMac || !desktopDownloads.windows ? 'primary-button compact' : 'ls-ghost compact'" :href="desktopDownloads.mac" download>下载 Mac 版</a>
          </div>
        </div>
        <p class="audio-help">点“打开桌面端”没有反应，说明这台电脑还没有安装，请先下载。安装包暂时没有数字签名：<template v-if="desktopDownloads.windows">Windows 会提示“未知发布者”，点“更多信息 → 仍要运行”即可。</template><template v-if="desktopDownloads.mac">Mac 第一次打开会被系统拦下，要到“系统设置 → 隐私与安全性”里点“仍要打开”。</template>读取弹幕用的不是抖音官方接口，抖音网页改版时可能暂时失效。</p>
      </div>
    </template>
    <template v-else>
      <div class="audio-live-bar">
        <span class="audio-preparation-icon"><Volume2 :size="18" /></span>
        <div class="audio-live-copy"><strong>{{ inDesktop ? '播报' : '网页播报' }}</strong><span class="audio-status" :class="{ online }"><i />{{ playbackView.label }}</span><small>{{ playbackView.detail }}</small></div>
        <div class="audio-live-actions">
          <button v-if="!online" type="button" class="primary-button compact" :disabled="connecting || ended || !storeId" @click="startPlayback()">{{ connecting ? '正在连接…' : '开始播报' }}</button>
          <template v-else>
            <button type="button" class="audio-text-button" :disabled="busy" @click="test">播放测试音</button>
            <button type="button" class="ls-ghost compact" @click="togglePause">{{ playback.paused ? '继续播报' : '暂停播报' }}</button>
          </template>
        </div>
      </div>
      <label v-if="online" class="audio-volume"><span>音量</span><input v-model.number="volume" type="range" min="0" max="100" aria-label="播报音量"><span class="mono">{{ volume }}%</span></label>
      <p v-if="playback.error" class="audio-error" role="alert">{{ playback.error }} <button type="button" class="audio-text-button" @click="retryAudio">重新加载音频</button></p>
      <div class="audio-auto" :class="{ on: autoScript?.enabled }">
        <div class="audio-auto-head">
          <div><strong>自动讲解</strong><p>{{ autoSummary }}</p></div>
          <button type="button" :class="autoScript?.enabled ? 'ls-ghost compact' : 'primary-button compact'" :disabled="busy || ended || (!autoScript?.enabled && sessionStatus !== 'LIVE')" @click="toggleAuto">{{ autoScript?.enabled ? '停止自动讲解' : '开启自动讲解' }}</button>
        </div>
        <p v-if="!autoScript?.enabled && sessionStatus !== 'LIVE' && !ended" class="audio-help">开始本场后可以开启。</p>
        <p v-else-if="autoScript?.enabled && !online" class="audio-help">本页还没有开始播报：会先备好 {{ autoScript.targetBuffer }} 段，点击上方“开始播报”后播放。</p>
        <p v-if="autoScript?.lastError" class="audio-error" role="alert">{{ autoScript.lastError }}</p>
        <p v-if="autoScript?.hint" class="audio-warning" role="status">{{ autoScript.hint }}</p>
      </div>
      <component :is="LiveDanmakuPanel" v-if="LiveDanmakuPanel" :session-id="sessionId" :session-status="sessionStatus" :online="online" :voice-input="voiceInput" />
      <details class="audio-secondary">
        <summary>临时播报<span>手动补充一段讲解</span></summary>
        <div class="audio-secondary-body">
          <label class="audio-field"><span>播报内容 · {{ hostVoice?.name || '请先选择主讲音色' }}</span><textarea v-model="script" :disabled="busy || ended" rows="3" maxlength="1000" placeholder="填写经过确认的商品讲解，每次最多 1000 字。" /></label>
          <div class="speech-actions"><small>{{ script.length }} / 1000</small><select v-model="mode" :disabled="busy || ended" aria-label="播报方式"><option value="APPEND">追加到队尾</option><option value="INTERRUPT">停顿处插播</option><option value="CLEAR_REPLAY">清空后播报本段</option></select><button type="button" class="primary-button compact" :disabled="busy || !online || ended || !script.trim()" @click="send(false)">{{ sending ? '提交中…' : '合成并加入播报' }}</button></div>
          <p class="audio-help">插播会等待合适的停顿；剩余不足 5 秒时等当前段落播完。每段合成完成后开始播放。</p>
        </div>
      </details>
      <details class="audio-secondary" open>
        <summary>播报记录<span>{{ feed.length ? `最近 ${feed.length} 条` : '还没有播报' }}</span></summary>
        <div class="audio-secondary-body">
          <p v-if="!feed.length" class="audio-help">提交或自动生成的内容会显示在这里，包括合成进度和未完成的原因。</p>
          <ul v-else class="audio-feed">
            <li v-for="item in feed" :key="item.id" :class="item.tone">
              <div class="audio-feed-head"><strong>{{ item.title }}</strong><span>{{ item.stateLabel }}</span></div>
              <p v-if="item.text">{{ item.text }}</p>
              <p v-if="item.error" class="audio-feed-error">{{ item.error }}</p>
            </li>
          </ul>
        </div>
      </details>
      <details v-if="isDevelopment" class="audio-secondary audio-developer">
        <summary>开发测试<span>模拟弹幕互动（Mock）</span></summary>
        <div class="audio-secondary-body"><p class="audio-help">未连接抖音，在这里手动输入一条观众弹幕。AI 先判断它是哪一类：提问就依据本场问答和商品资料用口语回答，资料里没有的不回答；想买就顺势引导下单；投诉、身体不适、退款不由 AI 回，标出来等人处理；夸奖、闲聊不回，问主播是不是真人或 AI 的也一律不回。回复合成后在句子之间插播。</p><label class="audio-field"><span>测试问题</span><input v-model="mockQuestion" :disabled="busy || ended" maxlength="500" placeholder="输入一条观众可能会发的弹幕"></label><button type="button" class="ls-ghost compact" :disabled="busy || !online || ended || !mockQuestion.trim()" @click="send(true)">发送测试弹幕</button></div>
      </details>
    </template>
    <p v-if="notice" class="audio-notice" role="status">{{ notice }}</p>
    <p v-if="error" class="audio-error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.audio-control { margin-top: 20px; }
.audio-control .panel-heading { align-items: flex-start; }
.audio-status { display: inline-flex; align-items: center; gap: 6px; color: var(--ink-muted); font-size: 12px; white-space: nowrap; }
.audio-status i { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.audio-status.online { color: var(--green); }
.audio-intro { margin: 12px 0 20px; color: var(--ink-muted); font-size: 13px; line-height: 1.8; }
.audio-preparation-icon { display: grid; place-items: center; flex: 0 0 36px; width: 36px; height: 36px; border-radius: 8px; background: var(--color-bg-subtle); color: var(--ink-muted); }
.audio-text-button { display: inline-flex; align-items: center; justify-content: center; gap: 4px; padding: 4px 0; border: 0; background: transparent; color: var(--color-accent-text); font-size: 12px; white-space: nowrap; }
.audio-text-button:hover { color: var(--color-accent-hover); }
.audio-blockers { display: grid; gap: 6px; margin: 14px 0 0; padding: 12px 14px; border-radius: 8px; background: color-mix(in srgb, var(--amber) 8%, transparent); list-style: none; }
.audio-blockers li { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: var(--ink); font-size: 12px; }
.audio-launch-footer { display: flex; align-items: center; justify-content: space-between; gap: 18px; padding-top: 18px; }
.audio-launch-footer p { margin: 0; color: var(--ink-muted); font-size: 12px; }
.audio-desktop { margin-top: 18px; padding: 14px 16px; border: 1px solid var(--line); border-radius: 8px; }
.audio-desktop-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.audio-desktop-head > div:first-child { min-width: 0; }
.audio-desktop-head strong { font-size: 13px; font-weight: 600; }
.audio-desktop-head strong span { margin-left: 8px; padding: 1px 6px; border: 1px solid var(--line); border-radius: 4px; color: var(--ink-muted); font-size: 11px; font-weight: 400; }
.audio-desktop-head p { margin: 6px 0 0; color: var(--ink-muted); font-size: 12px; line-height: 1.6; }
.audio-desktop-actions { display: flex; flex-shrink: 0; flex-wrap: wrap; gap: 10px; }
.audio-desktop-actions a { display: inline-flex; align-items: center; text-decoration: none; white-space: nowrap; }
.audio-control-live { padding: 18px 22px; }
.audio-live-bar { display: flex; align-items: center; gap: 12px; }
.audio-live-copy { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 12px; flex: 1; }
.audio-live-copy small { width: 100%; color: var(--ink-muted); font-size: 11px; line-height: 1.6; overflow-wrap: anywhere; }
.audio-live-actions { display: flex; align-items: center; flex-shrink: 0; gap: 14px; }
.audio-volume { display: flex; align-items: center; gap: 10px; margin: 12px 0 0 48px; color: var(--ink-muted); font-size: 12px; }
.audio-volume input { flex: 1; max-width: 220px; accent-color: var(--color-accent); }
.audio-secondary { margin-top: 16px; border-top: 1px solid var(--line); padding-top: 14px; }
.audio-secondary > summary { color: var(--ink); font-size: 12px; cursor: pointer; }
.audio-secondary > summary span { margin-left: 10px; color: var(--ink-muted); font-size: 11px; }
.audio-secondary-body { padding-top: 16px; }
.audio-developer > summary { color: var(--ink-muted); font-size: 11px; }
.audio-developer .audio-secondary-body > button { margin-top: 12px; }
.audio-field { display: flex; flex-direction: column; gap: 8px; color: var(--ink); font-size: 12px; }
.audio-field > span { display: flex; align-items: center; gap: 6px; }
.audio-field > span small { color: var(--ink-muted); font-size: 11px; }
.audio-field input, .audio-field textarea, .speech-actions select { min-width: 0; width: 100%; padding: 10px 12px; border: 1px solid var(--line); border-radius: 6px; color: var(--ink); background: var(--night-panel); font-size: 12px; line-height: 1.6; }
.audio-field input::placeholder, .audio-field textarea::placeholder { color: var(--ink-muted); opacity: .8; }
.audio-field textarea { resize: vertical; min-height: 90px; }
.speech-actions { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 10px; margin-top: 10px; }
.speech-actions small { margin-right: auto; color: var(--ink-muted); font-size: 11px; }
.speech-actions select { width: auto; padding: 7px 10px; min-height: 36px; }
.audio-help { margin: 8px 0 0; color: var(--ink-muted); font-size: 12px; line-height: 1.8; }
.audio-secondary-body > .audio-help:first-child { margin: 0 0 14px; }
.audio-notice, .audio-error { margin: 14px 0 0; font-size: 12px; line-height: 1.8; }
.audio-notice { color: var(--green); }
.audio-error { color: var(--red); }
.audio-warning { margin: 14px 0 0; color: var(--amber); font-size: 12px; line-height: 1.8; }
.audio-auto { margin-top: 16px; padding: 14px 16px; border: 1px solid var(--line); border-radius: 8px; }
.audio-auto.on { border-color: var(--color-accent); background: color-mix(in srgb, var(--color-accent) 4%, transparent); }
.audio-auto-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.audio-auto-head > div { min-width: 0; }
.audio-auto-head strong { font-size: 13px; font-weight: 600; }
.audio-auto-head p { margin: 6px 0 0; color: var(--ink-muted); font-size: 12px; line-height: 1.6; }
.audio-auto-head button { flex-shrink: 0; }
.audio-auto > .audio-help, .audio-auto > .audio-error, .audio-auto > .audio-warning { margin-top: 10px; }
.audio-feed { display: grid; gap: 10px; margin: 0; padding: 0; list-style: none; max-height: 320px; overflow-y: auto; }
.audio-feed li { padding: 10px 12px; border-left: 2px solid var(--line); background: var(--color-bg-canvas); border-radius: 0 6px 6px 0; }
.audio-feed li.working { border-left-color: var(--amber); }
.audio-feed li.ready { border-left-color: var(--color-accent); }
.audio-feed li.failed { border-left-color: var(--red); }
.audio-feed-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.audio-feed-head strong { min-width: 0; font-size: 12px; font-weight: 600; overflow-wrap: anywhere; }
.audio-feed-head span { flex-shrink: 0; color: var(--ink-muted); font-size: 11px; }
.audio-feed li.working .audio-feed-head span { color: var(--amber); }
.audio-feed li.failed .audio-feed-head span { color: var(--red); }
.audio-feed p { margin: 6px 0 0; color: var(--ink-soft); font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.audio-feed .audio-feed-error { color: var(--red); }
@media (max-width: 560px) {
  .audio-control .panel-heading { flex-wrap: wrap; }
  .audio-launch-footer { align-items: flex-start; flex-direction: column; }
  .audio-launch-footer > button { width: 100%; }
  .audio-desktop-head { align-items: flex-start; flex-direction: column; }
  .audio-auto-head { align-items: flex-start; flex-direction: column; }
  .audio-live-bar { flex-wrap: wrap; }
  .audio-live-actions { margin-left: 48px; }
  .audio-secondary > summary span { display: block; margin: 6px 0 0 15px; }
}
</style>
