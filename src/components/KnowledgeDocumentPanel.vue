<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Check, FileText, RefreshCw, Trash2, X } from 'lucide-vue-next'
import { knowledgeApi } from '../services/knowledgeApi'

// 一份文档整理出来的建议：品牌/门店字段的新写法、新问答。逐条采纳或忽略，采纳才会真正写进去。
const props = defineProps({
  documentId: { type: Number, required: true },
  ownerLabel: { type: String, required: true },
  // 同一归属下、没归档的问答集；问答建议只能放进这些集。
  sets: { type: Array, required: true },
  canEdit: { type: Boolean, default: true },
})
const emit = defineEmits(['changed', 'deleted', 'entries-added', 'create-set'])

const detail = ref(null)
const loadError = ref('')
const error = ref('')
const notice = ref('')
const busy = ref(false)
const drafts = ref({})
const targetSetId = ref(null)
const showHandled = ref(false)
const confirmingDelete = ref(false)
let pollTimer = null
let loadVersion = 0

const document = computed(() => detail.value?.document || null)
const pending = computed(() => (detail.value?.suggestions || []).filter(item => item.status === 'PENDING'))
const handled = computed(() => (detail.value?.suggestions || []).filter(item => item.status !== 'PENDING'))
const fieldSuggestions = computed(() => pending.value.filter(item => item.kind === 'FIELD'))
const qaSuggestions = computed(() => pending.value.filter(item => item.kind === 'QA'))
const fieldTarget = computed(() => document.value?.brandId ? '品牌资料' : '门店资料')

function stopPolling() {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = null
}

async function load() {
  stopPolling()
  const version = ++loadVersion
  try {
    const loaded = await knowledgeApi.getDocument(props.documentId)
    if (version !== loadVersion) return
    // 整理刚结束（或失败）时，让左侧列表跟着更新状态和待确认数量。
    if (detail.value?.document.status === 'PROCESSING' && loaded.document.status !== 'PROCESSING') emit('changed')
    detail.value = loaded
    loadError.value = ''
    for (const item of loaded.suggestions) {
      if (!drafts.value[item.id]) drafts.value[item.id] = { value: item.value || '', question: item.question || '', answer: item.answer || '' }
    }
    if (loaded.document.status === 'PROCESSING') pollTimer = setTimeout(load, 3000)
  } catch (failure) {
    if (version === loadVersion) loadError.value = failure.message || '文档读取失败，请重试。'
  }
}

watch(() => props.documentId, () => {
  detail.value = null
  drafts.value = {}
  error.value = ''
  notice.value = ''
  showHandled.value = false
  confirmingDelete.value = false
  load()
}, { immediate: true })
watch(() => props.sets, sets => {
  if (!sets.some(set => set.id === targetSetId.value)) targetSetId.value = sets[0]?.id ?? null
}, { immediate: true })
onBeforeUnmount(() => { loadVersion++; stopPolling() })

async function run(task, message) {
  if (busy.value) return false
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await task()
    notice.value = message
    return true
  } catch (failure) {
    error.value = failure.message || '操作失败，请重试。'
    return false
  } finally {
    busy.value = false
  }
}

function markHandled(updated) {
  detail.value = {
    ...detail.value,
    suggestions: detail.value.suggestions.map(item => item.id === updated.id ? { ...item, ...updated, currentValue: item.currentValue } : item),
  }
}

