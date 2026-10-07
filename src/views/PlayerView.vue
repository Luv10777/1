<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { BrowserAudioPlayer } from '../services/audioPlayer.js'
import { AUDIO_DEDUP_HISTORY_LIMIT } from '../domain/audioPlaybackQueue.js'

const route = useRoute()
const token = ref(typeof route.query.token === 'string' ? route.query.token.trim() : '')
function scrubPairingUrl() {
  const url = new URL(window.location.href)
  if (!url.searchParams.has('token')) return
  url.searchParams.delete('token')
  window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`)
}
scrubPairingUrl()
const status = ref('idle')
const statusMessage = ref('点击开始播报，连接本场音频队列。')
const started = ref(false)
const sessionName = ref('本场直播')
const heartbeatAt = ref(null)
const player = ref({ current: null, pending: [], positionMillis: 0, paused: false, loading: false, error: '', audioState: 'uninitialized' })
const volume = ref(75)
const muted = ref(false)
const wakeLockState = ref('未开启')
const connectionError = ref('')
let socket = null
let reconnectTimer = null
let heartbeatTimer = null
let refreshTimer = null
let handshakeTimer = null
let retryCount = 0
let lastHeartbeat = 0
let intentionalDisconnect = false
let destroyed = false
let wakeLock = null
let wakeLockPending = false
const pendingAcks = new Set()

const send = (message) => {
  if (socket?.readyState !== WebSocket.OPEN) return false
  try { socket.send(JSON.stringify(message)); return true }
  catch { return false }
}
const complete = (id) => {
  pendingAcks.add(id)
  while (pendingAcks.size > AUDIO_DEDUP_HISTORY_LIMIT) pendingAcks.delete(pendingAcks.values().next().value)
  send({ type: 'ack', id })
}
let engine = new BrowserAudioPlayer({ onChange: value => { player.value = value }, onComplete: complete, audioBaseUrl: import.meta.env.VITE_API_BASE_URL, getMediaToken: () => token.value })
const connectionLabel = computed(() => ({ idle: '未连接', connecting: '连接中', connected: '已连接', reconnecting: '重连中', disconnected: '已断开', invalid: '配对已失效' }[status.value]))
const connectionTone = computed(() => ({ connected: 'success', connecting: 'warning', reconnecting: 'warning', disconnected: 'danger', invalid: 'danger' }[status.value] || 'muted'))
const currentPercent = computed(() => player.value.current ? Math.min(100, player.value.positionMillis / player.value.current.durationMillis * 100) : 0)
const formatSeconds = (ms) => `${Math.floor(ms / 1000)}s`
const currentTimeLabel = computed(() => player.value.current ? `${formatSeconds(player.value.positionMillis)} / ${formatSeconds(player.value.current.durationMillis)}` : '—')
const formattedHeartbeat = computed(() => heartbeatAt.value ? new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(heartbeatAt.value) : '—')
const modeLabel = (mode) => ({ APPEND: '常规讲解', INTERRUPT: '互动回复', CLEAR_REPLAY: '重新播报' }[mode] || '讲解')
const isAudioSuspended = computed(() => started.value && player.value.audioState !== 'running' && !player.value.paused)

function clearConnectionTimers() {
  clearTimeout(reconnectTimer)
  clearTimeout(handshakeTimer)
  clearInterval(heartbeatTimer)
}
function connect(reconnecting = false) {
  if (!token.value || destroyed) return
  clearConnectionTimers()
  const previous = socket
  socket = null
  previous?.close(1000, 'reconnecting')
  intentionalDisconnect = false
  status.value = reconnecting ? 'reconnecting' : 'connecting'
  statusMessage.value = reconnecting ? '连接中断，正在恢复音频队列…' : '正在验证配对链接…'
  const url = new URL('/api/player/ws', import.meta.env.VITE_API_BASE_URL || window.location.origin)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.searchParams.set('token', token.value)
  const currentSocket = new WebSocket(url.href)
  socket = currentSocket
  handshakeTimer = setTimeout(() => {
    if (socket === currentSocket && status.value !== 'connected') currentSocket.close(4000, 'handshake timeout')
  }, 12000)
  currentSocket.onopen = () => {
    if (socket !== currentSocket) return
    lastHeartbeat = Date.now()
    send({ type: 'heartbeat' })
    heartbeatTimer = setInterval(() => {
      if (Date.now() - lastHeartbeat > 30000) currentSocket.close(4000, 'heartbeat timeout')
      else send({ type: 'heartbeat' })
    }, 10000)
  }
  currentSocket.onmessage = (event) => {
    if (socket !== currentSocket) return
    try {
      const message = JSON.parse(event.data)
      if (message.type === 'snapshot') {
        clearTimeout(handshakeTimer)
        status.value = 'connected'
        statusMessage.value = '已连接本场直播，等待或播放下发的音频。'
        sessionName.value = message.sessionName || '本场直播'
        connectionError.value = ''
        retryCount = 0
        lastHeartbeat = Date.now()
        const outstanding = new Set((message.commands || []).map(command => command.id))
        for (const id of pendingAcks) {
          if (outstanding.has(id)) send({ type: 'ack', id })
          else pendingAcks.delete(id)
        }
        for (const command of message.commands || []) engine.enqueue(command)
        send({ type: 'heartbeat' })
      } else if (message.type === 'command') engine.enqueue(message.command)
      else if (message.type === 'heartbeat') {
        lastHeartbeat = Date.now()
        heartbeatAt.value = new Date(lastHeartbeat)
      } else if (message.type === 'error') connectionError.value = message.message || '播报连接出现异常，请重试。'
    } catch (error) {
      connectionError.value = error.message || '无法读取播报指令。'
    }
  }
  currentSocket.onclose = async (event) => {
    if (socket !== currentSocket || destroyed) return
    socket = null
    clearConnectionTimers()
    if (intentionalDisconnect) return
    if (event.code === 1008) {
      status.value = 'invalid'
      statusMessage.value = '配对链接已失效，请在直播配置页重新生成。'
      await engine.pause()
      await releaseWakeLock()
      return
    }
    status.value = 'reconnecting'
    statusMessage.value = '连接已中断，正在重连；已缓存的音频仍可继续播放。'
    const delay = Math.min(15000, 1000 * 2 ** Math.min(retryCount++, 4))
    reconnectTimer = setTimeout(() => connect(true), delay)
  }
  currentSocket.onerror = () => {
    if (socket === currentSocket) statusMessage.value = '暂时无法连接播报服务，正在尝试恢复。'
  }
}

async function requestWakeLock() {
  if (document.visibilityState !== 'visible' || wakeLock || wakeLockPending || !started.value || intentionalDisconnect || status.value === 'invalid') return
  if (!('wakeLock' in navigator)) { wakeLockState.value = '浏览器不支持，请手动保持亮屏'; return }
  try {
    wakeLockPending = true
    const lock = await navigator.wakeLock.request('screen')
    if (destroyed || !started.value || intentionalDisconnect) { await lock.release(); return }
    wakeLock = lock
    wakeLockState.value = '已保持亮屏'
    lock.addEventListener('release', () => {
      if (wakeLock === lock) wakeLock = null
      wakeLockState.value = '已释放，请保持前台'
    })
  } catch { wakeLockState.value = '未能保持亮屏，请手动设置' }
  finally { wakeLockPending = false }
}
async function releaseWakeLock() {
  const lock = wakeLock
  wakeLock = null
  await lock?.release().catch(() => {})
}
async function startPlayer() {
  if (!token.value) { connectionError.value = '缺少配对链接，请从直播配置页重新打开播报页。'; return }
  connectionError.value = ''
  try {
    await engine.unlock()
    started.value = true
    intentionalDisconnect = false
    engine.setVolume(muted.value ? 0 : volume.value / 100)
    requestWakeLock()
    if (!socket || socket.readyState > WebSocket.OPEN) connect(status.value !== 'idle')
  } catch (error) { connectionError.value = error.message }
}
async function togglePause() {
  try {
    if (player.value.paused || isAudioSuspended.value) { await engine.resume(); requestWakeLock() }
    else await engine.pause()
  } catch (error) { connectionError.value = error.message }
}
async function disconnect() {
  intentionalDisconnect = true
  clearConnectionTimers()
  const old = socket
  socket = null
  old?.close(1000, 'player closed')
  status.value = 'disconnected'
  statusMessage.value = '播报已断开。重新连接后会从保留的位置继续。'
  await engine.pause()
  await releaseWakeLock()
}
const onVisibilityChange = () => {
  if (document.visibilityState === 'visible') requestWakeLock()
}
const onOnline = () => { if (started.value && !intentionalDisconnect && status.value === 'reconnecting') connect(true) }
watch([volume, muted], () => engine.setVolume(muted.value ? 0 : volume.value / 100))
watch(() => route.query.token, async (nextToken) => {
  token.value = typeof nextToken === 'string' ? nextToken.trim() : ''
  scrubPairingUrl()
  await disconnect()
  await engine.destroy()
  pendingAcks.clear()
  engine = new BrowserAudioPlayer({ onChange: value => { player.value = value }, onComplete: complete, audioBaseUrl: import.meta.env.VITE_API_BASE_URL, getMediaToken: () => token.value })
  player.value = engine.snapshot()
  started.value = false
  status.value = 'idle'
  heartbeatAt.value = null
  connectionError.value = ''
  statusMessage.value = '配对链接已更新，请点击开始播报。'
})
onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  window.addEventListener('online', onOnline)
  // Rendering reads AudioContext time; it never advances a synthetic clock.
  refreshTimer = setInterval(() => { player.value = engine.snapshot() }, 100)
})
onBeforeUnmount(() => {
  destroyed = true
  clearInterval(refreshTimer)
  disconnect()
  engine.destroy()
  document.removeEventListener('visibilitychange', onVisibilityChange)
  window.removeEventListener('online', onOnline)
})
</script>

<template>
  <div class="player-page">
    <header class="player-topbar">
      <div class="player-brand"><span class="player-brand-mark"><img src="/images/brand/yifangzhi-mark.png" alt="" /></span><span><strong>一方志</strong><small>AI 直播播报页</small></span></div>
      <div class="player-topbar-meta"><span class="player-live-dot" /> {{ sessionName }}</div>
    </header>
    <main class="player-main">
      <section class="player-hero"><div><p class="eyebrow">LIVE AUDIO PLAYER</p><h1>直播声音播报</h1><p class="player-lead">此页面仅播放本场直播的音频。请在直播配置页选择声音接法，并由另一台设备进入直播间检查实际收音。</p></div></section>
      <section class="player-notice warning" role="note"><span class="material-symbols-outlined">screen_lock_portrait</span><span>请保持此页面在前台运行，不要锁屏。切换到抖音或其他应用可能中断声音；同机后台播报与配件兼容性需要实机测试。</span></section>
      <p v-if="!token" class="player-error" role="alert">缺少配对链接。为保护本场访问凭证，刷新后需要从直播配置页重新打开播报页。</p>
      <p v-if="connectionError" class="player-error" role="alert">{{ connectionError }}</p>
      <section class="player-grid">
        <article class="player-card player-status-card">
          <div class="player-card-heading"><div><p class="eyebrow">CONNECTION</p><h2>播报连接</h2></div><span class="player-status-pill" :class="connectionTone"><i />{{ connectionLabel }}</span></div>
          <p class="player-status-copy" role="status">{{ statusMessage }}</p>
          <dl class="player-status-details"><div><dt>本场直播</dt><dd>{{ sessionName }}</dd></div><div><dt>最近服务端心跳</dt><dd>{{ formattedHeartbeat }}</dd></div><div><dt>屏幕状态</dt><dd>{{ wakeLockState }}</dd></div></dl>
          <div class="player-status-actions"><button v-if="status !== 'connected'" type="button" class="player-button primary player-start" :disabled="!token || status === 'connecting' || status === 'invalid'" @click="startPlayer">{{ !started ? '开始播报' : '重新连接' }}</button><button v-else type="button" class="player-button secondary" @click="disconnect">断开连接</button></div>
          <p class="player-footnote">首次播放需要你点击按钮授权。已连接只表示播报页在线，不能证明观众已听到声音。</p>
        </article>
        <article class="player-card player-now-card">
          <div class="player-card-heading"><div><p class="eyebrow">NOW PLAYING</p><h2>正在播报</h2></div><span class="player-wave" :class="{ active: player.current && !player.paused && !player.loading && player.audioState === 'running' }"><i v-for="n in 5" :key="n" :style="{ animationDelay: `${n * 70}ms` }" /></span></div>
          <div v-if="player.current" class="player-now-content"><span class="player-now-label">{{ modeLabel(player.current.mode) }}</span><p class="player-current-text">{{ player.current.text || '本场音频' }}</p><div class="player-progress"><div class="player-progress-track" role="progressbar" :aria-valuenow="Math.round(currentPercent)" aria-valuemin="0" aria-valuemax="100" aria-label="音频播放进度"><span :style="{ width: `${currentPercent}%` }" /></div><div><span>{{ currentTimeLabel }}</span><span>{{ player.loading ? '正在缓冲' : player.paused ? '已暂停' : isAudioSuspended ? '浏览器已暂停声音' : '播放中' }}</span></div></div></div>
          <div v-else class="player-empty-state"><span class="material-symbols-outlined">graphic_eq</span><p>{{ started ? '等待本场音频' : '点击开始播报以连接' }}</p><small>收到讲解或互动回复后，将自动加入队列。</small></div>
          <p v-if="player.error" class="player-error" role="alert">{{ player.error }} <button type="button" class="player-text-button" @click="engine.retry()">重新加载音频</button></p>
          <div class="player-volume"><span class="material-symbols-outlined">{{ muted || volume === 0 ? 'volume_off' : 'volume_up' }}</span><input v-model.number="volume" type="range" min="0" max="100" aria-label="播报音量" @input="muted = false" /><span class="mono">{{ muted ? 0 : volume }}%</span><button type="button" class="player-text-button" @click="muted = !muted">{{ muted ? '取消静音' : '静音' }}</button></div>
        </article>
      </section>
      <section class="player-card player-queue-card">
        <div class="player-card-heading"><div><p class="eyebrow">AUDIO QUEUE</p><h2>待播队列</h2></div><span class="player-queue-count">{{ player.pending.length }} 条待播</span></div>
        <p class="player-footnote">互动回复在可用的安静停顿处插入，播完后继续原讲解；剩余不足 5 秒时，等待当前段结束。</p>
        <ol v-if="player.pending.length" class="player-queue-list"><li v-for="(item, index) in player.pending" :key="item.id"><span class="player-queue-index">{{ String(index + 1).padStart(2, '0') }}</span><span class="player-queue-type">{{ item.offsetMillis > 0 ? '续播' : item.mode === 'INTERRUPT' ? '互动' : '讲解' }}</span><div><p>{{ item.text || '本场音频' }}</p></div><span class="player-queue-duration">{{ formatSeconds(item.durationMillis - item.offsetMillis) }}</span></li></ol>
        <div v-else class="player-queue-empty">暂无待播内容</div>
      </section>
      <section class="player-bottom-bar"><div><span class="player-bottom-label">播放控制</span><strong>{{ player.paused ? '播报已暂停' : player.loading ? '音频正在缓冲' : player.current ? '正在播放本场音频' : '等待音频' }}</strong></div><div class="player-bottom-actions"><button type="button" class="player-button primary" :disabled="!started || status === 'invalid'" @click="togglePause">{{ player.paused || isAudioSuspended ? '继续播报' : '暂停播报' }}</button></div></section>
      <p class="player-compliance">本场直播包含 AI 合成语音，请按平台规定进行标识。页面不能检测配件连接状态或观众端收音效果。</p>
    </main>
  </div>
</template>

<style scoped>
.player-error { color:var(--red); font-size:12px; line-height:1.8; }
.player-start { min-height:46px; min-width:180px; }
.player-current-text { margin-top:12px!important; font-size:15px!important; }
.player-page { min-height: 100dvh; background: var(--night); color: var(--ink); }
.player-topbar { min-height: 62px; display:flex; align-items:center; justify-content:space-between; padding: 0 clamp(20px, 5vw, 72px); border-bottom: 1px solid var(--line); background: color-mix(in srgb, var(--night-panel) 92%, transparent); }
.player-brand { display:flex; align-items:center; gap:10px; }
.player-brand-mark { display:grid; place-items:center; width:31px; height:31px; }
.player-brand-mark img { width:100%; height:100%; object-fit:contain; }
.player-brand strong { display:block; font: 600 17px/1.1 var(--font-serif); letter-spacing:.08em; }
.player-brand small { display:block; margin-top:4px; color:var(--ink-muted); font-size:10px; }
.player-topbar-meta { display:flex; align-items:center; gap:8px; color:var(--ink-muted); font-size:11px; }
.player-live-dot { width:7px; height:7px; border-radius:50%; background:var(--cinnabar); box-shadow:0 0 0 3px color-mix(in srgb, var(--cinnabar) 16%, transparent); }
.player-main { width:min(1080px, calc(100% - 36px)); margin:0 auto; padding:44px 0 56px; }
.player-hero { display:grid; grid-template-columns:minmax(0, 1fr) minmax(280px, 360px); gap:28px; align-items:end; margin-bottom:20px; }
.player-hero h1 { margin:10px 0 12px; font:600 clamp(26px,4vw,38px)/1.25 var(--font-serif); letter-spacing:.01em; }
.player-lead { max-width:590px; margin:0; color:var(--ink-muted); font-size:13px; line-height:1.8; }
.player-pair-card { padding:18px; border:1px solid var(--line); border-radius:8px; background:var(--night-panel); box-shadow:var(--shadow-paper); }
.player-pair-head { display:flex; align-items:center; justify-content:space-between; color:var(--ink-muted); font-size:11px; }
.player-token-status { padding:3px 8px; border-radius:99px; font-size:10px; }
.player-token-status.success { color:var(--green); background:color-mix(in srgb, var(--green) 10%, transparent); }
.player-token-status.warning { color:var(--amber); background:color-mix(in srgb, var(--amber) 10%, transparent); }
.player-token-status.danger { color:var(--red); background:color-mix(in srgb, var(--red) 10%, transparent); }
.player-token-status.muted { color:var(--ink-muted); background:var(--color-bg-subtle); }
.player-token { display:block; margin:14px 0 15px; font:500 20px var(--font-mono); letter-spacing:.05em; }
.player-pair-actions { display:flex; align-items:center; gap:10px; }
.player-pair-hint { color:var(--ink-muted); font-size:10px; }
.player-notice { display:flex; align-items:center; gap:9px; min-height:44px; padding:0 14px; margin-bottom:18px; border:1px solid var(--line); border-radius:6px; color:var(--ink-muted); font-size:12px; background:var(--color-bg-subtle); }
.player-notice.success { color:var(--green); border-color:color-mix(in srgb, var(--green) 20%, var(--line)); }
.player-notice.warning { color:var(--amber); border-color:color-mix(in srgb, var(--amber) 20%, var(--line)); }
.player-grid { display:grid; grid-template-columns: 1fr 1fr; gap:18px; }
.player-card { padding:22px; border:1px solid var(--line); border-radius:8px; background:var(--night-panel); box-shadow:var(--shadow-paper); }
.player-card-heading { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; }
.player-card-heading h2 { margin:7px 0 0; font:500 18px/1.25 var(--font-serif); }
.player-status-pill { display:flex; align-items:center; gap:6px; padding:5px 8px; border-radius:99px; font-size:10px; white-space:nowrap; }
.player-status-pill i { display:block; width:6px; height:6px; border-radius:50%; background:currentColor; }
.player-status-pill.success { color:var(--green); background:color-mix(in srgb, var(--green) 10%, transparent); }
.player-status-pill.warning { color:var(--amber); background:color-mix(in srgb, var(--amber) 10%, transparent); }
.player-status-pill.danger { color:var(--red); background:color-mix(in srgb, var(--red) 10%, transparent); }
.player-status-pill.muted { color:var(--ink-muted); background:var(--color-bg-subtle); }
.player-status-copy { min-height:42px; margin:18px 0 0; color:var(--ink-muted); font-size:12px; line-height:1.7; }
.player-status-details { display:grid; grid-template-columns:repeat(3, 1fr); gap:10px; margin:20px 0; }
.player-status-details div { padding:10px; border:1px solid var(--line); border-radius:5px; background:var(--color-bg-canvas); }
.player-status-details dt { color:var(--ink-muted); font-size:10px; }
.player-status-details dd { margin:7px 0 0; font-size:11px; }
.player-status-actions { display:flex; gap:9px; }
.player-footnote { margin:17px 0 0; color:var(--ink-muted); font-size:10px; line-height:1.7; }
.player-button { display:inline-flex; align-items:center; justify-content:center; min-height:36px; padding:0 14px; border-radius:5px; font-size:12px; font-weight:500; transition:background .18s, border-color .18s; }
.player-button.primary { color:var(--color-on-accent); border:1px solid var(--cinnabar); background:var(--cinnabar); }
.player-button.primary:hover { background:var(--color-accent-hover); }
.player-button.secondary { color:var(--ink); border:1px solid var(--line-strong); background:transparent; }
.player-button.secondary:hover { background:var(--color-bg-subtle); }
.player-button:disabled { opacity:.45; cursor:not-allowed; }
.player-now-card { display:flex; min-height:280px; flex-direction:column; }
.player-wave { display:flex; align-items:center; gap:3px; height:22px; }
.player-wave i { width:3px; height:8px; border-radius:2px; background:var(--line-strong); }
.player-wave.active i { animation: player-wave 700ms ease-in-out infinite alternate; background:var(--cinnabar); }
.player-wave.active i:nth-child(2n) { animation-duration:520ms; }
@keyframes player-wave { from { height:6px; } to { height:20px; } }
.player-now-content { flex:1; padding-top:29px; }
.player-now-label { color:var(--cinnabar); font:10px var(--font-mono); letter-spacing:.08em; }
.player-now-content h3 { margin:10px 0 8px; font-size:20px; font-weight:500; }
.player-now-content p { max-width:450px; margin:0; color:var(--ink-muted); font-size:12px; line-height:1.8; }
.player-progress { margin-top:28px; }
.player-progress-track { height:5px; overflow:hidden; border-radius:99px; background:var(--color-bg-subtle); }
.player-progress-track span { display:block; height:100%; border-radius:inherit; background:var(--cinnabar); transition:width .15s linear; }
.player-progress > div:last-child { display:flex; justify-content:space-between; margin-top:8px; color:var(--ink-muted); font:10px var(--font-mono); }
.player-empty-state { display:grid; justify-items:center; align-content:center; flex:1; min-height:170px; color:var(--ink-muted); text-align:center; }
.player-empty-state .material-symbols-outlined { color:var(--line-strong); font-size:33px; }
.player-empty-state p { margin:10px 0 4px; color:var(--ink); font-size:13px; }
.player-empty-state small { font-size:10px; }
.player-volume { display:flex; align-items:center; gap:9px; margin-top:18px; color:var(--ink-muted); }
.player-volume input { flex:1; min-width:90px; accent-color:var(--cinnabar); }
.player-text-button { padding:0; border:0; background:none; color:var(--ink-muted); font-size:11px; }
.player-text-button:hover { color:var(--ink); }
.player-queue-card { margin-top:18px; }
.player-queue-count { color:var(--ink-muted); font:11px var(--font-mono); }
.player-queue-actions { display:grid; grid-template-columns:repeat(3, 1fr); gap:10px; margin-top:20px; }
.player-queue-action { display:flex; align-items:center; gap:10px; min-height:62px; padding:10px 12px; border:1px solid var(--line); border-radius:6px; background:var(--color-bg-canvas); text-align:left; }
.player-queue-action:hover { border-color:var(--line-strong); background:var(--color-bg-subtle); }
.player-queue-action > span { display:grid; place-items:center; width:26px; height:26px; border-radius:50%; font-size:18px; }
.player-queue-action.append > span { color:var(--cyan); background:color-mix(in srgb, var(--cyan) 12%, transparent); }
.player-queue-action.interrupt > span { color:var(--cinnabar); background:color-mix(in srgb, var(--cinnabar) 12%, transparent); }
.player-queue-action.clear > span { color:var(--amber); background:color-mix(in srgb, var(--amber) 12%, transparent); }
.player-queue-action strong,.player-queue-action small { display:block; }
.player-queue-action strong { font-size:12px; font-weight:500; }
.player-queue-action small { margin-top:4px; color:var(--ink-muted); font-size:10px; }
.player-pending { display:flex; align-items:center; flex-wrap:wrap; gap:7px; margin-top:18px; padding:10px; border:1px dashed color-mix(in srgb, var(--cinnabar) 40%, var(--line)); color:var(--cinnabar); font-size:11px; }
.player-pending-mark { padding:2px 5px; border-radius:3px; background:color-mix(in srgb, var(--cinnabar) 12%, transparent); font-size:10px; }
.player-queue-list { padding:0; margin:18px 0 0; list-style:none; }
.player-queue-list li { display:grid; grid-template-columns:36px 45px minmax(0,1fr) auto; align-items:center; gap:10px; min-height:60px; padding:10px 0; border-top:1px solid var(--line); }
.player-queue-index,.player-queue-duration { color:var(--ink-muted); font:10px var(--font-mono); }
.player-queue-type { align-self:start; margin-top:3px; padding:3px 5px; border-radius:3px; color:var(--cyan); background:color-mix(in srgb, var(--cyan) 10%, transparent); font-size:9px; }
.player-queue-list strong { font-size:12px; font-weight:500; }
.player-queue-list p { overflow:hidden; margin:4px 0 0; color:var(--ink-muted); font-size:11px; text-overflow:ellipsis; white-space:nowrap; }
.player-queue-empty { padding:26px 0 8px; color:var(--ink-muted); font-size:12px; text-align:center; }
.player-bottom-bar { display:flex; align-items:center; justify-content:space-between; gap:16px; margin-top:18px; padding:14px 18px; border:1px solid var(--line); border-radius:8px; background:var(--color-bg-subtle); }
.player-bottom-label { display:block; margin-bottom:5px; color:var(--ink-muted); font:10px var(--font-mono); }
.player-bottom-bar strong { font-size:12px; font-weight:500; }
.player-bottom-actions { display:flex; gap:8px; }
.player-compliance { margin:18px 0 0; color:var(--ink-muted); font-size:10px; line-height:1.7; text-align:center; }
@media (max-width: 760px) {
  .player-main { width:min(100% - 28px, 600px); padding-top:28px; }
  .player-hero,.player-grid { grid-template-columns:1fr; }
  .player-hero { gap:18px; }
  .player-queue-actions { grid-template-columns:1fr; }
  .player-status-details { gap:6px; }
  .player-status-details div { padding:8px; }
}
@media (max-width: 460px) {
  .player-topbar { padding:0 15px; }
  .player-topbar-meta { display:none; }
  .player-main { width:calc(100% - 24px); padding-bottom:32px; }
  .player-card { padding:18px; }
  .player-status-details { grid-template-columns:1fr; }
  .player-bottom-bar { align-items:stretch; flex-direction:column; }
  .player-bottom-actions { display:grid; grid-template-columns:1fr 1fr; }
  .player-button { width:100%; }
  .player-queue-list li { grid-template-columns:26px 45px minmax(0,1fr) auto; gap:7px; }
}
@media (prefers-reduced-motion: reduce) { .player-wave.active i { animation:none; height:11px; } }
</style>
