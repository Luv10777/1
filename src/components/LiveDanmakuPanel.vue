<script setup>
// 只在桌面端里出现：读取直播间弹幕要靠桌面软件（desktop/），网页版浏览器做不到。
// LiveAudioControl 看到 window.yifangzhiDesktop 才加载这个组件。
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { liveApi } from '../services/liveApi'
import { LiveDanmakuRelay } from '../services/liveDanmakuRelay'
import { desktopBridge } from '../utils/desktop'
import { selectedStoreId } from '../stores/merchantContext'

const props = defineProps({
  sessionId: { type: Number, default: null },
  sessionStatus: { type: String, default: 'DRAFT' },
  online: { type: Boolean, default: false },
  // 用主讲音色回复；还没选好时抛出说明原因的错误。
  voiceInput: { type: Function, required: true },
})
const bridge = desktopBridge.danmaku
// 同一个抖音号的直播间号是固定的：连上过一次就记在这台设备上（按门店分开记），下次不用再粘贴。
const roomKey = () => `yifangzhi-live-room:${selectedStoreId.value ?? ''}`
const recallRoom = () => { try { return localStorage.getItem(roomKey()) || '' } catch { return '' } }
const rememberRoom = (roomId) => { try { localStorage.setItem(roomKey(), roomId) } catch { /* 记不住就下次再粘贴 */ } }
const room = ref(recallRoom())
const state = ref(null)
const lost = ref(0)
// 后端按规则直接放过的：打招呼、刷屏一类不用回的，以及辱骂、引流一类不回的。
const passed = ref(0)
const blocked = ref(0)
const error = ref('')
const resolving = ref(false)
let relay = null
let mounted = true
const onAir = computed(() => Boolean(props.sessionId) && props.sessionStatus === 'LIVE')
const stop = () => { relay?.stop(); relay = null; state.value = null }
const forward = async ({ id, text }) => {
  const sessionId = props.sessionId
  try {
    if (!props.online) throw new Error('本页没有在播报，收到的弹幕没有处理')
    const result = await liveApi.comment(sessionId, { id: `douyin:${id}`, text, ...props.voiceInput() })
    if (!mounted || sessionId !== props.sessionId) return
    if (result.status === 'skipped') passed.value += 1
    else if (result.status === 'blocked') blocked.value += 1
  } catch (reason) {
    if (!mounted || sessionId !== props.sessionId) return
    lost.value += 1
    error.value = reason.message || '弹幕没有送达'
  }
}
const toggle = async () => {
  if (relay) return stop()
  if (resolving.value) return
  const sessionId = props.sessionId
  error.value = ''
  lost.value = 0
  passed.value = 0
  blocked.value = 0
  try {
    if (!onAir.value) throw new Error('请先开始本场')
    if (!props.online) throw new Error('请先点击开始播报')
    props.voiceInput()
    resolving.value = true
    // 粘贴的是分享链接时，软件要去打开它才知道是哪个直播间，要等一两秒。
    const roomId = await bridge.resolveRoom(room.value)
    if (!mounted || sessionId !== props.sessionId || !onAir.value) return
    room.value = roomId
    rememberRoom(roomId)
    relay = new LiveDanmakuRelay({ bridge, roomId, onChat: forward, onChange: value => { if (mounted) state.value = value } })
    relay.start()
  } catch (reason) {
    if (mounted) error.value = reason.message || '没能连接直播间'
  } finally { resolving.value = false }
}
// 换了场次或本场结束：上一场的弹幕不能接着送进来。
watch(() => [props.sessionId, onAir.value], () => { if (relay) stop() })
// 换了门店：换成那家门店记着的直播间。
watch(selectedStoreId, () => { if (!relay) room.value = recallRoom() })
onBeforeUnmount(() => { mounted = false; relay?.stop(); relay = null })
</script>

<template>
  <div class="live-danmaku" :class="{ on: state?.status === 'live' }">
    <div class="live-danmaku-head">
      <div>
        <strong>直播间弹幕<span>试用</span></strong>
        <p>连接后，软件在后台读取你直播间里的弹幕，交给 AI 判断该不该回、怎么回。只读取弹幕文字，不保存观众的昵称和账号；礼物、点赞、进场不处理。</p>
      </div>
      <button type="button" :class="state ? 'ls-ghost compact' : 'primary-button compact'" :disabled="resolving || (!state && !room.trim())" @click="toggle">{{ state ? '断开' : resolving ? '正在查找直播间…' : '连接直播间' }}</button>
    </div>
    <label v-if="!state"><span>直播间分享链接（连上过一次会记住，下次直接点“连接直播间”）</span><input v-model="room" :disabled="resolving" maxlength="500" placeholder="在抖音 App 的直播间点“分享 → 复制链接”，把复制到的内容整段粘贴到这里"></label>
    <p v-if="state?.message" class="live-danmaku-status" role="status">{{ state.message }}</p>
    <p v-if="state" class="live-danmaku-note">已读到 {{ state.received }} 条：交给 AI {{ Math.max(0, state.forwarded - lost - passed - blocked) }} 条，不用回 {{ passed }} 条（打招呼、刷屏一类），已屏蔽 {{ blocked }} 条（辱骂、引流一类），重复或刷屏过密 {{ state.skipped }} 条，未送达 {{ lost }} 条。</p>
    <p v-if="error" class="live-danmaku-error" role="alert">{{ error }}</p>
    <p class="live-danmaku-note">这项功能还在试用：读取方式不是抖音官方接口，抖音网页改版时可能暂时失效。弹幕多的时候优先处理提问、想买和投诉，每 10 分钟最多让 AI 处理 30 条。</p>
  </div>
</template>

<style scoped>
.live-danmaku { display: grid; gap: 10px; margin-top: 16px; padding: 14px 16px; border: 1px solid var(--line); border-radius: 8px; }
.live-danmaku.on { border-color: var(--color-accent); background: color-mix(in srgb, var(--color-accent) 4%, transparent); }
.live-danmaku-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.live-danmaku-head > div { min-width: 0; }
.live-danmaku-head strong { font-size: 13px; font-weight: 600; }
.live-danmaku-head strong span { margin-left: 8px; padding: 1px 6px; border: 1px solid var(--line); border-radius: 4px; color: var(--ink-muted); font-size: 11px; font-weight: 400; }
.live-danmaku-head p { margin: 6px 0 0; color: var(--ink-muted); font-size: 12px; line-height: 1.6; }
.live-danmaku-head button { flex-shrink: 0; }
.live-danmaku label { display: flex; flex-direction: column; gap: 8px; color: var(--ink); font-size: 12px; }
.live-danmaku input { min-width: 0; padding: 10px 12px; border: 1px solid var(--line); border-radius: 6px; color: var(--ink); background: var(--night-panel); font-size: 12px; line-height: 1.6; }
.live-danmaku input::placeholder { color: var(--ink-muted); opacity: .8; }
.live-danmaku-status { margin: 0; color: var(--ink); font-size: 12px; line-height: 1.6; }
.live-danmaku-note { margin: 0; color: var(--ink-muted); font-size: 12px; line-height: 1.6; }
.live-danmaku-error { margin: 0; color: var(--red); font-size: 12px; line-height: 1.6; }
@media (max-width: 560px) {
  .live-danmaku-head { align-items: flex-start; flex-direction: column; }
}
</style>