async function acceptField(item) {
  const value = drafts.value[item.id].value.trim()
  const done = await run(async () => markHandled(await knowledgeApi.acceptSuggestion(item.id, { value })), `已写进${fieldTarget.value}的「${item.fieldLabel}」。`)
  if (done) emit('changed')
}
async function acceptQa(item) {
  const draft = drafts.value[item.id]
  if (!targetSetId.value) { error.value = '请先选一个问答集。'; return }
  const setId = targetSetId.value
  const done = await run(async () => markHandled(await knowledgeApi.acceptSuggestion(item.id, {
    question: draft.question.trim(), answer: draft.answer.trim(), knowledgeSetId: setId,
  })), '已加入问答集，并且已启用。')
  if (done) emit('entries-added', setId)
}
async function acceptAllQa() {
  if (!targetSetId.value || busy.value) return
  const setId = targetSetId.value
  const items = [...qaSuggestions.value]
  let accepted = 0
  const done = await run(async () => {
    for (const item of items) {
      const draft = drafts.value[item.id]
      markHandled(await knowledgeApi.acceptSuggestion(item.id, { question: draft.question.trim(), answer: draft.answer.trim(), knowledgeSetId: setId }))
      accepted++
    }
  }, `已把 ${items.length} 条问答加入问答集，并且已启用。`)
  if (accepted) emit('entries-added', setId)
  if (!done && accepted) error.value = `已加入 ${accepted} 条，剩下的没有成功：${error.value}`
}
async function dismiss(item) {
  const done = await run(async () => markHandled(await knowledgeApi.dismissSuggestion(item.id)), '已忽略。')
  if (done) emit('changed')
}
async function retry() {
  const done = await run(() => knowledgeApi.retryDocument(props.documentId), '已重新开始整理。没处理的建议会被新的结果替换，已采纳的不受影响。')
  if (done) { emit('changed'); load() }
}
async function remove() {
  const done = await run(() => knowledgeApi.deleteDocument(props.documentId), '文档已删除。')
  if (done) emit('deleted')
}
</script>

