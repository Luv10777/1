<script setup>
import { computed, nextTick, ref } from 'vue'
import { Listbox, ListboxButton, ListboxOptions, ListboxOption } from '@headlessui/vue'
import {
  Archive, ArrowDown, BellOff, BookOpen, Bot, Check, ChevronDown, MessageSquare,
  Clock3, Edit3, FileText, Leaf, MoreHorizontal,
  Paperclip, PanelRightClose, PanelRightOpen, PenLine, Plus, RotateCcw, Search,
  Send, Settings2, Smile, Sparkles, Tag, WandSparkles, Zap, X,
} from 'lucide-vue-next'

const channels = [
  { key: 'all', label: '全部平台', mark: '全', className: 'channel-all', logo: '' },
  { key: 'douyin', label: '抖音', mark: '音', className: 'channel-douyin', logo: '/images/platform-logos/douyin.png' },
  { key: 'red', label: '小红书', mark: '书', className: 'channel-red', logo: '/images/platform-logos/xiaohongshu.png' },
  { key: 'meituan', label: '美团 / 点评', mark: '团', className: 'channel-meituan', logo: '/images/platform-logos/meituan.png' },
  { key: 'wechat', label: '视频号', mark: '微', className: 'channel-wechat', logo: '' },
]

const states = [
  { key: 'all', label: '全部对话' },
  { key: 'todo', label: '未读' },
  { key: 'draft', label: 'AI 已拟稿' },
  { key: 'done', label: '已回复' },
]

const conversations = ref([
  { id: 1, name: '周芷若', initials: '周', avatar: 'avatar-sage', channel: 'red', channelLabel: '小红书', time: '刚刚', intent: '包厢预订', unread: 2, draft: true, preview: '周六晚上 6 点还有四人景观位吗？可以带小型宠物吗？', tags: ['周末', '景观位'], level: '老客 · 3次到店' },
  { id: 2, name: '陈先生', initials: '陈', avatar: 'avatar-ink', channel: 'douyin', channelLabel: '抖音', time: '8分钟前', intent: '团购优惠', unread: 1, draft: true, preview: '双人餐现在还有团购吗？周末去需要预约吗', tags: ['双人餐'], level: '新客' },
  { id: 3, name: '南风知我意', initials: '南', avatar: 'avatar-clay', channel: 'meituan', channelLabel: '美团', time: '21分钟前', intent: '差评安抚', unread: 0, draft: false, preview: '上次用餐体验不太好，希望这次可以安排安静一点的位置。', tags: ['服务体验'], level: '回访客' },
  { id: 4, name: '木棉花开', initials: '木', avatar: 'avatar-moss', channel: 'wechat', channelLabel: '视频号', time: '昨天', intent: '探店合作', unread: 0, draft: true, preview: '我们是本地生活账号，想和您聊聊秋季探店合作。', tags: ['合作'], level: '潜客' },
  { id: 5, name: '林间小鹿', initials: '鹿', avatar: 'avatar-sand', channel: 'red', channelLabel: '小红书', time: '昨天', intent: '生日布置', unread: 0, draft: false, preview: '下周生日想要简单布置，可以帮忙准备吗？', tags: ['生日'], level: '熟客' },
  { id: 6, name: '苏打汽水', initials: '苏', avatar: 'avatar-blue', channel: 'douyin', channelLabel: '抖音', time: '周二', intent: '停车咨询', unread: 0, draft: false, preview: '请问店门口方便停车吗？', tags: ['到店'], level: '新客' },
])

// 未选择会话时，不渲染客户详情或输入区。
const selectedId = ref(null)
const activeChannel = ref('all')
const activeChannelLabel = computed(() => channels.find(channel => channel.key === activeChannel.value)?.label)
const activeState = ref('all')
const searchTerm = ref('')
const draftVisible = ref(true)
const draftText = ref('周六晚上 6 点目前还有一席临窗的四人景观位，可以带小型宠物同行。我们会为您安排靠近竹林、相对安静的位置，宠物请全程使用牵引或航空包哦。\n\n如果您喜欢秋日风味，也可以提前试试我们的「秋蟹双人餐」，现点现蒸，帮您一并留好。')
const selectedTone = ref('亲切热情')
const composerText = ref('')
const isEditingDraft = ref(false)
const rightRailOpen = ref(true)
const sending = ref(false)
const messages = ref([
  { id: 1, side: 'customer', text: '你好呀，想问一下周六晚上 6 点还有没有四人景观位？', time: '14:32', source: '小红书私信' },
  { id: 2, side: 'customer', text: '另外可以带小型宠物吗？是一只很乖的比熊。', time: '14:32', source: '小红书私信' },
  { id: 3, side: 'merchant', text: '芷若您好，周末的景观位需要提前帮您看一下，我这边马上为您确认。', time: '14:35', source: '商家手动发出' },
])

const selectedConversation = computed(() => conversations.value.find(item => item.id === selectedId.value))
const channelConversations = computed(() => conversations.value.filter(item => activeChannel.value === 'all' || item.channel === activeChannel.value))
function matchesState(item, state) {
  return state === 'all' || (state === 'draft' ? item.draft : state === 'todo' ? item.unread > 0 : item.unread === 0 && !item.draft)
}
const stateCount = (state) => channelConversations.value.filter(item => matchesState(item, state)).length
const filteredConversations = computed(() => conversations.value.filter(item => {
  const matchChannel = activeChannel.value === 'all' || item.channel === activeChannel.value
  const matchState = activeState.value === 'all' || (activeState.value === 'draft' ? item.draft : activeState.value === 'todo' ? item.unread > 0 : item.unread === 0 && !item.draft)
  const q = searchTerm.value.trim().toLowerCase()
  const matchSearch = !q || `${item.name}${item.preview}${item.intent}`.toLowerCase().includes(q)
  return matchChannel && matchState && matchSearch
}))

const toneDrafts = {
  '亲切热情': '周六晚上 6 点目前还有一席临窗的四人景观位，可以带小型宠物同行。我们会为您安排靠近竹林、相对安静的位置，宠物请全程使用牵引或航空包哦。\n\n如果您喜欢秋日风味，也可以提前试试我们的「秋蟹双人餐」，现点现蒸，帮您一并留好。',
  '沉稳得体': '您好，周六 18:00 目前尚有一席四人景观位，可携带小型宠物入店。我们会为您安排相对安静的临窗位置，宠物需全程使用牵引或航空包。\n\n另有秋季双人餐可提前预订，如有需要我可以一并为您备注。',
  '干练直接': '有的，周六 18:00 还有四人景观位，可携带小型宠物。宠物请使用牵引或航空包，我们会安排靠竹林的安静位置。秋蟹双人餐也可一起预订。',
}

