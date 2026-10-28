<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { ChevronRight, History } from 'lucide-vue-next'
import { copyApi, copyPlatforms, platformLabel } from '../services/copywriting'
import { selectedStoreId } from '../stores/merchantContext'
import { auth } from '../stores/auth'
import '../copywriting.css'

const intros = { MOMENTS: '两三句短话，像熟人聊天', DOUYIN: '钩子标题加话题', XIAOHONGSHU: '标题、分段正文和标签' }
const voice = ref(null)
const entries = ref([])
const editing = ref(false)
const draft = ref('')
const saving = ref(false)
const error = ref('')
let timer = null

const samples = computed(() => draft.value.split(/\n\s*\n/).map(s => s.trim()).filter(Boolean))
const learning = computed(() => voice.value?.status === 'LEARNING')

async function load() {
  clearTimeout(timer)
  error.value = ''
  if (!selectedStoreId.value || !auth.isAuthenticated) { voice.value = null; entries.value = []; return }
  const storeId = selectedStoreId.value
  try {
    const [nextVoice, journal] = await Promise.all([copyApi.voice(storeId), copyApi.journal(storeId, 5)])
    if (storeId !== selectedStoreId.value) return
    voice.value = nextVoice
    entries.value = journal.items || []
    if (learning.value) timer = setTimeout(refreshVoice, 3000)
  } catch (e) { error.value = e.message }
}

async function refreshVoice() {
  try {
    voice.value = await copyApi.voice(selectedStoreId.value)
    if (learning.value) timer = setTimeout(refreshVoice, 3000)
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
    timer = setTimeout(refreshVoice, 3000)
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

function day(value) {
  const date = new Date(value)
  return `${String(date.getMonth() + 1).padStart(2, '0')}/${String(date.getDate()).padStart(2, '0')}`
}

function firstLine(entry) { return (entry.title || entry.body || '').split('\n').find(line => line.trim()) || '' }

watch(selectedStoreId, load, { immediate: true })
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <div class="cw-page cw-home">
    <header class="cw-home-head">
      <h1>文案创作</h1>
      <div>
        <RouterLink to="/copy/create?history=1" class="cw-ghost"><History :size="16" :stroke-width="1.7" aria-hidden="true" />创作记录</RouterLink>
        <RouterLink to="/copy/create" class="cw-primary-link">直接写一条<ChevronRight :size="16" :stroke-width="2" aria-hidden="true" /></RouterLink>
      </div>
    </header>

    <section class="cw-starts" aria-label="选一个平台开始">
      <RouterLink v-for="(platform, index) in copyPlatforms" :key="platform.id" :to="`/copy/create?platform=${platform.id}`" class="cw-card cw-start">
        <span class="cw-start-no">{{ ['壹', '贰', '叁'][index] }}</span>
        <span class="cw-start-main"><strong>{{ platform.label }}</strong><small>{{ intros[platform.id] }}</small></span>
        <span class="cw-start-go">去写<ChevronRight :size="15" :stroke-width="2" aria-hidden="true" /></span>
      </RouterLink>
    </section>

    <p v-if="error" class="cw-error" role="alert">{{ error }}</p>

    <div class="cw-home-row">
      <section id="voice" class="cw-voice-card" aria-label="我的口吻">
        <div class="cw-voice-card-head">
          <h2>我的口吻</h2>
          <span v-if="voice?.status === 'READY'">已学会 · {{ voice.samples.length }} 条</span>
          <span v-else-if="learning">正在学…</span>
        </div>
        <template v-if="!editing">
          <p v-if="voice?.summary" class="cw-voice-summary">{{ voice.summary }}</p>
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
      </section>
    </div>
  </div>
</template>