<template>
  <main class="kd-panel">
    <p v-if="loadError" class="kd-notice kd-error" role="alert">{{ loadError }} <button type="button" class="kd-button" @click="load">重试</button></p>
    <p v-else-if="!document" class="kd-muted" role="status">正在读取文档…</p>
    <template v-else>
      <div class="kd-head">
        <div>
          <p class="kd-owner"><FileText :size="14" aria-hidden="true" />{{ ownerLabel }}的文档</p>
          <h2>{{ document.name }}</h2>
        </div>
        <div v-if="canEdit" class="kd-actions">
          <button v-if="document.status !== 'PROCESSING'" type="button" class="kd-button" :disabled="busy" @click="retry"><RefreshCw :size="14" />重新整理</button>
          <button type="button" class="kd-button" :disabled="busy" @click="confirmingDelete = true"><Trash2 :size="14" />删除</button>
        </div>
      </div>

      <div v-if="confirmingDelete" class="kd-notice kd-error" role="alertdialog" aria-label="确认删除文档">
        <span>删除这份文档和它还没处理的建议？已经采纳的内容不受影响，原文件仍在素材库里。</span>
        <span class="kd-actions"><button type="button" class="kd-button" :disabled="busy" @click="confirmingDelete = false">取消</button><button type="button" class="kd-button kd-danger" :disabled="busy" @click="remove">删除</button></span>
      </div>

      <p v-if="notice" class="kd-notice" role="status">{{ notice }}</p>
      <p v-if="error" class="kd-notice kd-error" role="alert">{{ error }}</p>

      <div v-if="document.status === 'PROCESSING'" class="kd-state" role="status">
        <span class="kd-spinner" aria-hidden="true" />
        <div><strong>正在阅读和整理</strong><p>通常需要半分钟到两分钟，可以先去做别的，整理好了这里会自动更新。</p></div>
      </div>
      <div v-else-if="document.status === 'FAILED'" class="kd-state kd-failed" role="alert">
        <div><strong>没有整理出来</strong><p>{{ document.errorMessage }}</p></div>
      </div>

      <template v-else>
        <p v-if="document.truncated" class="kd-notice">文档比较长，这次只读了前 3 万字。后面的内容可以拆成另一份文档上传。</p>
        <p v-if="!pending.length" class="kd-state">{{ handled.length ? '这份文档的建议都处理完了。' : '这份文档里没有找到能用的新资料，已有的资料和文档一致。' }}</p>

        <section v-if="fieldSuggestions.length" class="kd-section">
          <h3>{{ fieldTarget }}建议 <small>采纳后会写进{{ fieldTarget }}，直播讲解和创作都会参考</small></h3>
          <article v-for="item in fieldSuggestions" :key="item.id" class="kd-card">
            <strong class="kd-label">{{ item.fieldLabel }}</strong>
            <div class="kd-compare">
              <div><span>现在</span><p :class="{ empty: !item.currentValue }">{{ item.currentValue || '未填写' }}</p></div>
              <label><span>文档里的说法（可以改）</span><textarea v-model="drafts[item.id].value" rows="3" :disabled="busy || !canEdit" /></label>
            </div>
            <div v-if="canEdit" class="kd-card-actions">
              <button type="button" class="kd-button" :disabled="busy" @click="dismiss(item)"><X :size="14" />忽略</button>
              <button type="button" class="kd-button kd-primary" :disabled="busy || !drafts[item.id].value.trim()" @click="acceptField(item)"><Check :size="14" />{{ item.currentValue ? '替换' : '填进去' }}</button>
            </div>
          </article>
        </section>

        <section v-if="qaSuggestions.length" class="kd-section">
          <h3>问答建议 <small>采纳后直接启用，之后开播的直播会用到</small></h3>
          <div v-if="canEdit" class="kd-target">
            <template v-if="sets.length">
              <label>放进
                <select v-model="targetSetId" :disabled="busy" aria-label="放进哪个问答集">
                  <option v-for="set in sets" :key="set.id" :value="set.id">{{ set.name }}</option>
                </select>
              </label>
              <button type="button" class="kd-button" :disabled="busy" @click="acceptAllQa">全部采纳 {{ qaSuggestions.length }} 条</button>
            </template>
            <template v-else>
              <span>{{ ownerLabel }}还没有问答集，先建一个再采纳。</span>
              <button type="button" class="kd-button kd-primary" @click="emit('create-set')">新建问答集</button>
            </template>
          </div>
          <article v-for="item in qaSuggestions" :key="item.id" class="kd-card">
            <label><span>问题</span><input v-model="drafts[item.id].question" maxlength="500" :disabled="busy || !canEdit" /></label>
            <label><span>回答</span><textarea v-model="drafts[item.id].answer" rows="2" :disabled="busy || !canEdit" /></label>
            <div v-if="canEdit" class="kd-card-actions">
              <button type="button" class="kd-button" :disabled="busy" @click="dismiss(item)"><X :size="14" />忽略</button>
              <button type="button" class="kd-button kd-primary" :disabled="busy || !sets.length || !drafts[item.id].question.trim() || !drafts[item.id].answer.trim()" @click="acceptQa(item)"><Check :size="14" />采纳</button>
            </div>
          </article>
        </section>

        <section v-if="handled.length" class="kd-section">
          <button type="button" class="kd-link" :aria-expanded="showHandled" @click="showHandled = !showHandled">已处理 {{ handled.length }} 条 {{ showHandled ? '收起' : '展开' }}</button>
          <ul v-if="showHandled" class="kd-handled">
            <li v-for="item in handled" :key="item.id">
              <span :class="item.status === 'ACCEPTED' ? 'ok' : 'off'">{{ item.status === 'ACCEPTED' ? '已采纳' : '已忽略' }}</span>
              {{ item.kind === 'FIELD' ? `${item.fieldLabel}：${item.value}` : item.question }}
            </li>
          </ul>
        </section>
      </template>
      <p v-if="!canEdit" class="kd-muted">品牌的资料和问答由管理员确认。</p>
    </template>
  </main>
</template>