const sessions = {
  1: { messages: messages.value, draftText: draftText.value, draftVisible: true, composerText: '', tone: '亲切热情' },
}

// 切换或关闭都保留当前会话的消息与未发送内容。
function saveCurrentSession() {
  if (selectedId.value !== null) {
    sessions[selectedId.value] = { messages: messages.value, draftText: draftText.value, draftVisible: draftVisible.value, composerText: composerText.value, tone: selectedTone.value }
  }
}

function closeConversation() {
  if (sending.value) return
  saveCurrentSession()
  const conversationId = selectedId.value
  selectedId.value = null
  isEditingDraft.value = false
  nextTick(() => document.querySelector(`[data-conversation-id="${conversationId}"]`)?.focus())
}

function selectConversation(item) {
  if (selectedId.value === item.id || sending.value) return
  saveCurrentSession()
  selectedId.value = item.id
  isEditingDraft.value = false
  const saved = sessions[item.id]
  if (saved) {
    messages.value = saved.messages
    draftText.value = saved.draftText
    draftVisible.value = saved.draftVisible
    composerText.value = saved.composerText
    selectedTone.value = saved.tone
    return
  }
  messages.value = [{ id: Date.now(), side: 'customer', text: item.preview, time: item.time, source: `${item.channelLabel}私信` }]
  composerText.value = ''
  selectedTone.value = '亲切热情'
  if (item.id !== 1) {
    draftVisible.value = item.draft
    draftText.value = item.id === 2 ? '陈先生您好，双人餐团购目前仍有库存，周末到店建议提前预约，我们可以帮您优先安排靠窗位。' : '您好，感谢您的留言。我已经记下您的需求，会在确认后第一时间回复您。'
  } else {
    draftVisible.value = true
    draftText.value = toneDrafts[selectedTone.value]
  }
}

function setTone(tone) {
  selectedTone.value = tone
  draftText.value = toneDrafts[tone]
}

async function sendDraft() {
  if (sending.value || !draftVisible.value) return
  sending.value = true
  await new Promise(resolve => setTimeout(resolve, 520))
  messages.value.push({ id: Date.now(), side: 'merchant', text: draftText.value, time: '14:38', source: '一方志 · 经商家核发' })
  const current = selectedConversation.value
  if (current) { current.draft = false; current.unread = 0; current.preview = draftText.value.split('\n')[0] }
  draftVisible.value = false
  sending.value = false
}

function refineDraft() {
  composerText.value = draftText.value
  draftVisible.value = false
  nextTick(() => document.querySelector('.composer-input')?.focus())
}

function sendComposer() {
  const text = composerText.value.trim()
  if (!text) return
  messages.value.push({ id: Date.now(), side: 'merchant', text, time: '14:39', source: '商家手动发出' })
  composerText.value = ''
}

function addQuickReply(text) {
  composerText.value = `${composerText.value}${composerText.value ? ' ' : ''}${text}`
}
</script>

