<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { ChevronRight, History } from 'lucide-vue-next'
import { copyApi, industryPrompt, platformLabel, topicLink } from '../services/copywriting'
import { selectedStoreId } from '../stores/merchantContext'
import { auth } from '../stores/auth'
import '../copywriting.css'

const NUMERALS = ['壹', '贰', '叁']
const almanac = ref(null)
const voice = ref(null)
const entries = ref([])
const editing = ref(false)
const draft = ref('')
const saving = ref(false)
const error = ref('')
let almanacTimer = null
let voiceTimer = null

const samples = computed(() => draft.value.split(/\n\s*\n/).map(s => s.trim()).filter(Boolean))
const learning = computed(() => voice.value?.status === 'LEARNING')
const generating = computed(() => almanac.value?.status === 'GENERATING')
const prompt = computed(() => industryPrompt(almanac.value, auth.isOwner))
const voiceQuote = computed(() => voice.value?.samples?.[0] || '')

async function load() {
  clearTimeout(almanacTimer)
  clearTimeout(voiceTimer)
  error.value = ''
  if (!selectedStoreId.value || !auth.isAuthenticated) { almanac.value = null; voice.value = null; entries.value = []; return }
  const storeId = selectedStoreId.value
  try {
    const [nextAlmanac, nextVoice, journal] = await Promise.all([copyApi.almanac(storeId), copyApi.voice(storeId), copyApi.journal(storeId, 5)])
    if (storeId !== selectedStoreId.value) return
    almanac.value = nextAlmanac
    voice.value = nextVoice
    entries.value = journal.items || []
    if (generating.value) almanacTimer = setTimeout(refreshAlmanac, 3000)
    if (learning.value) voiceTimer = setTimeout(refreshVoice, 3000)
  } catch (e) { error.value = e.message }
}

async function refreshAlmanac() {
  try {
    almanac.value = await copyApi.almanac(selectedStoreId.value)
    if (generating.value) almanacTimer = setTimeout(refreshAlmanac, 3000)
  } catch { /* 下次打开再取 */ }
}

async function refreshVoice() {
  try {
    voice.value = await copyApi.voice(selectedStoreId.value)
    if (learning.value) voiceTimer = setTimeout(refreshVoice, 3000)
  } catch { /* 下次打开再取 */ }
}

function startEditing() {
  if (!auth.requireLogin()) return
  draft.value = (voice.value?.samples || []).join('\n\n')
  editing.value = true
}

