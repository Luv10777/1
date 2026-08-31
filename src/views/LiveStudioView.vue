<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

/* ---------------- 全局阶段：配置 → 开播引导 → 监控台 → 复盘 ---------------- */
const stage = ref('setup') // setup | live | review
const steps = [
  { key: 'voice', index: '01', label: '人设与声音' },
  { key: 'script', index: '02', label: '话术与知识库' },
  { key: 'balance', index: '03', label: '云算力套餐' },
  { key: 'launch', index: '04', label: '启动直播' },
]
const activeStep = ref('voice')
// 已完成过首次配置的老用户，默认直达启动步骤
const hasInitialConfig = ref(true)
const editingConfig = ref(false)
const showDetailedSetup = computed(() => !hasInitialConfig.value || editingConfig.value)
const scrollToStep = (key) => {
  activeStep.value = key
  document.getElementById(`ls-step-${key}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/* ---------------- 01 声音克隆与角色 ---------------- */
const voices = ref([
  { id: 'v1', name: '老板娘·亲和', sample: '10s 样本 · 已训练', quality: 98, role: 'host', glyph: '林', builtin: true },
  { id: 'v2', name: '店长·稳重男声', sample: '12s 样本 · 已训练', quality: 95, role: 'cohost', glyph: '陈', builtin: true },
  { id: 'v3', name: '车间主管·实在', sample: '10s 样本 · 已训练', quality: 91, role: 'none', glyph: '赵', builtin: false },
])
const playingVoice = ref('')
let playTimer = null
const previewVoice = (id) => {
  clearTimeout(playTimer)
  if (playingVoice.value === id) { playingVoice.value = ''; return }
  playingVoice.value = id
  playTimer = setTimeout(() => { playingVoice.value = '' }, 2600)
}
const setRole = (voice, role) => {
  if (role === 'host') voices.value.forEach(v => { if (v.role === 'host') v.role = 'none' })
  voice.role = voice.role === role ? 'none' : role
}
const rotateRoles = ref(true)
const rotationEditing = ref(false)
const rotationSelection = ref(['v1', 'v2'])
const startRotationEdit = () => { rotationEditing.value = true }
const confirmRotationEdit = () => {
  if (rotationSelection.value.length < 2) {
    window.alert('至少选择2个音色才能开启轮换')
    return
  }
  rotateRoles.value = true
  rotationEditing.value = false
}
const toggleRotationVoice = (voice) => {
  if (!rotationEditing.value) return
  const selected = rotationSelection.value.includes(voice.id)
  if (selected) {
    rotationSelection.value = rotationSelection.value.filter(id => id !== voice.id)
  } else {
    rotationSelection.value = [...rotationSelection.value, voice.id]
  }
}
const voiceDialogOpen = ref(false)
const voiceDialogMode = ref('edit')
const voiceDialogVoice = ref(null)
const voiceDialogName = ref('')
const voiceDialogReplaceSample = ref(false)
const openVoiceDialog = (voice, mode) => {
  voiceDialogVoice.value = voice
  voiceDialogMode.value = mode
  voiceDialogName.value = voice.name
  voiceDialogReplaceSample.value = false
  voiceDialogOpen.value = true
}
const closeVoiceDialog = () => { voiceDialogOpen.value = false }
const submitVoiceDialog = () => {
  const voice = voiceDialogVoice.value
  if (!voice) return
  if (voiceDialogMode.value === 'edit') {
    const name = voiceDialogName.value.trim()
    if (name) {
      voice.name = name
      voice.glyph = name.slice(0, 1)
    }
    if (voiceDialogReplaceSample.value) voice.sample = '已替换样本 · 已训练'
  } else {
    voices.value = voices.value.filter(v => v.id !== voice.id)
    rotationSelection.value = rotationSelection.value.filter(id => id !== voice.id)
    if (rotationSelection.value.length < 2) rotateRoles.value = false
  }
  closeVoiceDialog()
}
const editVoice = (voice) => {
  openVoiceDialog(voice, 'edit')
}
const removeVoice = (voice) => {
  if (voice.builtin) return
  openVoiceDialog(voice, 'delete')
}
const cloneOpen = ref(false)
const cloneName = ref('')
const cloneFileName = ref('')
const recording = ref(false)
const recordingPaused = ref(false)
const recordSeconds = ref(0)
const recordModalOpen = ref(false)
const samplePreviewing = ref(false)
const recordingScripts = [
  '欢迎新进直播间的朋友，今天给大家带来的都是现磨纯手工好物，顺手包邮直送您家，品质看得见。',
  '大家好，欢迎来到我们的直播间，门店和车间实景展示，喜欢的朋友可以放心下单。',
  '今天为大家准备了几款超值好物，现做现发、用料扎实，感兴趣的朋友记得关注收藏。',
  '感谢大家来到直播间，有任何问题都可以直接留言，我们会一一为大家解答。',
]
const microphones = ['内置麦克风', 'MacBook Pro 内置麦克风', 'AirPods Pro 麦克风', 'USB 电容麦克风', '蓝牙耳机麦克风']
const selectedMicrophone = ref(microphones[0])
const recordingScriptIndex = ref(0)
const currentRecordingScript = computed(() => recordingScripts[recordingScriptIndex.value])
const nextRecordingScript = () => { recordingScriptIndex.value = (recordingScriptIndex.value + 1) % recordingScripts.length }
let recordTimer = null
const openRecordingModal = () => { recordModalOpen.value = true }
const closeRecordingModal = () => {
  recording.value = false
  recordingPaused.value = false
  clearInterval(recordTimer)
  recordModalOpen.value = false
  samplePreviewing.value = false
}
const submitRecording = () => {
  recording.value = false
  recordingPaused.value = false
  clearInterval(recordTimer)
  recordModalOpen.value = false
  samplePreviewing.value = false
}
const toggleSamplePreview = () => {
  samplePreviewing.value = !samplePreviewing.value
  if (samplePreviewing.value) setTimeout(() => { samplePreviewing.value = false }, 2600)
}
const restartRecording = () => {
  clearInterval(recordTimer)
  recordSeconds.value = 0
  recordingPaused.value = false
  recording.value = true
  recordTimer = setInterval(() => {
    recordSeconds.value += 1
    if (recordSeconds.value >= 10) { clearInterval(recordTimer); recording.value = false }
  }, 1000)
}
const pauseRecording = () => {
  recording.value = false
  recordingPaused.value = true
  clearInterval(recordTimer)
}
const resumeRecording = () => {
  recording.value = true
  recordingPaused.value = false
  clearInterval(recordTimer)
  recordTimer = setInterval(() => {
    recordSeconds.value += 1
    if (recordSeconds.value >= 10) { clearInterval(recordTimer); recording.value = false; recordingPaused.value = false }
  }, 1000)
}
const toggleRecord = () => {
  if (recording.value) return pauseRecording()
  if (recordingPaused.value) return resumeRecording()
  restartRecording()
}
const recordingAction = () => {
  if (recording.value) return pauseRecording()
  if (recordSeconds.value >= 10) return submitRecording()
  if (recordingPaused.value) return resumeRecording()
  return restartRecording()
}
const saveClone = () => {
  const name = cloneName.value.trim() || `新音色 ${voices.value.length + 1}`
  const id = `v${Date.now()}`
  voices.value.push({ id, name, sample: '10s 样本 · 训练中', quality: 0, role: 'none', glyph: name.slice(0, 1), builtin: false, training: true })
  cloneName.value = ''
  cloneFileName.value = ''
  cloneOpen.value = false
  recordSeconds.value = 0
  setTimeout(() => {
    const voice = voices.value.find(v => v.id === id)
    if (voice) { voice.training = false; voice.sample = '10s 样本 · 已训练'; voice.quality = 94 }
  }, 15000)
}
const onCloneFile = (event) => {
  const file = event.target.files?.[0]
  if (file) cloneFileName.value = file.name
}
const cloneReady = computed(() => recordSeconds.value >= 10 || !!cloneFileName.value)
const hostVoice = computed(() => voices.value.find(v => v.role === 'host'))

/* ---------------- 02 话术与知识库 ---------------- */
const productSource = ref('library')
const libraryProducts = [
  { id: 'p1', name: '手工现磨芝麻丸', price: '69 / 罐', tag: '食品' },
  { id: 'p2', name: '车间直发不锈钢锅', price: '199 / 口', tag: '厨具' },
  { id: 'p3', name: '门店现调茶饮券', price: '19.9 / 张', tag: '到店核销' },
]
const pickedProduct = ref('p1')
const manualProduct = ref({ name: '', price: '', points: '' })

const toneGroups = [
  { key: 'opening', label: '开场', options: ['门店实景寒暄', '直接报价', '悬念提问'] },
  { key: 'pain', label: '痛点挖掘', options: ['生活场景代入', '同类对比', '不展开'] },
  { key: 'detail', label: '细节讲解', options: ['工艺与用料', '使用方法', '售后与保障'] },
]
const tone = ref({ opening: '门店实景寒暄', pain: '生活场景代入', detail: '工艺与用料' })
const urgency = ref(3)
const urgencyLabel = computed(() => ['极缓 · 只讲不催', '偏慢', '标准节奏', '偏紧', '强促单 · 高频逼单'][urgency.value - 1])

const qaPairs = ref([
  { q: '这个是现做的吗？', a: '镜头里就是我们车间，早上 6 点开工，拍到什么就是什么。' },
  { q: '发什么快递？多久到？', a: '默认顺丰，江浙沪次日达，其他地区 2–3 天。' },
  { q: '可以到店自提吗？', a: '可以的，下单备注自提，凭订单号到门店直接拿。' },
])
const newQ = ref('')
const newA = ref('')
const addQa = () => {
  if (!newQ.value.trim() || !newA.value.trim()) return
  qaPairs.value.push({ q: newQ.value.trim(), a: newA.value.trim() })
  newQ.value = ''
  newA.value = ''
}
const removeQa = (i) => qaPairs.value.splice(i, 1)
const antiRepeat = ref(true)

/* ---------------- 03 算力套餐 ---------------- */
const balanceHours = ref(6.5)
const plans = [
  { id: 'hourly', name: '按小时充值', price: '¥12', unit: '/ 小时', hours: '10 小时起充', desc: '偶尔开播、先试水的商家', perks: ['随充随用，永久有效', '标准话术引擎', '弹幕自动回复'] },
  { id: 'month', name: '月卡', price: '¥399', unit: '/ 月', hours: '含 60 小时', desc: '每天固定时段开播', perks: ['单价低至 ¥6.6/小时', '多音色轮换', '知识库 500 条', '优先算力队列'], featured: true },
  { id: 'quarter', name: '季卡', price: '¥1,059', unit: '/ 季', hours: '含 210 小时', desc: '多门店、长时段无人播', perks: ['单价低至 ¥5.0/小时', '全部月卡权益', '知识库不限条数', '专属运营顾问'] },
]
const pickedPlan = ref('month')
const dailyHours = ref(6)
const estimatedDays = computed(() => (balanceHours.value / dailyHours.value).toFixed(1))

/* ---------------- 04 启动引导 ---------------- */
const roomUrl = ref('')
const roomState = computed(() => {
  const v = roomUrl.value.trim()
  if (!v) return { level: 'idle', text: '粘贴抖音直播间分享链接，支持 v.douyin.com 短链与 live.douyin.com 完整链接。' }
  if (!/douyin\.com/.test(v)) return { level: 'error', text: '暂未识别到抖音直播间链接，请在抖音「分享 → 复制链接」后再粘贴。' }
  return { level: 'ok', text: '已识别直播间 room_id 7398***2140 · 准备接入弹幕监听' }
})
const clientInstalled = ref(false)
const startLive = () => {
  if (roomState.value.level !== 'ok') return
  stage.value = 'live'
  elapsed.value = 0
  const participants = rotationVoices.value
  speakingVoiceId.value = participants[0]?.id || hostVoice.value?.id || ''
  startClock()
}

/* ---------------- 05 监控台 ---------------- */
const elapsed = ref(0)
let clock = null
const startClock = () => {
  clearInterval(clock)
  clock = setInterval(() => {
    elapsed.value += 1
    if (rotateRoles.value && rotationVoices.value.length > 1) {
      const idx = Math.floor(elapsed.value / 6) % rotationVoices.value.length
      speakingVoiceId.value = rotationVoices.value[idx]?.id || speakingVoiceId.value
    }
    if (elapsed.value % 4 === 0) pushDanmu()
    viewers.value = Math.max(40, viewers.value + Math.round((Math.random() - 0.42) * 18))
    trend.value = [...trend.value.slice(1), viewers.value]
    likes.value += Math.round(Math.random() * 9)
    if (Math.random() > 0.86) follows.value += 1
  }, 1000)
}
const clockText = computed(() => {
  const h = String(Math.floor(elapsed.value / 3600)).padStart(2, '0')
  const m = String(Math.floor((elapsed.value % 3600) / 60)).padStart(2, '0')
  const s = String(elapsed.value % 60).padStart(2, '0')
  return `${h}:${m}:${s}`
})
const durationLabel = computed(() => {
  if (elapsed.value < 60) return `${elapsed.value}秒`
  const minutes = Math.floor(elapsed.value / 60)
  const seconds = elapsed.value % 60
  return `${minutes}分${seconds ? ` ${seconds}秒` : ''}`
})
const usedHours = computed(() => (elapsed.value / 3600))
const remainHours = computed(() => Math.max(0, balanceHours.value - usedHours.value))

const viewers = ref(126)
const trend = ref(Array.from({ length: 24 }, (_, i) => 60 + Math.round(Math.sin(i / 2.4) * 22 + i * 2.4)))
const likes = ref(1840)
const follows = ref(37)
const trendPath = computed(() => {
  const max = Math.max(...trend.value) || 1
  const min = Math.min(...trend.value)
  const span = Math.max(1, max - min)
  return trend.value.map((v, i) => `${(i / (trend.value.length - 1)) * 100},${34 - ((v - min) / span) * 30}`).join(' ')
})

const audienceLines = [
  ['能看看后厨吗？', '镜头这就转过去，我们后厨全程开放，随时可以看。'],
  ['多少钱一份呀', '现在直播间价 69 一罐，两罐包邮，链接在小黄车 1 号。'],
  ['真的是今天做的吗', '刚才画面里出锅的就是今天这批，出货日期直接打在罐底。'],
  ['能开发票吗', '可以的，下单时备注抬头，电子发票 24 小时内发到手机。'],
  ['有没有无糖的', '有一款低糖版本，糖含量只有原味的三分之一，2 号链接。'],
  ['支持退换吗', '七天无理由，食品未拆封都可以退，运费我们承担。'],
]
const feed = ref([
  { id: 1, type: 'ask', user: '小满', text: '这个是现做的吗？' },
  { id: 2, type: 'ai', text: '镜头里就是我们车间，早上 6 点开工，拍到什么就是什么。', matched: '知识库命中' },
])
let feedId = 3
const interactions = ref(2)
const pushDanmu = () => {
  const [q, a] = audienceLines[Math.floor(Math.random() * audienceLines.length)]
  const names = ['阿May', '路过的老王', '甜筒', '不吃香菜', '晚风', '橙子汽水']
  feed.value.push({ id: feedId++, type: 'ask', user: names[Math.floor(Math.random() * names.length)], text: q })
  interactions.value += 1
  setTimeout(() => {
    feed.value.push({ id: feedId++, type: 'ai', text: a, matched: Math.random() > 0.4 ? '知识库命中' : 'RAG 生成' })
    if (feed.value.length > 40) feed.value.splice(0, feed.value.length - 40)
  }, 900)
}
const feedEl = ref(null)
watch(() => feed.value.length, () => {
  requestAnimationFrame(() => { if (feedEl.value) feedEl.value.scrollTop = feedEl.value.scrollHeight })
})
const paused = ref(false)
const endConfirmOpen = ref(false)
const speakingVoiceId = ref('')
const rotationVoices = computed(() => {
  if (rotateRoles.value && rotationSelection.value.length >= 2) {
    return rotationSelection.value.map(id => voices.value.find(v => v.id === id)).filter(Boolean)
  }
  return hostVoice.value ? [hostVoice.value] : voices.value.slice(0, 1)
})
const speakingVoice = computed(() => rotationVoices.value.find(v => v.id === speakingVoiceId.value) || rotationVoices.value[0] || hostVoice.value)
const togglePause = () => {
  paused.value = !paused.value
  if (paused.value) clearInterval(clock)
  else startClock()
}
const requestEndLive = () => { endConfirmOpen.value = true }
const endLive = () => {
  endConfirmOpen.value = false
  clearInterval(clock)
  stage.value = 'review'
}

/* ---------------- 06 复盘 ---------------- */
const reviewStats = computed(() => [
  { label: '本场时长', value: clockText.value, foot: `${dailyHours.value}h 计划 · 实际达成` },
  { label: '峰值在线', value: String(Math.max(...trend.value)), foot: `均值 ${Math.round(trend.value.reduce((a, b) => a + b, 0) / trend.value.length)} 人` },
  { label: '互动量', value: String(interactions.value), foot: `AI 自动回复 ${Math.max(0, feed.value.filter(f => f.type === 'ai').length)} 条` },
  { label: '算力消耗', value: `${usedHours.value.toFixed(2)}h`, foot: `余额剩余 ${remainHours.value.toFixed(1)}h` },
  { label: '新增关注', value: String(follows.value), foot: `点赞 ${likes.value}` },
])
const restart = () => {
  stage.value = 'setup'
  elapsed.value = 0
  interactions.value = 0
  activeStep.value = 'voice'
  editingConfig.value = false
  rotationEditing.value = false
  paused.value = false
  endConfirmOpen.value = false
  recordModalOpen.value = false
  recording.value = false
  recordingPaused.value = false
}

onBeforeUnmount(() => { clearInterval(clock); clearInterval(recordTimer); clearTimeout(playTimer) })
</script>

<template>
  <div class="ls-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">CONTENT TOOLS / LIVE</p>
        <h1><span class="placeholder-icon">◎</span>AI实景直播</h1>
        <p class="page-intro">手机拍真实门店画面，云端负责生成话术、克隆声音朗读、自动回复弹幕。配置、监听与复盘都在这个网页工作区完成，手机只承担最后一步语音播报。</p>
      </div>
      <div class="ls-head-side">
        <span class="ls-stage-pill" :class="stage">
          <i class="status-pulse" />{{ { setup: '未开播 · 配置中', live: '直播中', review: '已结束 · 复盘' }[stage] }}
        </span>
        <span class="mono ls-balance-mini">余额 {{ remainHours.toFixed(1) }}h</span>
      </div>
    </div>

    <!-- 架构说明条 -->
    <section class="ls-arch panel-dark">
      <div class="ls-arch-node">
        <span class="ls-arch-glyph web">◫</span>
        <div><strong>网页端（主控）</strong><p>话术生成 · 声音克隆 · room_id 解析 · WebSocket 弹幕监听 · RAG 自动回复 · 数据复盘</p></div>
      </div>
      <div class="ls-arch-flow"><span /><em>仅传输合成语音</em><span /></div>
      <div class="ls-arch-node">
        <span class="ls-arch-glyph phone">▯</span>
        <div><strong>手机播报客户端（配件）</strong><p>接收云端语音并注入直播麦克风。受系统限制必须本地完成，除此之外不做任何事。</p></div>
      </div>
    </section>

    <!-- 步骤导航 -->
    <nav v-if="stage === 'setup' && showDetailedSetup" class="ls-stepbar" aria-label="配置步骤">
      <button v-for="s in steps" :key="s.key" type="button" class="ls-step-chip" :class="{ active: activeStep === s.key }" @click="scrollToStep(s.key)">
        <span class="mono">{{ s.index }}</span>{{ s.label }}
      </button>
      <button v-if="hasInitialConfig && editingConfig" type="button" class="ls-ghost compact ls-collapse-config" @click="editingConfig = false">收起编辑</button>
    </nav>

    <template v-if="stage === 'setup'">
      <section v-if="hasInitialConfig && !editingConfig" class="panel ls-setup-summary">
        <div class="panel-heading"><div><p class="eyebrow">快速路径</p><h3>配置已就绪，直接开始直播</h3></div><button type="button" class="ls-ghost" @click="editingConfig = true">编辑配置</button></div>
        <div class="ls-setup-summary-grid">
          <span>当前音色 <strong>{{ hostVoice?.name || '未分配' }}</strong></span>
          <span>知识库 <strong>{{ qaPairs.length }} 条</strong></span>
          <span>促单节奏 <strong>{{ urgencyLabel.split(' · ')[0] }}</strong></span>
          <span>轮换音色 <strong>{{ rotateRoles ? rotationSelection.length + ' 个' : '未开启' }}</strong></span>
        </div>
      </section>
      <!-- 01 声音克隆 -->
      <section v-if="showDetailedSetup" id="ls-step-voice" class="panel ls-section">
        <div class="panel-heading">
          <div><p class="eyebrow">STEP 01</p><h3>主播人设与声音克隆</h3></div>
          <div class="ls-step-heading-actions">
            <button v-if="!rotationEditing" type="button" class="ls-ghost compact" @click="startRotationEdit">多角色配置</button>
            <button v-else type="button" class="primary-button compact" @click="confirmRotationEdit">确认</button>
          </div>
        </div>
        <p class="ls-section-note">主播负责主线讲解，助播负责接话与答疑。开启轮换后，系统会在每轮话术之间切换音色，让直播间听起来像两个人在配合。</p>
        <div class="ls-voice-grid">
          <article v-for="voice in voices" :key="voice.id" class="ls-voice-card" :class="{ assigned: voice.role !== 'none', selected: rotationSelection.includes(voice.id), 'rotation-selected': rotationSelection.includes(voice.id) && rotateRoles }" @click="rotationEditing && toggleRotationVoice(voice)">
            <div class="ls-voice-badge" :class="{ clone: !voice.builtin }">{{ voice.builtin ? '系统内置' : '我的克隆' }}</div>
            <div v-if="!voice.builtin" class="ls-voice-manage"><button type="button" aria-label="编辑音色" @click.stop="editVoice(voice)">✎</button><button type="button" aria-label="删除音色" @click.stop="removeVoice(voice)">×</button></div>
            <div class="ls-voice-top">
              <span class="ls-voice-avatar">{{ voice.glyph }}</span>
              <div class="ls-voice-copy">
                <strong>{{ voice.name }}</strong>
                <small>{{ voice.sample }}</small>
              </div>
            </div>
            <div class="ls-wave-row">
              <div class="ls-wave" :class="{ active: playingVoice === voice.id }">
                <i v-for="n in 22" :key="n" :style="{ animationDelay: `${n * 55}ms` }" />
              </div>
              <button type="button" class="ls-play" :class="{ playing: playingVoice === voice.id }" :aria-label="`试听 ${voice.name}`" @click.stop="previewVoice(voice.id)">
                {{ playingVoice === voice.id ? '❚❚' : '▶' }}
              </button>
            </div>
            <div class="ls-voice-foot">
              <span class="ls-quality" title="声纹还原度：克隆音色与原始样本的相似程度">声纹还原度 <i>{{ voice.quality ? voice.quality + '%' : '训练中' }}</i></span>
              <div class="ls-role-toggle">
                <button type="button" :disabled="voice.training || !rotationEditing || !rotationSelection.includes(voice.id)" :class="{ on: voice.role === 'host', readonly: !rotationEditing && rotationSelection.includes(voice.id) }" @click.stop="setRole(voice, 'host')">主播</button>
                <button type="button" :disabled="voice.training || !rotationEditing || !rotationSelection.includes(voice.id)" :class="{ on: voice.role === 'cohost', readonly: !rotationEditing && rotationSelection.includes(voice.id) }" @click.stop="setRole(voice, 'cohost')">助播</button>
              </div>
            </div>
          </article>

          <article class="ls-voice-card ls-clone-card" :class="{ open: cloneOpen }">
            <template v-if="!cloneOpen">
              <button type="button" class="ls-clone-entry" @click="cloneOpen = true">
                <span class="ls-clone-plus">＋</span>
                <strong>新建声音克隆</strong>
                <small>上传或录制清晰人声即可</small>
              </button>
            </template>
            <template v-else>
              <div class="ls-clone-form">
                <label class="ls-field"><span>音色名称</span><input v-model="cloneName" type="text" placeholder="例如：门店客服·热情"></label>
                <div class="ls-clone-inputs">
                  <button type="button" class="ls-clone-source" @click="openRecordingModal"><span>🎙</span>{{ recordSeconds ? '重新录制' : '麦克风录制' }}</button>
                  <label class="ls-clone-source"><span>📁</span>{{ cloneFileName || '上传音频文件' }}<input type="file" accept="audio/*" @change="onCloneFile"></label>
                </div>
                <div v-if="cloneReady" class="ls-clone-file"><span>✓</span>{{ cloneFileName || '录音样本 · 10 秒' }}<small>已准备</small></div>
                <p class="ls-clone-tip">💡 提示：安静环境下朗读 10~20 秒，效果最佳</p>
                <div class="ls-clone-actions">
                  <button type="button" class="primary-button compact" :disabled="!cloneReady" @click="saveClone">开始克隆训练</button>
                  <button type="button" class="ls-ghost" @click="cloneOpen = false">取消</button>
                </div>
              </div>
            </template>
          </article>
        </div>
      </section>

      <!-- 02 话术与知识库 -->
      <section v-if="showDetailedSetup" id="ls-step-script" class="ls-section ls-two-col">
        <article class="panel">
          <div class="panel-heading"><div><p class="eyebrow">STEP 02 / A</p><h3>商品信息与话术风格</h3></div></div>

          <div class="ls-tabs" role="tablist">
            <button type="button" role="tab" :aria-selected="productSource === 'library'" :class="{ on: productSource === 'library' }" @click="productSource = 'library'">从商品库选择</button>
            <button type="button" role="tab" :aria-selected="productSource === 'manual'" :class="{ on: productSource === 'manual' }" @click="productSource = 'manual'">手动填写</button>
          </div>

          <div v-if="productSource === 'library'" class="ls-product-list">
            <label v-for="p in libraryProducts" :key="p.id" class="ls-product-row" :class="{ on: pickedProduct === p.id }">
              <input v-model="pickedProduct" type="radio" :value="p.id">
              <span class="ls-radio" />
              <span class="ls-product-copy"><strong>{{ p.name }}</strong><small>{{ p.tag }}</small></span>
              <span class="mono ls-product-price">{{ p.price }}</span>
            </label>
          </div>
          <div v-else class="ls-manual">
            <label class="ls-field"><span>商品名称</span><input v-model="manualProduct.name" type="text" placeholder="例如：手工现磨芝麻丸"></label>
            <label class="ls-field"><span>直播间价格</span><input v-model="manualProduct.price" type="text" placeholder="例如：69 元 / 罐，两罐包邮"></label>
            <label class="ls-field"><span>核心卖点</span><textarea v-model="manualProduct.points" rows="3" placeholder="每行一个卖点：现磨现做、零添加蔗糖、车间直发…" /></label>
          </div>

          <div class="ls-tone">
            <div v-for="group in toneGroups" :key="group.key" class="ls-tone-row">
              <span class="ls-tone-label">{{ group.label }}</span>
              <div class="ls-tone-options">
                <button v-for="opt in group.options" :key="opt" type="button" :class="{ on: tone[group.key] === opt }" @click="tone[group.key] = opt">{{ opt }}</button>
              </div>
            </div>
            <div class="ls-tone-row ls-slider-row">
              <span class="ls-tone-label">促单节奏</span>
              <div class="ls-slider">
                <input v-model.number="urgency" type="range" min="1" max="5" step="1">
                <span class="ls-slider-value">{{ urgencyLabel }}</span>
              </div>
            </div>
          </div>

          <div class="ls-anti" :class="{ on: antiRepeat }">
            <label class="ls-switch"><input v-model="antiRepeat" type="checkbox"><span /><em>话术防重复</em></label>
            <p>每轮循环自动改写话术，避免声纹 / 文本重复被限流。同一卖点会换说法、换语序、换停顿，不做逐字复读。</p>
          </div>
        </article>

        <article class="panel">
          <div class="panel-heading"><div><p class="eyebrow">STEP 02 / B</p><h3>互动知识库</h3></div><span class="mono muted-text">{{ qaPairs.length }} 条</span></div>
          <p class="ls-section-note">弹幕命中问题时优先按这里的答案回复；未命中则由 RAG 依据商品信息生成。</p>
          <ul class="ls-qa-list">
            <li v-for="(item, i) in qaPairs" :key="i" class="ls-qa-item">
              <div>
                <strong>Q · {{ item.q }}</strong>
                <p>A · {{ item.a }}</p>
              </div>
              <button type="button" class="ls-remove" :aria-label="`删除 ${item.q}`" @click="removeQa(i)">×</button>
            </li>
          </ul>
          <div class="ls-qa-add">
            <input v-model="newQ" type="text" placeholder="观众可能会问…">
            <input v-model="newA" type="text" placeholder="希望 AI 怎么答…" @keyup.enter="addQa">
            <button type="button" class="ls-ghost" @click="addQa">添加</button>
          </div>
        </article>
      </section>

      <!-- 03 套餐 -->
      <section v-if="showDetailedSetup" id="ls-step-balance" class="panel ls-section">
        <div class="panel-heading"><div><p class="eyebrow">STEP 03</p><h3>云算力套餐与余额</h3></div></div>
        <div class="ls-balance-bar">
          <div class="ls-balance-main">
            <p class="eyebrow">当前余额</p>
            <strong>{{ remainHours.toFixed(1) }}<small>小时</small></strong>
            <i class="ls-balance-track"><b :style="{ width: `${Math.min(100, remainHours / 60 * 100)}%` }" /></i>
          </div>
          <div class="ls-balance-est">
            <label class="ls-field inline"><span>每天计划开播</span>
              <select v-model.number="dailyHours"><option v-for="h in [2, 4, 6, 8, 12]" :key="h" :value="h">{{ h }} 小时</option></select>
            </label>
            <p>按此节奏，余额可支撑 <strong>{{ estimatedDays }}</strong> 天，约 <strong>{{ remainHours.toFixed(1) }}</strong> 小时无人直播。</p>
          </div>
        </div>
        <div class="ls-plan-grid">
          <article v-for="plan in plans" :key="plan.id" class="ls-plan-card" :class="{ featured: plan.featured, on: pickedPlan === plan.id }" @click="pickedPlan = plan.id">
            <span v-if="plan.featured" class="ls-plan-badge">最多商家选择</span>
            <h4>{{ plan.name }}</h4>
            <p class="ls-plan-price"><strong>{{ plan.price }}</strong><small>{{ plan.unit }}</small></p>
            <p class="ls-plan-hours mono">{{ plan.hours }}</p>
            <p class="ls-plan-desc">{{ plan.desc }}</p>
            <ul class="ls-plan-perks"><li v-for="perk in plan.perks" :key="perk">{{ perk }}</li></ul>
            <button type="button" class="ls-plan-cta">{{ pickedPlan === plan.id ? '已选择' : '选择套餐' }}</button>
          </article>
        </div>
      </section>

      <!-- 04 启动引导 -->
      <section id="ls-step-launch" class="panel ls-section ls-launch">
        <div class="panel-heading"><div><p class="eyebrow">STEP 04 / 唯一涉及手机的步骤</p><h3>启动直播</h3></div></div>

        <ol class="ls-launch-steps">
          <li class="ls-launch-step">
            <span class="ls-launch-index mono">01</span>
            <div>
              <strong>先在抖音 App 手动开播</strong>
              <p>用手机摄像头对准门店 / 车间实景，按平时的方式点开播。这一步必须真人操作：由真实设备发起的直播更安全，能避免被平台判定为自动化开播而限流或封禁。</p>
              <span class="ls-launch-tip">保持手机不锁屏、连接稳定网络与电源。</span>
            </div>
          </li>

          <li class="ls-launch-step">
            <span class="ls-launch-index mono">02</span>
            <div>
              <strong>复制直播间链接，粘贴到这里</strong>
              <p>在抖音直播间点「分享 → 复制链接」，粘贴后网页端会解析出 room_id 并建立弹幕连接。</p>
              <div class="ls-room-input" :class="roomState.level">
                <input v-model="roomUrl" type="text" placeholder="https://v.douyin.com/xxxxxxx/">
                <button type="button" class="ls-ghost" @click="roomUrl = 'https://v.douyin.com/iR8Kd2Qm/'">粘贴示例</button>
              </div>
              <p class="ls-room-feedback" :class="roomState.level" role="status">
                <i v-if="roomState.level !== 'idle'">{{ roomState.level === 'ok' ? '✓' : '!' }}</i>{{ roomState.text }}
              </p>
            </div>
          </li>

          <li class="ls-launch-step">
            <span class="ls-launch-index mono">03</span>
            <div>
              <strong>在直播的那台手机上打开播报客户端</strong>
              <p>客户端只负责接收云端合成好的语音并注入麦克风，不到 10MB，几秒装好；装好后放着不用管，所有配置仍在这个网页里改。</p>
              <div class="ls-client">
                <div class="ls-qr" aria-hidden="true">
                  <i v-for="n in 100" :key="n" :class="{ on: (n * 7919) % 11 > 5 }" />
                </div>
                <div class="ls-client-copy">
                  <button type="button" class="primary-button compact">{{ clientInstalled ? '打开播报客户端' : '下载播报客户端' }}</button>
                  <button type="button" class="ls-ghost" @click="clientInstalled = !clientInstalled">{{ clientInstalled ? '还没装？去下载' : '已安装，直接打开' }}</button>
                  <ul>
                    <li>已安装 → 深链接直接唤起并自动配对本场直播</li>
                    <li>未安装 → 扫码下载，安装后回到本页自动配对</li>
                    <li>支持外放拾音与虚拟声卡两种注入方式</li>
                  </ul>
                </div>
              </div>
            </div>
          </li>
        </ol>

        <div class="ls-launch-foot">
          <div class="ls-launch-summary">
            <span>主播音色 <strong>{{ hostVoice?.name || '未分配' }}</strong></span>
            <span>知识库 <strong>{{ qaPairs.length }} 条</strong></span>
            <span>促单节奏 <strong>{{ urgencyLabel.split(' · ')[0] }}</strong></span>
            <span>可播 <strong>{{ remainHours.toFixed(1) }}h</strong></span>
          </div>
          <button type="button" class="primary-button" :disabled="roomState.level !== 'ok'" @click="startLive">
            接入直播间并开始播报 <span>→</span>
          </button>
        </div>
      </section>
      <div v-if="voiceDialogOpen" class="ls-modal-backdrop" role="presentation" @click.self="closeVoiceDialog">
        <section class="ls-modal panel-dark" role="dialog" aria-modal="true" aria-labelledby="ls-voice-dialog-title">
          <p class="eyebrow accent">{{ voiceDialogMode === 'edit' ? 'EDIT CLONED VOICE' : 'DELETE CLONED VOICE' }}</p>
          <h3 id="ls-voice-dialog-title">{{ voiceDialogMode === 'edit' ? '编辑克隆音色' : '确定删除这个克隆音色吗？' }}</h3>
          <template v-if="voiceDialogMode === 'edit'">
            <label class="ls-field"><span>音色名称</span><input v-model="voiceDialogName" type="text" placeholder="例如：老板娘·亲和"></label>
            <div class="ls-dialog-upload"><button type="button" class="ls-ghost compact" @click="voiceDialogReplaceSample = true">上传 / 录制新样本</button><span v-if="voiceDialogReplaceSample">已选择新样本（演示）</span></div>
            <p class="ls-modal-copy">可在保存后继续使用当前音色；替换样本后会重新训练声纹。</p>
          </template>
          <template v-else>
            <p class="ls-modal-copy">删除后无法恢复。<span v-if="voiceDialogVoice && rotationSelection.includes(voiceDialogVoice.id)">该音色正用于角色轮换，删除后将自动移出轮换列表。</span></p>
          </template>
          <div class="ls-modal-actions">
            <button type="button" class="ls-ghost" @click="closeVoiceDialog">取消</button>
            <button type="button" :class="voiceDialogMode === 'edit' ? 'primary-button compact' : 'ls-danger'" @click="submitVoiceDialog">{{ voiceDialogMode === 'edit' ? '保存修改' : '确认删除' }}</button>
          </div>
        </section>
      </div>
      <div v-if="recordModalOpen" class="ls-modal-backdrop" role="presentation" @click.self="closeRecordingModal">
        <section class="ls-modal panel-dark ls-record-modal" role="dialog" aria-modal="true" aria-labelledby="ls-record-title">
          <p class="eyebrow accent">VOICE SAMPLE / 10 SEC</p>
          <div class="ls-record-modal-head"><h3 id="ls-record-title">录制声音样本</h3><label class="ls-record-device">🎙 <select v-model="selectedMicrophone" aria-label="选择录音麦克风"><option v-for="microphone in microphones" :key="microphone" :value="microphone">{{ microphone }}</option></select></label></div>
          <div class="ls-script-box"><div class="ls-script-head"><span>请自然朗读下方文字</span><button type="button" class="ls-ghost compact" @click="nextRecordingScript">换一段 ↻</button></div><p>“{{ currentRecordingScript }}”</p></div>
          <div class="ls-record ls-record-dialog" :class="{ recording }">
            <span class="mono" :class="{ complete: recordSeconds >= 10 }">{{ recordSeconds >= 10 ? '✓ 样本已录满' : `${recordSeconds}s / 10s` }}</span>
            <i class="ls-record-track"><b :style="{ width: `${recordSeconds * 10}%` }" /></i>
          </div>
          <div v-if="recordSeconds >= 10" class="ls-record-wave-row"><div class="ls-record-wave" :class="{ active: recording || samplePreviewing }"><i v-for="n in 18" :key="n" :style="{ animationDelay: `${n * 45}ms` }" /></div><button type="button" class="ls-ghost compact" @click="restartRecording">重新录制</button><button type="button" class="ls-play ls-sample-play" :class="{ playing: samplePreviewing }" @click="toggleSamplePreview">{{ samplePreviewing ? '❚❚' : '▶' }} 试听</button></div>
          <div v-else class="ls-record-wave" :class="{ active: recording }"><i v-for="n in 18" :key="n" :style="{ animationDelay: `${n * 45}ms` }" /></div>
          <div class="ls-modal-actions">
            <button type="button" :class="recordSeconds >= 10 ? 'primary-button compact' : 'ls-ghost'" @click="recordingAction">{{ recording ? '暂停' : (recordSeconds >= 10 ? '提交' : (recordingPaused ? '继续录制' : '开始录制')) }}</button>
            <button type="button" class="ls-ghost" @click="closeRecordingModal">取消</button>
          </div>
        </section>
      </div>
    </template>

    <!-- 05 监控台 -->
    <template v-else-if="stage === 'live'">
      <section class="ls-live-bar panel">
        <div class="ls-live-metric"><p class="eyebrow">直播时长</p><strong class="mono">{{ clockText }}</strong></div>
        <div class="ls-live-metric"><p class="eyebrow">算力消耗</p><strong class="mono">{{ usedHours.toFixed(2) }}h</strong><small>余 {{ remainHours.toFixed(1) }}h</small></div>
        <div class="ls-live-metric"><p class="eyebrow">弹幕互动</p><strong class="mono">{{ interactions }}</strong><small>AI 已回复 {{ feed.filter(f => f.type === 'ai').length }}</small></div>
        <div class="ls-live-metric ls-live-voice-metric"><p class="eyebrow">播报音色</p>
          <template v-if="rotateRoles && rotationVoices.length > 1">
            <div class="ls-rotation-voices" aria-label="本轮参与播报的音色">
              <div v-for="(voice, i) in rotationVoices" :key="voice.id" class="ls-rotation-voice" :class="{ active: speakingVoice?.id === voice.id }">
                <span class="ls-rotation-index">{{ i + 1 }}</span><span class="ls-rotation-name">{{ voice.name }}</span>
              </div>
            </div>
            <small>轮换播报中 · 当前 {{ speakingVoice?.name || '默认音色' }}</small>
          </template>
          <template v-else>
            <strong>{{ hostVoice?.name || '默认音色' }}</strong><small>单角色</small>
          </template>
        </div>
        <div class="ls-live-actions">
          <button type="button" class="ls-ghost" :class="{ paused }" @click="togglePause">{{ paused ? '已暂停·点击恢复' : '暂停播报' }}</button>
          <button type="button" class="ls-danger" @click="requestEndLive">结束本场</button>
        </div>
      </section>

      <section class="ls-live-grid">
        <article class="panel ls-feed-panel">
          <div class="panel-heading">
            <div><p class="eyebrow">REALTIME</p><h3>弹幕流与 AI 回复</h3></div>
            <span class="ls-conn"><i class="status-pulse" />WebSocket 已连接</span>
          </div>
          <div ref="feedEl" class="ls-feed">
            <div v-for="item in feed" :key="item.id" class="ls-bubble" :class="item.type">
              <template v-if="item.type === 'ask'">
                <span class="ls-bubble-user">{{ item.user }}</span>
                <p>{{ item.text }}</p>
              </template>
              <template v-else>
                <span class="ls-bubble-user ai">AI 助播<i>{{ item.matched }}</i></span>
                <p>{{ item.text }}</p>
              </template>
            </div>
          </div>
        </article>

        <div class="ls-live-side">
          <article class="panel ls-chart-card">
            <div class="panel-heading"><div><p class="eyebrow">观看人数</p><h3>{{ viewers }}<small> 人在线</small></h3></div></div>
            <svg class="ls-spark" viewBox="0 0 100 36" preserveAspectRatio="none" role="img" aria-label="观看人数曲线">
              <polyline :points="trendPath" fill="none" stroke="var(--violet-bright)" stroke-width="1.4" vector-effect="non-scaling-stroke" />
            </svg>
            <p class="ls-chart-foot mono">近 24 分钟趋势</p>
          </article>
          <article class="metric-card metric-amber ls-mini-metric">
            <div class="metric-header"><span>点赞</span><span class="metric-glyph">♥</span></div>
            <p class="metric-number">{{ likes.toLocaleString() }}</p>
            <div class="metric-foot"><span class="metric-delta">本场累计</span></div>
          </article>
          <article class="metric-card metric-cyan ls-mini-metric">
            <div class="metric-header"><span>关注转化</span><span class="metric-glyph">＋</span></div>
            <p class="metric-number">{{ follows }}<small>人</small></p>
            <div class="metric-foot"><span class="metric-delta">{{ (follows / Math.max(1, viewers) * 100).toFixed(1) }}%</span><span>转化率</span></div>
          </article>
        </div>
      </section>
      <div v-if="endConfirmOpen" class="ls-modal-backdrop" role="presentation" @click.self="endConfirmOpen = false">
        <section class="ls-modal panel-dark" role="dialog" aria-modal="true" aria-labelledby="ls-end-title">
          <p class="eyebrow accent">END SESSION</p>
          <h3 id="ls-end-title">确定结束本场直播？</h3>
          <p class="ls-modal-copy">已直播 <strong>{{ durationLabel }}</strong>，已产生 <strong>{{ usedHours.toFixed(2) }}h</strong> 算力消耗。结束后将停止语音播报并生成复盘报告。</p>
          <div class="ls-modal-actions">
            <button type="button" class="ls-ghost" @click="endConfirmOpen = false">继续直播</button>
            <button type="button" class="ls-danger" @click="endLive">确认结束</button>
          </div>
        </section>
      </div>
    </template>

    <!-- 06 复盘 -->
    <template v-else>
      <section class="ls-review panel-dark">
        <p class="eyebrow accent">SESSION REPORT</p>
        <h2>本场直播已结束</h2>
        <p class="ls-review-intro">语音播报已停止，手机端客户端可以关闭。以下是本场的核心数据，完整的时段拆解、话术转化归因在运营分析模块。</p>
        <div class="ls-review-grid">
          <article v-for="stat in reviewStats" :key="stat.label" class="ls-review-card">
            <p class="eyebrow">{{ stat.label }}</p>
            <strong>{{ stat.value }}</strong>
            <small>{{ stat.foot }}</small>
          </article>
        </div>
        <div class="ls-review-actions">
          <RouterLink to="/analytics" class="primary-button compact">查看完整报告 <span>→</span></RouterLink>
          <RouterLink to="/digital-human/history" class="text-link">查看本场弹幕与回复记录 <span>→</span></RouterLink>
          <button type="button" class="ls-ghost" @click="restart">再开一场</button>
        </div>
      </section>
    </template>
  </div>
</template>