<template>
  <div class="messages-workspace" :class="{ 'rail-collapsed': !rightRailOpen, 'no-conversation': !selectedConversation }">
    <section class="messages-grid">
      <aside class="inbox-panel panel-paper">
        <div class="inbox-toolbar">
          <h2 class="inbox-label">对话</h2>
          <!-- Listbox handles keyboard navigation, Escape, outside clicks and focus restoration. -->
          <Listbox v-slot="{ open }" v-model="activeChannel" as="div" class="platform-select-wrap">
            <ListboxButton class="platform-select" :class="{ 'is-open': open }" aria-label="筛选平台">
              <span>{{ activeChannelLabel }}</span>
              <ChevronDown :size="14" class="platform-chevron" :class="{ 'is-open': open }" aria-hidden="true" />
            </ListboxButton>
            <Transition name="platform-menu">
              <ListboxOptions class="platform-options" aria-label="选择平台">
                <ListboxOption v-for="channel in channels" :key="channel.key" v-slot="{ active, selected }" :value="channel.key" as="template">
                  <li class="platform-option" :class="{ 'is-active': active, 'is-selected': selected }">
                    <span class="platform-option-mark" :class="channel.className" aria-hidden="true">
                      <img v-if="channel.logo" :src="channel.logo" alt="" />
                      <span v-else>{{ channel.mark }}</span>
                    </span>
                    <span>{{ channel.label }}</span>
                    <Check v-if="selected" :size="15" class="platform-option-check" aria-hidden="true" />
                  </li>
                </ListboxOption>
              </ListboxOptions>
            </Transition>
          </Listbox>
        </div>
        <div class="state-tabs"><button v-for="state in states" :key="state.key" :class="['state-tab', { active: activeState === state.key, draft: state.key === 'draft' }]" :aria-pressed="activeState === state.key" @click="activeState = state.key">{{ state.label }} <b>{{ stateCount(state.key) }}</b></button></div>
        <div class="inbox-search-row"><label class="conversation-search"><Search :size="15" /><input v-model="searchTerm" placeholder="搜索会话" /><kbd>⌘ K</kbd></label><button class="filter-button" title="筛选"><Tag :size="14" /></button></div>
        <div class="conversation-list">
          <button v-for="item in filteredConversations" :key="item.id" :data-conversation-id="item.id" :class="['conversation-card', { selected: selectedId === item.id }]" @click="selectConversation(item)">
            <span :class="['customer-avatar', item.avatar]">{{ item.initials }}<i :class="['source-dot', `source-${item.channel}`]" /></span>
            <span class="conversation-body"><span class="conversation-name-row"><strong>{{ item.name }}</strong><small>{{ item.time }}</small></span><span class="conversation-meta"><em :class="`platform-${item.channel}`">{{ item.channelLabel }}</em><em class="intent-tag">{{ item.intent }}</em><span v-if="item.level" class="customer-level">{{ item.level }}</span></span><span class="conversation-preview">{{ item.preview }}</span><span v-if="item.draft" class="draft-hint"><Leaf :size="13" /> 灵犀拟稿待审</span></span>
            <span v-if="item.unread" class="unread-badge">{{ item.unread }}</span>
          </button>
          <p v-if="!filteredConversations.length" class="empty-inbox">没有符合条件的会话</p>
        </div>
        <div class="inbox-foot"><span><span class="status-pip" /> 共 {{ filteredConversations.length }} 个对话</span><button @click="activeChannel = 'all'; activeState = 'all'; searchTerm = ''">查看全部 <ArrowDown :size="13" /></button></div>
      </aside>

      <main v-if="selectedConversation" class="chat-panel panel-paper">
        <header class="chat-header"><div class="chat-identity"><span class="customer-avatar avatar-sage big">{{ selectedConversation.initials }}</span><div><div class="chat-title-row"><h2>{{ selectedConversation.name }}</h2><span :class="`platform-${selectedConversation.channel}`">{{ selectedConversation.channelLabel }}</span><span class="preference-tag"><Tag :size="12" /> {{ selectedConversation.intent }}</span></div><p>{{ selectedConversation.level }} · 最近咨询 2 次 · 常在午后活跃</p></div></div><div class="chat-actions"><button class="header-icon-button"><BellOff :size="15" /> 转人工静音</button><button class="header-icon-button"><Settings2 :size="15" /> 知识库设置</button><button class="round-icon"><MoreHorizontal :size="17" /></button><button class="round-icon close-conversation" type="button" aria-label="关闭会话" title="关闭会话" :disabled="sending" @click="closeConversation"><X :size="17" /></button></div></header>
        <div class="chat-log">
          <div class="day-divider"><span>今天 14:32</span></div>
          <article v-for="message in messages" :key="message.id" :class="['message-row', message.side]">
            <span v-if="message.side === 'customer'" class="message-mini-avatar avatar-sage">{{ selectedConversation.initials }}</span>
            <div class="message-cluster"><div class="message-bubble">{{ message.text }}</div><div class="message-meta"><span>{{ message.time }}</span><span>·</span><span>{{ message.source }}</span><Check v-if="message.side === 'merchant'" :size="13" /></div></div>
          </article>
          <transition name="draft-fade">
            <section v-if="draftVisible" class="ai-draft-card">
              <div class="draft-card-header"><div class="draft-title"><span class="ai-orb"><Leaf :size="15" /></span><div><strong>一方灵犀 · 拟稿笺</strong><small>已关联本店《周末包间预订须知 & 秋季双人餐单》</small></div></div><button class="regenerate-button" @click="draftText = toneDrafts[selectedTone]"><RotateCcw :size="14" /> 重新推演</button></div>
              <div class="draft-paper"><div class="draft-paper-stamp">灵犀</div><div v-if="!isEditingDraft" class="draft-copy" @click="isEditingDraft = true">{{ draftText }}</div><textarea v-else v-model="draftText" class="draft-inline-editor" @blur="isEditingDraft = false" /><button v-if="!isEditingDraft" class="inline-edit-button" @click="isEditingDraft = true"><Edit3 :size="13" /> 点击文字可直接修改</button></div>
              <div class="tone-row"><span>调整语气</span><button v-for="tone in Object.keys(toneDrafts)" :key="tone" :class="['tone-pill', { active: selectedTone === tone }]" @click="setTone(tone)">{{ tone }}</button></div>
              <div class="draft-actions"><button class="draft-secondary" @click="draftVisible = false"><Archive :size="15" /> 暂缓</button><button class="draft-refine" @click="refineDraft"><WandSparkles :size="15" /> 精修 / 上屏</button><button class="draft-confirm" :disabled="sending" @click="sendDraft"><span class="stamp-mark">印</span>{{ sending ? '正在钤印…' : '钤印发送' }}</button></div>
            </section>
          </transition>
        </div>
        <footer class="composer-area"><div class="composer-tools"><button title="上传附件"><Paperclip :size="16" /></button><button title="表情"><Smile :size="16" /></button><button title="优惠券" @click="addQuickReply('本周秋蟹双人餐 · 到店可核销')"><Zap :size="15" /></button><button title="菜单卡片" @click="addQuickReply('为您附上本店菜单，欢迎提前选餐')"><FileText :size="15" /></button><span class="composer-divider" /><button class="quick-insert" @click="addQuickReply('地址：青石巷 18 号，导航搜索「一方·竹里」即可抵达')">常用话术 <ChevronDown :size="13" /></button><span class="composer-spacer" /><span class="composer-mode"><Bot :size="14" /> 灵犀托管已开启</span></div><div class="composer-box"><textarea v-model="composerText" class="composer-input" rows="2" placeholder="写下你的回复，或让灵犀帮你润一润…" @keydown.ctrl.enter.prevent="sendComposer" /><button class="send-button" :disabled="!composerText.trim()" @click="sendComposer"><Send :size="16" /> 发送</button></div><div class="composer-hint"><span>Enter 换行 · ⌘ Enter 发送</span><span>本次会话已安全加密</span></div></footer>
      </main>
      <main v-else class="conversation-placeholder" aria-label="尚未选择会话">
        <MessageSquare :size="32" :stroke-width="1.25" aria-hidden="true" />
        <p>选择左侧会话，开始回复</p>
        <span>聊天记录与客户资料将在这里显示</span>
      </main>

      <aside v-if="selectedConversation && rightRailOpen" class="context-panel panel-paper">
        <div class="context-header"><div><h2>掌柜手记</h2><span>客户与知识上下文</span></div><button class="round-icon" @click="rightRailOpen = false"><PanelRightClose :size="16" /></button></div>
        <section class="profile-section"><div class="profile-section-title"><h3>本客小像</h3><button><Edit3 :size="13" /> 编辑</button></div><div class="customer-profile"><span class="customer-avatar avatar-sage large">{{ selectedConversation.initials }}</span><div><strong>{{ selectedConversation.name }}</strong><span>小红书 · {{ selectedConversation.level }}</span></div><span class="profile-score">A<small>熟客度</small></span></div><div class="profile-stats"><div><strong>3</strong><span>历史到店</span></div><div><strong>¥ 1,280</strong><span>累计消费</span></div><div><strong>86%</strong><span>核销意向</span></div></div><div class="note-box"><PenLine :size="14" /><span>老熟客，爱坐窗边靠竹林位。对宠物友好政策比较关注。</span></div></section>
        <section class="context-section"><div class="profile-section-title"><h3>知识依据</h3><button><BookOpen :size="13" /> 3 条</button></div><div class="grounding-card"><span class="grounding-icon"><FileText :size="15" /></span><div><strong>周末包间预订须知</strong><span>景观位 · 宠物规约 · 预约规则</span></div><Check :size="14" /></div><div class="grounding-card"><span class="grounding-icon green"><Sparkles :size="15" /></span><div><strong>秋季大闸蟹预售套餐</strong><span>秋蟹双人餐 · 现点现蒸</span></div><Check :size="14" /></div><div class="grounding-card"><span class="grounding-icon amber"><Clock3 :size="15" /></span><div><strong>今日门店状态</strong><span>晚市尚余 6 席 · 适合带宠</span></div><Check :size="14" /></div></section>
        <section class="context-section shortcuts"><div class="profile-section-title"><h3>话术锦囊</h3><button><MoreHorizontal :size="15" /></button></div><button @click="addQuickReply('地址：青石巷 18 号，导航搜索「一方·竹里」即可抵达')"><span class="shortcut-icon"><ArrowDown :size="14" /></span><span><strong>定位指引</strong><small>青石巷 18 号 · 距地铁 600m</small></span><Plus :size="14" /></button><button @click="addQuickReply('Wi-Fi：YIFANGZHI_GUEST，密码 88888888')"><span class="shortcut-icon"><Zap :size="14" /></span><span><strong>Wi-Fi 信息</strong><small>YIFANGZHI_GUEST</small></span><Plus :size="14" /></button><button @click="addQuickReply('为您附上本店菜单，欢迎提前选餐')"><span class="shortcut-icon"><FileText :size="14" /></span><span><strong>发送菜单</strong><small>秋季菜单 · 12 道时令菜</small></span><Plus :size="14" /></button></section>
        <div class="context-footer"><span class="status-pip" /> 灵犀依据已实时同步 <span>·</span> <button>查看全部知识库</button></div>
      </aside>
      <button v-if="selectedConversation && !rightRailOpen" class="rail-open-button" @click="rightRailOpen = true"><PanelRightOpen :size="16" /> 打开掌柜手记</button>
    </section>
  </div>