<style scoped>
.kd-panel{min-width:0;padding:18px 20px 22px;border:1px solid var(--color-border-subtle);border-radius:8px;background:var(--color-bg-surface)}
.kd-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:14px}
.kd-owner{display:flex;align-items:center;gap:6px;margin:0 0 4px;color:var(--color-text-muted);font-size:12px}
.kd-head h2{margin:0;font:700 20px/1.3 var(--font-serif);overflow-wrap:anywhere}
.kd-actions,.kd-card-actions{display:flex;flex-wrap:wrap;gap:8px}
.kd-card-actions{justify-content:flex-end}
.kd-button{display:inline-flex;align-items:center;justify-content:center;gap:6px;min-height:34px;padding:0 12px;border:1px solid var(--color-border-subtle);border-radius:4px;color:var(--color-primary);background:var(--color-bg-surface);font-size:13px;white-space:nowrap;cursor:pointer}
.kd-button:hover:not(:disabled){border-color:var(--color-border-strong)}
.kd-button:disabled{cursor:not-allowed;opacity:.55}
.kd-primary{border-color:var(--color-accent);color:var(--color-on-accent);background:var(--color-accent);font-weight:600}
.kd-danger{border-color:var(--color-error);color:var(--color-on-accent);background:var(--color-error);font-weight:600}
.kd-primary:hover:not(:disabled){border-color:var(--color-accent-hover);background:var(--color-accent-hover)}
.kd-button:focus-visible,.kd-link:focus-visible{outline:2px solid var(--color-accent);outline-offset:2px}
.kd-notice{display:flex;align-items:center;justify-content:space-between;gap:10px;margin:0 0 12px;padding:10px 12px;border-radius:6px;background:var(--color-bg-subtle);color:var(--color-primary);font-size:13px;line-height:1.6}
.kd-error{color:var(--color-error);background:color-mix(in srgb,var(--color-error) 8%,var(--color-bg-surface))}
.kd-muted{margin:12px 0 0;color:var(--color-text-muted);font-size:13px}
.kd-state{display:flex;align-items:center;gap:14px;margin:6px 0 0;padding:18px;border-radius:6px;background:var(--color-bg-canvas);color:var(--color-text-muted);font-size:13px;line-height:1.6}
.kd-state strong{color:var(--color-primary);font-size:14px}
.kd-state p{margin:4px 0 0}
.kd-failed strong{color:var(--color-error)}
.kd-spinner{flex:none;width:22px;height:22px;border:3px solid var(--color-border-subtle);border-top-color:var(--color-accent);border-radius:50%;animation:kd-spin 1s linear infinite}
@keyframes kd-spin{to{transform:rotate(360deg)}}
@media (prefers-reduced-motion:reduce){.kd-spinner{animation:none}}
.kd-section{display:grid;gap:10px;margin-top:18px}
.kd-section h3{margin:0;font-size:15px;font-weight:700}
.kd-section h3 small{margin-left:6px;color:var(--color-text-muted);font-size:12px;font-weight:400}
.kd-card{display:grid;gap:10px;padding:13px 14px;border:1px solid var(--color-border-subtle);border-radius:6px}
.kd-label{font-size:13px}
.kd-compare{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1.3fr);gap:12px}
.kd-compare p{margin:0;color:var(--color-text-muted);font-size:13px;line-height:1.7;white-space:pre-wrap;overflow-wrap:anywhere}
.kd-compare p.empty{font-style:italic}
.kd-card label,.kd-compare>div{display:grid;gap:5px;align-content:start}
.kd-card label>span,.kd-compare span{color:var(--color-text-muted);font-size:12px;font-weight:600}
.kd-card input,.kd-card textarea,.kd-target select{width:100%;padding:8px 10px;border:1px solid var(--color-border-subtle);border-radius:4px;color:var(--color-primary);background:var(--color-bg-surface);font:13px/1.6 var(--font-sans);resize:vertical}
.kd-card input:focus,.kd-card textarea:focus,.kd-target select:focus{border-color:var(--color-accent);outline:0}
.kd-target{display:flex;flex-wrap:wrap;align-items:center;gap:10px;padding:10px 12px;border-radius:6px;background:var(--color-bg-canvas);color:var(--color-text-muted);font-size:13px}
.kd-target label{display:flex;align-items:center;gap:8px;flex:1 1 220px}
.kd-target select{width:auto;flex:1;min-height:34px}
.kd-link{justify-self:start;padding:0;border:0;color:var(--color-accent-text);background:transparent;font-size:13px;cursor:pointer}
.kd-handled{display:grid;gap:6px;margin:0;padding:0;list-style:none;color:var(--color-text-muted);font-size:13px}
.kd-handled li{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.kd-handled span{margin-right:6px;padding:1px 6px;border-radius:4px;font-size:12px}
.kd-handled span.ok{color:var(--color-success);background:color-mix(in srgb,var(--color-success) 9%,var(--color-bg-surface))}
.kd-handled span.off{background:var(--color-bg-canvas)}
@media (max-width:860px){
  .kd-panel{padding:14px}
  .kd-head{flex-direction:column}
  .kd-compare{grid-template-columns:1fr}
}
</style>