async function saveSamples() {
  if (samples.value.length < 3) { error.value = '至少贴 3 条，每条之间空一行'; return }
  saving.value = true
  error.value = ''
  try {
    voice.value = await copyApi.updateVoice(selectedStoreId.value, samples.value.slice(0, 10))
    editing.value = false
    voiceTimer = setTimeout(refreshVoice, 3000)
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

function day(value) {
  const date = new Date(value)
  return `${String(date.getMonth() + 1).padStart(2, '0')}/${String(date.getDate()).padStart(2, '0')}`
}

function firstLine(entry) { return (entry.title || entry.body || '').split('\n').find(line => line.trim()) || '' }

watch(selectedStoreId, load, { immediate: true })
onBeforeUnmount(() => { clearTimeout(almanacTimer); clearTimeout(voiceTimer) })
</script>

<template>
  <div class="cw-page cw-home">
    <header class="cw-home-head">
      <h1>文案创作</h1>
      <div>
        <RouterLink to="/copy/create?history=1" class="cw-ghost cw-home-history"><History :size="16" :stroke-width="1.7" aria-hidden="true" />创作记录</RouterLink>
        <RouterLink to="/copy/create" class="cw-primary-link">直接写一条<ChevronRight :size="16" :stroke-width="2" aria-hidden="true" /></RouterLink>
      </div>
    </header>

    <p v-if="error" class="cw-error" role="alert">{{ error }}</p>

    <div class="cw-home-row">
      <section class="cw-almanac" aria-label="今日宜发">
        <div class="cw-almanac-top">
          <div class="cw-almanac-date">
            <span class="cw-almanac-kicker">今日宜发<em v-if="almanac?.industry">按 {{ almanac.industry }}</em></span>
            <strong>{{ almanac?.day ?? new Date().getDate() }}</strong>
            <span>{{ almanac ? `${almanac.month} · ${almanac.weekday}` : '' }}</span>
          </div>
          <span v-if="almanac" class="cw-almanac-term">{{ almanac.termLabel }}</span>
        </div>
        <i class="cw-almanac-rule" aria-hidden="true"></i>
        <div class="cw-almanac-lines">
          <div class="cw-almanac-line">
            <span class="cw-seal-good">宜</span>
            <div><span v-for="item in almanac?.good || []" :key="item">{{ item }}</span></div>
          </div>
          <div class="cw-almanac-line muted">
            <span class="cw-seal-avoid">忌</span>
            <div><span v-for="item in almanac?.avoid || []" :key="item">{{ item }}</span></div>
          </div>
        </div>
        <div v-if="prompt" class="cw-almanac-prompt">
          <span>{{ prompt.text }}</span>
          <RouterLink v-if="prompt.link" :to="prompt.link">{{ prompt.label }}<ChevronRight :size="14" :stroke-width="2" aria-hidden="true" /></RouterLink>
        </div>
        <p v-else-if="generating" class="cw-almanac-quote" role="status">正在翻今天的黄历……</p>
        <p v-else-if="almanac?.quote" class="cw-almanac-quote">{{ almanac.quote }}</p>
        <p v-if="!auth.isAuthenticated" class="cw-almanac-quote">登录后，按你的店给出今天的宜与忌。</p>
      </section>

      <section class="cw-topics" aria-label="今天可以发">
        <h2>今天可以发</h2>
        <template v-if="almanac?.topics?.length">
          <RouterLink v-for="(topic, index) in almanac.topics" :key="topic.title" :to="topicLink(topic)" class="cw-card cw-topic">
            <span class="cw-topic-no">{{ NUMERALS[index] }}</span>
            <span class="cw-topic-main"><strong>{{ topic.title }}</strong><small>{{ platformLabel(topic.platform) }} · {{ topic.style }}</small></span>
            <span class="cw-topic-go">就写这个<ChevronRight :size="15" :stroke-width="2" aria-hidden="true" /></span>
          </RouterLink>
        </template>
        <template v-else-if="generating">
          <div v-for="n in 3" :key="n" class="cw-card cw-topic cw-topic-waiting" aria-hidden="true">
            <span class="cw-topic-no">{{ NUMERALS[n - 1] }}</span><span class="cw-topic-main"><i></i><i></i></span>
          </div>
        </template>
        <div v-else class="cw-card cw-topic-empty">
          <p>{{ auth.isAuthenticated ? '今天的选题还没准备好，可以先直接写一条。' : '登录后，这里会给出今天适合发的 3 个选题。' }}</p>
          <RouterLink to="/copy/create">直接写一条<ChevronRight :size="15" :stroke-width="2" aria-hidden="true" /></RouterLink>
        </div>
      </section>
    </div>

    <div class="cw-home-row">
      <section id="voice" class="cw-voice-card" aria-label="我的口吻">
        <div class="cw-voice-card-head">
          <h2>我的口吻</h2>
          <span v-if="voice?.status === 'READY'">已学会 · {{ voice.samples.length }} 条</span>
          <span v-else-if="learning">正在学…</span>
        </div>
        <template v-if="!editing">
          <p v-if="voiceQuote" class="cw-voice-summary">“{{ voiceQuote }}”</p>
          <p v-else-if="voice?.brandStyle" class="cw-voice-summary">暂时按品牌库的语言风格写：{{ voice.brandStyle }}</p>
          <p v-else class="cw-voice-summary">贴几条你以前发过的朋友圈，之后写出来的文案会更像你本人。</p>
          <div v-if="voice?.traits?.length" class="cw-voice-traits"><span v-for="trait in voice.traits" :key="trait">{{ trait }}</span></div>
          <p v-if="voice?.errorMessage && !learning" class="cw-voice-error">{{ voice.errorMessage }}</p>
          <button type="button" class="cw-voice-button" :disabled="learning || (voice && !voice.remainingToday)" @click="startEditing">
            {{ voice?.samples?.length ? '再喂几条' : '喂几条我发过的' }}
          </button>
        </template>
        <template v-else>
          <label for="voice-samples" class="cw-sr-only">我发过的文案</label>
          <textarea id="voice-samples" v-model="draft" rows="8" maxlength="5500" placeholder="每条之间空一行，3 到 10 条"></textarea>
          <div class="cw-voice-actions">
            <small>{{ samples.length }} 条 · 今天还能更新 {{ voice?.remainingToday ?? 10 }} 次</small>
            <button type="button" class="cw-voice-cancel" @click="editing = false">取消</button>
            <button type="button" class="cw-voice-button solid" :disabled="saving" @click="saveSamples">{{ saving ? '提交中…' : '开始学' }}</button>
          </div>
        </template>
      </section>

      <section class="cw-card cw-journal" aria-label="门店志">
        <div class="cw-journal-head"><h2>门店志</h2></div>
        <p v-if="!auth.isAuthenticated" class="cw-drawer-empty">登录后可以看到这家店定稿的文案。</p>
        <p v-else-if="!entries.length" class="cw-drawer-empty">定稿的文案会收在这里，成为这家店的编年史。</p>
        <div v-for="entry in entries" :key="entry.id" class="cw-journal-row">
          <span class="cw-journal-day">{{ day(entry.finalizedAt) }}</span>
          <span class="cw-journal-text">{{ firstLine(entry) }}</span>
          <span class="cw-journal-platform">{{ platformLabel(entry.platform) }}</span>
          <i class="cw-record-seal" aria-hidden="true">定</i>
        </div>
        <p v-if="almanac?.journalHint" class="cw-journal-hint">{{ almanac.journalHint }}</p>
      </section>
    </div>
  </div>
</template>