</template>

<style>
.messages-workspace{--paper:#f8f6f1;--ink:#1c3532;--ink-2:#31534e;--muted:#87928e;--line:#e5e3dc;--line-strong:#ced9d4;--sage:#edf4f2;--sage-2:#dfece8;--sage-ink:#1e4842;--cinnabar:#c43d2a;--gold:#ae7d35;min-height:100%;height:100%;margin:-36px -56px -28px;padding:12px 0 0;background:var(--paper);color:var(--ink);font-family:var(--font-sans);overflow:hidden}.messages-header{display:none}.messages-grid{display:grid;grid-template-columns:320px minmax(0,1fr) 320px;gap:0;width:100%;height:calc(100dvh - 68px);max-width:none;margin:0}.panel-paper{background:#fffdfa;border:0;border-right:1px solid var(--line);border-radius:0;box-shadow:none;min-height:0}.inbox-panel,.chat-panel,.context-panel{display:flex;flex-direction:column;overflow:hidden}.inbox-panel{background:#fbfaf6}.inbox-toolbar{display:flex;align-items:center;gap:8px;height:68px;padding:0 14px;border-bottom:1px solid var(--line)}.inbox-toolbar .channel-switcher{border:0;padding:0;flex:1}.channel-switcher{display:flex;gap:3px;overflow:auto}.channel-tab{display:flex;align-items:center;gap:6px;flex-shrink:0;padding:7px 6px;border:0;background:transparent;color:#87928e;font-size:10px;border-radius:8px;white-space:nowrap}.channel-tab.active{color:var(--ink);background:#f0f5f3}.channel-mark{display:grid;place-items:center;width:25px;height:25px;border-radius:7px;font:10px var(--font-serif);overflow:hidden}.channel-mark img{width:17px;height:17px;object-fit:contain}.channel-all{background:#e8efeb;color:#48665f}.channel-douyin{background:#eef1f2;color:#31515a}.channel-red{background:#f7e8e3;color:#9c4e42}.channel-meituan{background:#f6edcf;color:#a47725}.channel-wechat{background:#e4f1e8;color:#4c8762}.state-tabs{display:flex;align-items:center;gap:4px;padding:11px 14px 8px;border-bottom:1px solid var(--line)}.state-tab{border:0;background:transparent;color:#8b9692;font-size:10px;padding:7px 8px;border-radius:7px;white-space:nowrap}.state-tab.active{background:#eaf2ef;color:var(--sage-ink)}.state-tab.draft.active{background:#f9ede9;color:var(--cinnabar)}.inbox-search-row{display:flex;gap:7px;padding:11px 14px 10px}.conversation-search{flex:1;margin:0}.filter-button{display:grid;place-items:center;width:34px;height:34px;border:1px solid var(--line);border-radius:9px;background:#fffdfa;color:#789089}.conversation-list{padding:0 8px;overflow:auto}.chat-panel{background:#fffefa;border-right:1px solid var(--line)}.context-panel{background:#fbfaf6;border-right:0}.context-header{padding:20px 17px 14px}.context-header h2{font-size:18px}.context-header span{display:block;margin-top:4px;color:#97a39e;font-size:9px}.chat-header{min-height:74px}.rail-collapsed .messages-grid{grid-template-columns:320px minmax(0,1fr)}
@media(max-width:1280px){.messages-workspace{margin:-24px -24px -28px;padding-inline:22px}.messages-grid{grid-template-columns:295px minmax(440px,1fr) 270px}.header-icon-button{font-size:0;padding:0;width:29px;justify-content:center}.header-icon-button svg{width:15px}.customer-level{display:none}}
@media(max-width:1050px){.messages-grid{grid-template-columns:285px minmax(0,1fr)}.context-panel{display:none}.rail-collapsed .messages-grid{grid-template-columns:285px minmax(0,1fr)}.chat-actions .header-icon-button{display:none}.messages-header h1{font-size:23px}}
@media(max-width:760px){.messages-workspace{margin:-20px -20px -90px;padding:18px 12px 90px;overflow:auto}.messages-header{align-items:flex-start;flex-direction:column;margin-bottom:13px}.messages-header-actions{width:100%;justify-content:flex-end}.messages-header h1{font-size:22px}.messages-grid,.rail-collapsed .messages-grid{display:flex;flex-direction:column;height:auto;min-height:0;gap:10px}.inbox-panel{height:380px;flex:0 0 380px}.chat-panel{height:680px;min-height:680px}.chat-header{padding:14px}.chat-actions{display:none}.chat-log{padding-inline:14px}.message-row{max-width:90%}.composer-area{padding-inline:12px}.context-panel{display:flex;height:auto}.conversation-list{padding-bottom:4px}}
[data-theme='dark'] .messages-workspace{--paper:#131b1a;--ink:#e7efeb;--ink-2:#c6d6d0;--muted:#8ea39b;--line:#30413d;--line-strong:#405954;--sage:#1b302d;--sage-2:#25443e;--sage-ink:#b8d3cd;--cinnabar:#d85a47;background:radial-gradient(circle at 70% -20%,#1c3532 0,transparent 34%),var(--paper)}
[data-theme='dark'] .panel-paper,[data-theme='dark'] .inbox-panel,[data-theme='dark'] .chat-panel,[data-theme='dark'] .context-panel,[data-theme='dark'] .soft-action,[data-theme='dark'] .icon-action,[data-theme='dark'] .profile-chip,[data-theme='dark'] .round-icon,[data-theme='dark'] .conversation-search,[data-theme='dark'] .composer-box,[data-theme='dark'] .draft-paper,[data-theme='dark'] .grounding-card{background:#1a2523;color:var(--ink);border-color:var(--line)}
[data-theme='dark'] .chat-log{background:linear-gradient(180deg,#1a2523 0,#16201e 100%)}
[data-theme='dark'] .message-bubble{background:#1d2926;color:#d8e6e0;border-color:var(--line)}
[data-theme='dark'] .merchant .message-bubble{background:#234039;color:#dcebe5;border-color:#3c655b}
[data-theme='dark'] .conversation-card.selected{background:#203a35;border-color:#42665c}
[data-theme='dark'] .conversation-card:hover{background:#1f302d}
[data-theme='dark'] .draft-copy,[data-theme='dark'] .draft-inline-editor{color:#35544d}

/* Layout correction: keep the three work areas inside the available page-scroll width. */
.messages-workspace{width:100%;max-width:none;margin:-24px 0 -28px;padding-inline:clamp(16px,2vw,34px);overflow:auto}
.messages-header,.messages-grid{width:100%;max-width:none}
.messages-grid{grid-template-columns:320px minmax(0,1fr) 320px}
.inbox-panel,.chat-panel,.context-panel{min-width:0}
.chat-header,.chat-identity,.chat-actions,.draft-card-header,.composer-tools{min-width:0}
.chat-title-row h2,.draft-title div,.conversation-body{min-width:0}
@media(max-width:1280px){.messages-grid,.rail-collapsed .messages-grid{grid-template-columns:300px minmax(0,1fr)}.context-panel{display:none}.messages-header h1{font-size:24px}}
@media(max-width:760px){.messages-workspace{width:auto;margin:-20px -20px -90px;padding-inline:12px}.messages-grid,.rail-collapsed .messages-grid{grid-template-columns:1fr;width:100%}}

/* Final full-bleed workspace pass. The shell already reserves the top navigation row. */
.messages-workspace{width:100%;height:100%;min-height:0;margin:0;padding:0;background:var(--paper);overflow:hidden}
.messages-grid{height:100%;min-height:0;grid-template-columns:320px minmax(0,1fr) 320px}
.inbox-toolbar{height:72px;padding:0 16px}
.channel-tab{min-height:46px;padding:6px 8px}
.channel-mark{width:30px;height:30px;border-radius:9px}
.channel-mark img{width:21px;height:21px}
.state-tabs{min-height:47px}
.inbox-search-row{padding-top:10px}
.chat-header{padding-inline:22px}
.context-panel{overflow-y:auto}
@media(max-width:1280px){.messages-grid,.rail-collapsed .messages-grid{grid-template-columns:300px minmax(0,1fr)}.context-panel{display:none}}
@media(max-width:760px){.messages-workspace{height:auto;min-height:100%;margin:0;padding:0;overflow:auto}.messages-grid,.rail-collapsed .messages-grid{display:flex;flex-direction:column;height:auto;min-height:0}.inbox-panel{height:390px;flex:0 0 390px}.chat-panel{height:680px;min-height:680px}.context-panel{display:flex;height:auto}}

/* Stable final rules for the full-bleed inbox layout. */
.messages-workspace,.messages-workspace *{box-sizing:border-box}
.messages-workspace{display:block;width:100%;height:100%;min-height:0;margin:0;padding:0;background:var(--paper);color:var(--ink);font-size:12px;line-height:1.5;overflow:hidden}
.messages-grid{display:grid;grid-template-columns:320px minmax(0,1fr) 320px;width:100%;height:100%;min-height:0;gap:0}
.panel-paper{min-width:0;min-height:0;border:0;border-right:1px solid var(--line);border-radius:0;box-shadow:none;background:#fffdfa}
.inbox-panel,.chat-panel,.context-panel{display:flex;flex-direction:column;min-width:0;min-height:0;overflow:hidden}
.inbox-panel{background:#fbfaf6}.chat-panel{background:#fffefa}.context-panel{background:#fbfaf6;border-right:0;overflow-y:auto}
.inbox-toolbar{display:flex;align-items:center;gap:8px;flex:0 0 72px;height:72px;padding:0 15px;border-bottom:1px solid var(--line)}
.inbox-toolbar .channel-switcher{flex:1;min-width:0;padding:0;border:0}
.channel-switcher{display:flex;align-items:center;gap:3px;min-width:0;overflow:auto;scrollbar-width:none}.channel-switcher::-webkit-scrollbar{display:none}
.channel-tab{display:flex;align-items:center;gap:6px;flex:0 0 auto;min-height:46px;padding:5px 7px;border:0;border-radius:9px;background:transparent;color:#87928e;font-size:10px;white-space:nowrap}.channel-tab:hover{background:#f1f5f3}.channel-tab.active{background:#e9f2ef;color:var(--ink)}
.channel-mark{display:grid;place-items:center;width:30px;height:30px;overflow:hidden;border-radius:9px;background:#e8efeb;color:#48665f;font:11px var(--font-serif)}.channel-mark img{display:block;width:22px;height:22px;object-fit:contain}.channel-douyin{background:#eef1f2}.channel-red{background:#f7e8e3}.channel-meituan{background:#f6edcf}.channel-wechat{background:#e4f1e8}
.round-icon{display:grid;place-items:center;flex:0 0 32px;width:32px;height:32px;padding:0;border:1px solid var(--line);border-radius:9px;background:#fffdfa;color:var(--ink-2);transition:background .18s,border-color .18s}.round-icon:hover{background:var(--sage);border-color:#b8d3cd}
.state-tabs{display:flex;align-items:center;gap:4px;flex:0 0 48px;height:48px;padding:8px 13px;border-bottom:1px solid var(--line);white-space:nowrap}.state-tab{height:31px;padding:0 9px;border:0;border-radius:7px;background:transparent;color:#8b9692;font-size:10px}.state-tab b{margin-left:3px;color:#a6afac;font-weight:500}.state-tab:hover{background:#f1f5f3}.state-tab.active{background:#eaf2ef;color:var(--sage-ink)}.state-tab.active b{color:var(--sage-ink)}.state-tab.draft.active{background:#f9ede9;color:var(--cinnabar)}.state-tab.draft.active b{color:var(--cinnabar)}
.inbox-search-row{display:flex;align-items:center;gap:7px;padding:11px 14px 10px}.conversation-search{display:flex;align-items:center;gap:7px;flex:1;height:35px;margin:0;padding:0 10px;border:1px solid var(--line);border-radius:9px;background:#fffdfa;color:#9aa39f}.conversation-search input{width:100%;min-width:0;border:0;outline:0;background:transparent;color:var(--ink);font:11px var(--font-sans)}.conversation-search input::placeholder{color:#a5aeaa}.conversation-search kbd{font:9px var(--font-mono);color:#adb5b1;white-space:nowrap}.filter-button{display:grid;place-items:center;width:35px;height:35px;padding:0;border:1px solid var(--line);border-radius:9px;background:#fffdfa;color:#789089}
.conversation-list{flex:1;min-height:0;padding:0 8px;overflow-y:auto;scrollbar-width:thin}.conversation-card{position:relative;display:flex;align-items:flex-start;width:100%;gap:10px;margin:0 0 3px;padding:12px 10px;border:1px solid transparent;border-radius:11px;background:transparent;color:var(--ink);text-align:left;transition:background .18s,border-color .18s}.conversation-card:hover{background:#f3f7f5}.conversation-card.selected{background:#edf5f2;border-color:#c4dcd4;box-shadow:inset 2px 0 #557c73}.customer-avatar{position:relative;display:grid;place-items:center;flex:0 0 32px;width:32px;height:32px;border-radius:50%;color:#fff;font:13px var(--font-serif)}.customer-avatar.big{width:40px;height:40px;font-size:16px}.customer-avatar.large{width:45px;height:45px;font-size:17px}.avatar-sage{background:linear-gradient(145deg,#a8bbb0,#5e786e)}.avatar-ink{background:linear-gradient(145deg,#6b7b7d,#263e40)}.avatar-clay{background:linear-gradient(145deg,#c98c72,#7d5148)}.avatar-moss{background:linear-gradient(145deg,#96a586,#536350)}.avatar-sand{background:linear-gradient(145deg,#cdb78e,#8b7456)}.avatar-blue{background:linear-gradient(145deg,#99b1b7,#4f6d74)}.source-dot{position:absolute;right:-1px;bottom:-1px;width:9px;height:9px;border:2px solid #fbfaf6;border-radius:50%}.source-red{background:#c26658}.source-douyin{background:#496b73}.source-meituan{background:#d29f31}.source-wechat{background:#6ea17c}.conversation-body{display:block;min-width:0;flex:1}.conversation-name-row,.conversation-meta{display:flex;align-items:center;gap:6px}.conversation-name-row strong{font-size:12px;font-weight:600}.conversation-name-row small{margin-left:auto;color:#a0aaa6;font-size:10px}.conversation-meta{margin-top:5px}.conversation-meta em{padding:2px 5px;border-radius:4px;font-size:9px;font-style:normal}.platform-red{background:#f7e9e5;color:#a65447}.platform-douyin{background:#eaf0f0;color:#46686d}.platform-meituan{background:#f7efd6;color:#9c772b}.platform-wechat{background:#e5f1e9;color:#558363}.intent-tag{background:#f0f1ed;color:#6e807a}.customer-level{margin-left:auto;color:#a1aaa7;font-size:9px;white-space:nowrap}.conversation-preview{display:block;overflow:hidden;margin-top:7px;color:#687a75;font-size:10px;line-height:1.55;text-overflow:ellipsis;white-space:nowrap}.draft-hint{display:flex;align-items:center;gap:4px;margin-top:6px;color:#3c786b;font-size:9px}.unread-badge{display:grid;place-items:center;align-self:flex-start;min-width:18px;height:18px;padding:0 4px;border-radius:10px;background:var(--cinnabar);color:#fff;font:10px var(--font-mono)}.empty-inbox{padding:30px 12px;color:#9aa39f;text-align:center;font-size:11px}.inbox-foot{display:flex;align-items:center;justify-content:space-between;flex:0 0 42px;height:42px;padding:0 15px;border-top:1px solid var(--line);color:#82908a;font-size:10px}.inbox-foot span:first-child{display:flex;align-items:center;gap:6px}.inbox-foot button{display:flex;align-items:center;gap:4px;border:0;background:transparent;color:var(--sage-ink);font-size:10px}.status-pip{display:inline-block;width:6px;height:6px;border-radius:50%;background:#7ca28d}
.chat-header{display:flex;align-items:center;justify-content:space-between;flex:0 0 74px;min-height:74px;gap:14px;padding:14px 20px;border-bottom:1px solid var(--line);background:#fffefa}.chat-identity{display:flex;align-items:center;min-width:0;gap:11px}.chat-title-row{display:flex;align-items:center;gap:7px;min-width:0;flex-wrap:wrap}.chat-title-row h2{margin:0;font:600 17px/1.25 var(--font-serif)}.chat-identity p{margin:6px 0 0;color:#9aa39f;font-size:10px}.preference-tag{display:inline-flex;align-items:center;gap:3px;padding:3px 6px;border:1px solid #e7d9c4;border-radius:5px;background:#fbf6ec;color:#9b743c;font-size:9px}.chat-actions{display:flex;align-items:center;gap:7px;flex:0 0 auto}.header-icon-button{display:inline-flex;align-items:center;gap:5px;height:30px;padding:0 9px;border:1px solid var(--line);border-radius:7px;background:transparent;color:#73837d;font-size:10px;white-space:nowrap}.header-icon-button:hover{background:var(--sage);color:var(--sage-ink)}
.chat-log{flex:1;min-height:0;overflow-y:auto;padding:20px 24px 12px;background:linear-gradient(180deg,#fffefa 0,#fbfaf6 100%);scrollbar-width:thin}.day-divider{display:flex;align-items:center;gap:10px;margin:0 auto 18px;color:#b1b9b5;font-size:9px}.day-divider::before,.day-divider::after{content:'';height:1px;flex:1;background:var(--line)}.message-row{display:flex;gap:8px;max-width:78%;margin:0 0 15px}.message-row.customer{margin-right:auto}.message-row.merchant{margin-left:auto;flex-direction:row-reverse}.message-mini-avatar{width:24px;height:24px;flex:0 0 24px;font-size:10px}.message-cluster{min-width:0}.message-bubble{padding:10px 12px;border:1px solid var(--line);border-radius:4px 12px 12px 12px;background:#fffefa;color:#415650;font-size:12px;line-height:1.7;white-space:pre-wrap}.merchant .message-bubble{border-color:#cfe1dc;border-radius:12px 4px 12px 12px;background:#eaf2ef;color:#244b44}.message-meta{display:flex;align-items:center;gap:5px;margin-top:5px;color:#a1aba6;font-size:9px}.merchant .message-meta{justify-content:flex-end}
.ai-draft-card{margin:23px 0 16px;border:1px solid #b9d4ce;border-radius:13px;background:#edf4f2;box-shadow:0 5px 18px rgba(68,113,101,.08);overflow:hidden;animation:draft-in .3s ease-out}.draft-card-header{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:13px 15px;border-bottom:1px solid #d6e6e2}.draft-title{display:flex;align-items:center;min-width:0;gap:9px}.draft-title>div{min-width:0}.ai-orb{display:grid;place-items:center;flex:0 0 27px;width:27px;height:27px;border-radius:9px;background:#d7eae5;color:#2f7164}.draft-title strong,.draft-title small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.draft-title strong{font:600 12px var(--font-serif);color:#245a50}.draft-title small{margin-top:3px;color:#718d86;font-size:9px}.regenerate-button{display:flex;align-items:center;flex:0 0 auto;gap:4px;padding:5px 7px;border:0;border-radius:6px;background:transparent;color:#477a70;font-size:9px}.regenerate-button:hover{background:#dcece8}.draft-paper{position:relative;margin:14px 15px 10px;padding:14px 18px 16px 21px;border:1px solid #dfdfd6;border-radius:7px;background:#fffefa;box-shadow:0 3px 9px rgba(67,78,67,.035);cursor:text}.draft-paper::before{content:'';position:absolute;inset:0 0 0 4px;border-left:1px solid #e7c8bf;border-radius:7px;opacity:.65}.draft-paper-stamp{position:absolute;right:14px;top:12px;width:33px;height:33px;display:grid;place-items:center;border:1px solid #d69b8e;border-radius:5px;color:#bf6558;font:13px var(--font-serif);transform:rotate(-6deg);opacity:.78}.draft-copy{padding-right:42px;color:#3f554f;font-size:12px;line-height:1.85;white-space:pre-wrap}.inline-edit-button{display:flex;align-items:center;gap:4px;margin-top:8px;padding:0;border:0;background:transparent;color:#90a19b;font-size:9px}.draft-inline-editor{width:100%;min-height:124px;padding:0 42px 0 0;border:0;outline:0;resize:vertical;background:transparent;color:#3f554f;font:12px/1.85 var(--font-sans)}.tone-row{display:flex;align-items:center;gap:7px;padding:0 15px 12px;color:#80938c;font-size:10px}.tone-pill{padding:4px 9px;border:1px solid #d4e2de;border-radius:20px;background:transparent;color:#628278;font-size:9px}.tone-pill.active{border-color:#6d9b90;background:#d8ebe6;color:#285f55}.draft-actions{display:flex;align-items:center;justify-content:flex-end;gap:7px;padding:11px 15px;background:#e7f0ed;border-top:1px solid #d6e6e2}.draft-secondary,.draft-refine,.draft-confirm{display:inline-flex;align-items:center;gap:5px;height:31px;padding:0 10px;border-radius:7px;font-size:10px}.draft-secondary,.draft-refine{border:1px solid #cadfd9;background:transparent;color:#557a72}.draft-confirm{border:1px solid #b43525;background:var(--cinnabar);color:#fff;box-shadow:0 3px 8px #c43d2a30;font-weight:600}.stamp-mark{display:grid;place-items:center;width:18px;height:18px;border:1px solid #edb9ad;border-radius:3px;font:11px var(--font-serif)}
.composer-area{flex:0 0 auto;padding:11px 18px 13px;border-top:1px solid var(--line);background:#fffefa}.composer-tools{display:flex;align-items:center;gap:10px;color:#8e9a95}.composer-tools button{display:flex;align-items:center;gap:4px;border:0;background:transparent;color:#83918b;font-size:10px;padding:0}.composer-divider{width:1px;height:14px;background:var(--line)}.quick-insert{padding:0 6px!important}.composer-spacer{flex:1}.composer-mode{display:flex;align-items:center;gap:5px;color:#668d80;font-size:9px}.composer-box{display:flex;align-items:flex-end;gap:10px;margin-top:9px;padding:10px 10px 9px 12px;border:1px solid #dfe5e1;border-radius:10px;background:#fbfcfa}.composer-input{flex:1;min-height:40px;resize:none;border:0;outline:0;background:transparent;color:#37514b;font:12px/1.65 var(--font-sans)}.send-button{display:inline-flex;align-items:center;gap:5px;height:31px;padding:0 11px;border:0;border-radius:7px;background:var(--ink);color:#fff;font-size:10px}.send-button:disabled{background:#d4ddd9;color:#a2ada8}.composer-hint{display:flex;justify-content:space-between;margin-top:7px;color:#adb6b1;font-size:9px}
.context-header{display:flex;align-items:flex-start;justify-content:space-between;flex:0 0 auto;padding:20px 17px 14px;border-bottom:1px solid var(--line)}.context-header h2{margin:0;font:600 18px/1.25 var(--font-serif)}.context-header span{display:block;margin-top:4px;color:#97a39e;font-size:9px}.profile-section,.context-section{padding:16px;border-bottom:1px solid var(--line)}.profile-section-title{display:flex;align-items:center;justify-content:space-between;margin-bottom:12px}.profile-section-title h3{margin:0;font:600 13px var(--font-serif)}.profile-section-title button{display:flex;align-items:center;gap:4px;border:0;background:transparent;color:#7d9189;font-size:9px}.customer-profile{display:flex;align-items:center;gap:9px}.customer-profile>div{display:flex;flex:1;flex-direction:column;gap:4px}.customer-profile strong{font-size:12px}.customer-profile span{color:#94a19c;font-size:9px}.profile-score{display:flex;flex-direction:column;align-items:center;justify-content:center;width:30px;height:30px;border:1px solid #c5d9d3;border-radius:8px;background:#edf4f2;color:#3c796c;font:600 14px var(--font-mono)}.profile-score small{font:8px var(--font-sans);color:#73958b}.profile-stats{display:grid;grid-template-columns:repeat(3,1fr);gap:5px;margin:14px 0}.profile-stats div{padding:8px 3px;border-radius:7px;background:#f1f4f0;text-align:center}.profile-stats strong,.profile-stats span{display:block}.profile-stats strong{font:600 12px var(--font-mono);color:#355950}.profile-stats span{margin-top:4px;color:#98a39f;font-size:8px}.note-box{display:flex;gap:7px;padding:9px 10px;border:1px solid #e8e0cf;border-radius:7px;background:#fdf8ed;color:#87704b;font-size:9px;line-height:1.6}.grounding-card{display:flex;align-items:center;gap:8px;margin-top:7px;padding:8px;border:1px solid #e3e8e3;border-radius:8px;background:#fffefa}.grounding-icon{display:grid;place-items:center;width:25px;height:25px;border-radius:6px;background:#edf1f0;color:#567771}.grounding-icon.green{background:#e8f2e9;color:#5f946c}.grounding-icon.amber{background:#f8f0dc;color:#ab7d35}.grounding-card div{display:flex;flex:1;flex-direction:column;gap:3px;min-width:0}.grounding-card strong{font-size:10px;font-weight:600}.grounding-card span{color:#9aa39f;font-size:8px}.grounding-card>svg{color:#7da08e}.shortcuts{border-bottom:0}.shortcuts>button{display:flex;align-items:center;gap:8px;width:100%;padding:9px 0;border:0;border-top:1px solid #eeece5;background:transparent;text-align:left}.shortcuts>button>span:nth-child(2){display:flex;flex:1;flex-direction:column;gap:3px}.shortcuts strong{font-size:10px;font-weight:600}.shortcuts small{color:#9ca6a2;font-size:8px}.shortcut-icon{display:grid;place-items:center;width:24px;height:24px;border-radius:6px;background:#eef3f0;color:#5f897e}.context-footer{display:flex;align-items:center;gap:5px;margin-top:auto;padding:12px 15px;border-top:1px solid var(--line);color:#98a39f;font-size:8px}.context-footer button{border:0;background:transparent;color:#66877d;font-size:8px}
.rail-open-button{position:fixed;right:16px;bottom:22px;display:flex;align-items:center;gap:6px;padding:9px 12px;border:1px solid #c5d8d2;border-radius:9px;background:#edf4f2;color:#31655a;font-size:10px;box-shadow:0 4px 12px rgba(50,80,74,.1)}
@keyframes draft-in{from{opacity:0;transform:translateY(9px)}to{opacity:1;transform:none}}.draft-fade-enter-active,.draft-fade-leave-active{transition:all .3s ease}.draft-fade-enter-from,.draft-fade-leave-to{opacity:0;transform:translateY(7px)}
@media(max-width:1280px){.messages-grid,.rail-collapsed .messages-grid{grid-template-columns:300px minmax(0,1fr)}.context-panel{display:none}.header-icon-button{font-size:0;padding:0;width:30px;justify-content:center}.header-icon-button svg{width:15px}.customer-level{display:none}}
@media(max-width:760px){.messages-workspace{height:auto;min-height:100%;overflow:auto}.messages-grid,.rail-collapsed .messages-grid{display:flex;flex-direction:column;height:auto;min-height:0}.inbox-panel{height:390px;flex:0 0 390px}.chat-panel{height:680px;min-height:680px}.context-panel{display:flex;height:auto}.chat-actions{display:none}.chat-log{padding-inline:14px}.message-row{max-width:90%}}
[data-theme='dark'] .messages-workspace{--paper:#131b1a;--ink:#e7efeb;--ink-2:#c6d6d0;--muted:#8ea39b;--line:#30413d;--sage:#1b302d;--sage-ink:#b8d3cd;background:#131b1a}.dark .messages-workspace{background:#131b1a}.messages-workspace[data-theme='dark'] .chat-log,[data-theme='dark'] .chat-log{background:#16201e}.messages-workspace[data-theme='dark'] .chat-header,[data-theme='dark'] .chat-header,[data-theme='dark'] .composer-area{background:#1a2523}.messages-workspace[data-theme='dark'] .message-bubble,[data-theme='dark'] .message-bubble{background:#1d2926;color:#d8e6e0;border-color:var(--line)}

/* 会话列表始终保留；未选中时，右侧完整留作引导区。 */
.messages-workspace.no-conversation .messages-grid { grid-template-columns: 320px minmax(0, 1fr); }
.messages-workspace .inbox-label { margin: 0; font: 600 16px/1.4 var(--font-sans); }
.messages-workspace .platform-select-wrap { position: relative; margin-left: auto; z-index: 10; }
.messages-workspace .platform-select { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-width: 128px; height: 36px; padding: 0 12px; border: 1px solid var(--line); border-radius: 8px; background: var(--paper); color: var(--ink); font: 12px var(--font-sans); cursor: pointer; transition: border-color 180ms ease, background-color 180ms ease, box-shadow 180ms ease; }
.messages-workspace .platform-select:hover, .messages-workspace .platform-select.is-open { border-color: #b8d3cd; background: var(--sage); }
.messages-workspace .platform-select:focus:not(:focus-visible) { outline: none; }
.messages-workspace .platform-select:focus-visible { outline: 2px solid #8bb6a9; outline-offset: 3px; }
.messages-workspace .platform-select.is-open { box-shadow: 0 0 0 3px rgb(184 211 205 / 18%); }
.messages-workspace .platform-chevron { color: var(--ink-2); transition: transform 220ms cubic-bezier(.2,.8,.2,1); }
.messages-workspace .platform-chevron.is-open { transform: rotate(180deg); }
.messages-workspace .platform-options { position: absolute; top: calc(100% + 8px); right: 0; width: 196px; margin: 0; padding: 6px; list-style: none; border: 1px solid var(--line); border-radius: 12px; background: var(--paper); box-shadow: 0 8px 24px rgb(28 53 50 / 10%); transform-origin: top right; outline: none; }
.messages-workspace .platform-option { display: flex; align-items: center; gap: 10px; min-height: 40px; padding: 6px 10px; border-radius: 7px; color: var(--ink-2); font-size: 12px; cursor: pointer; transition: background-color 140ms ease, color 140ms ease; }
.messages-workspace .platform-option.is-selected { color: var(--sage-ink); font-weight: 600; }
.messages-workspace .platform-option.is-active { background: var(--sage); color: var(--ink); }
.messages-workspace .platform-option-mark { display: grid; place-items: center; width: 24px; height: 24px; flex: 0 0 24px; border-radius: 6px; font-size: 11px; }
.messages-workspace .platform-option-mark img { display: block; width: 18px; height: 18px; object-fit: contain; }
.messages-workspace .platform-option-check { margin-left: auto; color: var(--sage-ink); }
.platform-menu-enter-active { transition: opacity 200ms ease, transform 220ms cubic-bezier(.2,.8,.2,1); }
.platform-menu-leave-active { transition: opacity 140ms ease, transform 160ms ease; pointer-events: none; }
.platform-menu-enter-from, .platform-menu-leave-to { opacity: 0; transform: translateY(-5px) scale(.98); }
@media (prefers-reduced-motion: reduce) { .platform-menu-enter-active, .platform-menu-leave-active, .messages-workspace .platform-chevron { transition: none; } }
.messages-workspace .conversation-placeholder { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; min-width: 0; min-height: 0; background: var(--paper); color: var(--ink-2); }
.messages-workspace .conversation-placeholder > svg { color: var(--muted); }
.messages-workspace .conversation-placeholder p { margin: 4px 0 0; font-size: 15px; }
.messages-workspace .conversation-placeholder > span { color: var(--muted); font-size: 12px; }
@media (max-width: 1280px) { .messages-workspace.no-conversation .messages-grid { grid-template-columns: 300px minmax(0, 1fr); } }
@media (max-width: 760px) { .messages-workspace .conversation-placeholder { min-height: 220px; padding: 24px 16px; } }
.messages-workspace .close-conversation:hover { color: var(--cinnabar); border-color: var(--cinnabar); }
@media (max-width: 760px) {
  .messages-workspace .chat-actions { display: flex; }
  .messages-workspace .chat-actions > :not(.close-conversation) { display: none; }
  .messages-workspace .close-conversation { width: 44px; height: 44px; flex-basis: 44px; }
}
</style>

