import test from 'node:test'
import assert from 'node:assert/strict'
import { liveApi, normalizeLiveConfig, defaultLiveConfig, answeringCohostId, toSessionQa, toSpeechFeedItem, toCommentFeedItem, describeAutoScript, describePlayback, defaultSessionName, pickCurrentSession, describeSession, startBlockers, sessionStatusLabel } from './liveApi.js'

const response = (data) => ({ ok: true, status: 200, json: async () => ({ code: 200, data }) })

test('live draft saves structured configuration and optimistic version without a tenant field', async (t) => {
  const calls = []
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push({ url, options })
    return response({ id: 12, version: 4 })
  })
  const config = defaultLiveConfig()
  config.urgency = 2.75
  await liveApi.update(12, { name: '周末场', productIds: [30], roomId: '', version: 3, config })
  assert.equal(calls[0].url, '/api/live-sessions/12')
  assert.equal(calls[0].options.method, 'PATCH')
  const body = JSON.parse(calls[0].options.body)
  assert.equal(body.version, 3)
  assert.equal(body.config.urgency, 2.75)
  assert.ok(!('tenantId' in body))
})

test('session QA edits and deletes use the row version and correct session URL', async (t) => {
  const calls = []
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push({ url, options })
    return response(null)
  })
  await liveApi.updateQa(12, 50, { question: '问题', answer: '回答', version: 2 })
  await liveApi.deleteQa(12, 50, 3)
  assert.equal(calls[0].url, '/api/live-sessions/12/qa/50')
  assert.equal(calls[0].options.method, 'PUT')
  assert.equal(JSON.parse(calls[0].options.body).version, 2)
  assert.equal(calls[1].url, '/api/live-sessions/12/qa/50?version=3')
  assert.equal(calls[1].options.method, 'DELETE')
})

test('draft defaults restore old saved sessions with missing or null config fields', () => {
  const defaults = defaultLiveConfig()
  assert.deepEqual(normalizeLiveConfig(null), defaults)
  assert.deepEqual(normalizeLiveConfig({ tone: { opening: null }, urgency: null, antiRepeat: false }), { ...defaults, antiRepeat: false })
  const result = normalizeLiveConfig({ tone: { opening: '直接报价', pain: [] }, urgency: 4.2 })
  assert.equal(result.tone.opening, '直接报价')
  assert.deepEqual(result.tone.pain, [])
  assert.deepEqual(result.tone.detail, defaults.tone.detail)
  assert.equal(result.urgency, 4.2)
})

test('knowledge target ID is not mistaken for a selected product scope', () => {
  const storeQa = toSessionQa({ id: 4, persistMode: 'STORE_KNOWLEDGE', targetId: 30, question: '停车？', answer: '免费', version: 0 }, [{ id: 30, name: '茶叶' }])
  assert.equal(storeQa.scope, 'session')
  assert.equal(storeQa.source, '本场通用')
  const productQa = toSessionQa({ id: 5, persistMode: 'SESSION', targetId: 30 }, [{ id: 30, name: '茶叶' }])
  assert.equal(productQa.scope, 30)
  assert.equal(productQa.source, '本场 · 茶叶')
  assert.equal(productQa.apiId, 5)
})

test('automatic narration is controlled per session and never sends a tenant or voice from the page', async (t) => {
  const calls = []
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push({ url, options })
    return response({ enabled: true })
  })
  await liveApi.startAutoScript(12)
  await liveApi.stopAutoScript(12)
  await liveApi.autoScript(12)
  await liveApi.speechItems(12)
  assert.deepEqual(calls.map(call => [call.options.method, call.url]), [
    ['POST', '/api/live-sessions/12/auto-script/start'],
    ['POST', '/api/live-sessions/12/auto-script/stop'],
    ['GET', '/api/live-sessions/12/auto-script'],
    ['GET', '/api/live-sessions/12/speech-items'],
  ])
  assert.equal(calls[0].options.body, '{}')
})

test('speech feed names what each item is and how far it has got', () => {
  const products = [{ id: 30, name: '椴树蜂蜜' }]
  const writing = toSpeechFeedItem({ id: 1, kind: 'SCRIPT', status: 'GENERATING', text: '', productId: 30, beat: '核心特点讲解' }, products)
  assert.equal(writing.title, '自动讲解 · 椴树蜂蜜 · 核心特点讲解')
  assert.equal(writing.text, '话术生成中…')
  assert.equal(writing.working, true)

  const failed = toSpeechFeedItem({ id: 2, kind: 'MANUAL', status: 'FAILED', text: '欢迎', error: '语音供应商请求未完成' })
  assert.equal(failed.title, '手动播报')
  assert.equal(failed.stateLabel, '未完成')
  assert.equal(failed.error, '语音供应商请求未完成')
  assert.equal(failed.working, false)

  // A removed product must not blank the row or throw.
  const orphan = toSpeechFeedItem({ id: 3, kind: 'SCRIPT', status: 'PLAYED', text: '讲过了', productId: 99, beat: '促单收尾' }, products)
  assert.equal(orphan.title, '自动讲解 · 促单收尾')
  assert.equal(orphan.error, '')
})

test('automatic narration summary reflects running, paused and stopped states', () => {
  assert.match(describeAutoScript(null), /播完一段补一段/)
  assert.equal(describeAutoScript({ enabled: true, sessionStatus: 'LIVE', generated: 5, buffered: 3, inFlight: 0 }), '自动讲解中 · 已生成 5 段 · 待播 3 段')
  assert.equal(describeAutoScript({ enabled: true, sessionStatus: 'LIVE', generated: 5, buffered: 2, inFlight: 1 }), '自动讲解中 · 已生成 5 段 · 待播 2 段 · 正在准备下一段')
  assert.match(describeAutoScript({ enabled: true, sessionStatus: 'PAUSED', generated: 5, buffered: 2, inFlight: 0 }), /继续本场后恢复/)
  assert.match(describeAutoScript({ enabled: false, generated: 5, buffered: 1 }), /已停止，本场共生成 5 段.*1 段仍会播完/)
  assert.match(describeAutoScript({ enabled: false, generated: 0, buffered: 0 }), /播完一段补一段/)
})

test('in-page playback status says what the page is doing and what the user should do next', () => {
  assert.equal(describePlayback(null).label, '未开始')
  assert.match(describePlayback({ status: 'idle' }).detail, /点击开始播报.*保持本页面打开/)
  assert.equal(describePlayback({ status: 'connecting' }).label, '连接中')
  assert.match(describePlayback({ status: 'reconnecting' }).detail, /已缓存的音频会继续播放/)
  assert.match(describePlayback({ status: 'revoked' }).detail, /其他页面.*重新点击开始播报/)

  const idle = describePlayback({ status: 'connected', current: null, pending: [], paused: false, loading: false })
  assert.equal(idle.label, '等待内容')
  const playing = describePlayback({ status: 'connected', current: { text: '这款蜂蜜口感清甜' }, pending: [{}, {}], paused: false, loading: false })
  assert.equal(playing.label, '播放中')
  assert.equal(playing.detail, '正在播放：这款蜂蜜口感清甜 · 待播 2 条')
  const paused = describePlayback({ status: 'connected', current: { text: '' }, pending: [], paused: true })
  assert.equal(paused.label, '已暂停')
  // A paused clip must not be described as playing.
  assert.equal(paused.detail, '当前片段：本场音频')
  assert.equal(describePlayback({ status: 'connected', current: null, pending: [], loading: true }).label, '缓冲中')
})

test('a saved rotation list from the old format becomes co-hosts, and the old fields are not carried forward', () => {
  const legacy = normalizeLiveConfig({
    rotateRoles: true,
    rotationSelection: ['sample:7', 'sample:6'],
    voiceRoles: [{ id: 'builtin:warm', role: 'none' }, { id: 'sample:7', role: 'host' }, { id: 'sample:6', role: 'none' }],
  })
  assert.deepEqual(legacy.voiceRoles, [{ id: 'sample:7', role: 'host' }, { id: 'sample:6', role: 'cohost' }])
  assert.ok(!('rotateRoles' in legacy))
  assert.ok(!('rotationSelection' in legacy))

  // The list was only a preference while the switch was off; it must not turn into co-hosts.
  const off = normalizeLiveConfig({ rotateRoles: false, rotationSelection: ['sample:6'], voiceRoles: [{ id: 'sample:7', role: 'host' }] })
  assert.deepEqual(off.voiceRoles, [{ id: 'sample:7', role: 'host' }])
  // Co-hosts already chosen in the new format win over an old list.
  const mixed = normalizeLiveConfig({ rotateRoles: true, rotationSelection: ['sample:9'], voiceRoles: [{ id: 'sample:7', role: 'host' }, { id: 'sample:6', role: 'cohost' }] })
  assert.deepEqual(mixed.voiceRoles, [{ id: 'sample:7', role: 'host' }, { id: 'sample:6', role: 'cohost' }])
})

test('persona defaults fill in what an older session never saved', () => {
  assert.deepEqual(normalizeLiveConfig({}).persona, { name: '', style: '亲切自然' })
  assert.deepEqual(normalizeLiveConfig({ persona: { name: '小林', style: null } }).persona, { name: '小林', style: '亲切自然' })
  assert.deepEqual(defaultLiveConfig().voiceRoles, [])
})

test('the page opens the session on air, otherwise the one not yet started, otherwise none', () => {
  const ended = { id: 1, status: 'ENDED' }
  const draft = { id: 2, status: 'DRAFT' }
  const paused = { id: 3, status: 'PAUSED' }
  assert.equal(pickCurrentSession([draft, ended, paused]), paused)
  assert.equal(pickCurrentSession([ended, draft]), draft)
  assert.equal(pickCurrentSession([ended]), null)
  assert.equal(pickCurrentSession([]), null)
})

test('sessions are named by date and described by status and start time', () => {
  assert.equal(defaultSessionName(new Date(2026, 9, 4, 15, 39)), '10月4日直播')
  // A second session on the same day must not look identical to the first in the list.
  const day = new Date(2026, 9, 4)
  assert.equal(defaultSessionName(day, ['10月3日直播', '周末场']), '10月4日直播')
  assert.equal(defaultSessionName(day, ['10月4日直播']), '10月4日直播（第 2 场）')
  assert.equal(defaultSessionName(day, ['10月4日直播', '10月4日直播（第 2 场）']), '10月4日直播（第 3 场）')
  assert.equal(describeSession({ status: 'ENDED', startedAt: new Date(2026, 9, 4, 9, 5).toISOString() }), '已结束 · 10月4日 09:05 开始')
  assert.equal(describeSession({ status: 'DRAFT', startedAt: null }), '未开始')
  assert.equal(sessionStatusLabel('LIVE'), '直播中')
})

test('a session cannot start until it has a name, a host voice and a product', () => {
  assert.deepEqual(startBlockers({ name: ' ', hostVoice: null, products: [] }).map(item => item.key), ['name', 'voice', 'products'])
  const missingVoice = startBlockers({ name: '10月4日直播', hostVoice: null, products: [{ id: 1 }] })
  assert.deepEqual(missingVoice, [{ key: 'voice', label: '选择主播音色', step: 'voice' }])
  assert.deepEqual(startBlockers({ name: '10月4日直播', hostVoice: { id: 'sample:7' }, products: [{ id: 1 }] }), [])
  // Chosen earlier, but deleted or no longer matching the speech model: say so, rather than "choose one".
  const unusable = startBlockers({ name: '10月4日直播', hostVoice: { id: 'sample:7', usable: false }, products: [{ id: 1 }] })
  assert.deepEqual(unusable.map(item => item.label), ['重新选择主播音色（当前这个不可用）'])
})

test('drafts are deleted and the next session is copied through the session endpoints', async (t) => {
  const calls = []
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push({ url, options })
    return response({ id: 13 })
  })
  await liveApi.duplicate(12, { name: '10月5日直播' })
  await liveApi.remove(13)
  assert.equal(calls[0].url, '/api/live-sessions/12/duplicate')
  assert.equal(calls[0].options.method, 'POST')
  assert.deepEqual(JSON.parse(calls[0].options.body), { name: '10月5日直播' })
  assert.equal(calls[1].url, '/api/live-sessions/13')
  assert.equal(calls[1].options.method, 'DELETE')
})

test('弹幕流区分商家原话与 AI 回答，未回复时给出原因', () => {
  const saved = toCommentFeedItem({ id: '1', text: '几点关门', answer: '晚上 9 点', source: 'SESSION', state: 'ANSWERED', provider: 'MOCK' })
  assert.equal(saved.sourceLabel, '本场问答')
  assert.equal(saved.stateLabel, '已回复')
  assert.equal(saved.providerLabel, '模拟弹幕')
  assert.equal(toCommentFeedItem({ id: '2', text: '一罐多大', answer: '500 克', source: 'AI', state: 'ANSWERED' }).sourceLabel, 'AI 依据资料回答')

  const waiting = toCommentFeedItem({ id: '3', text: '能停车吗', state: 'ANSWERING' })
  assert.equal(waiting.working, true)
  assert.equal(waiting.answer, '')
  assert.equal(waiting.sourceLabel, '')

  // 夸奖不需要回：既不是失败，也不用再解释一遍原因。
  const praise = toCommentFeedItem({ id: '5', text: '绿豆不错 去火', state: 'SKIPPED', note: '不是提问，没有回复' })
  assert.equal(praise.stateLabel, '无需回复')
  assert.equal(praise.tone, 'done')
  assert.equal(praise.note, '')
  assert.equal(praise.needsPerson, false)

  // 投诉不由 AI 回，但要醒目地留给人处理，并说明是什么事。
  const complaint = toCommentFeedItem({ id: '6', text: '上次喝了拉稀', state: 'ATTENTION', note: '需要人工处理：观众说上次喝了之后拉肚子' })
  assert.equal(complaint.stateLabel, '需要人工处理')
  assert.equal(complaint.tone, 'failed')
  assert.equal(complaint.needsPerson, true)
  assert.equal(complaint.note, '需要人工处理：观众说上次喝了之后拉肚子')

  const declined = toCommentFeedItem({ id: '4', text: '天气如何', state: 'UNANSWERED', note: '本场资料里没有相关内容，AI 未作答' })
  assert.equal(declined.tone, 'failed')
  assert.equal(declined.note, '本场资料里没有相关内容，AI 未作答')
})

test('弹幕由助播回答默认关闭，开启后由最先设为助播的那一位回答', () => {
  assert.equal(defaultLiveConfig().replyByCohost, false)
  assert.equal(normalizeLiveConfig({ replyByCohost: true }).replyByCohost, true)
  const voiceRoles = [{ id: 'sample:6', role: 'cohost' }, { id: 'sample:7', role: 'host' }, { id: 'sample:9', role: 'cohost' }]
  assert.equal(answeringCohostId({ replyByCohost: true, voiceRoles }), 'sample:6')
  assert.equal(answeringCohostId({ replyByCohost: false, voiceRoles }), null)
  // 没有助播、或没有主播时，开关不起作用。
  assert.equal(answeringCohostId({ replyByCohost: true, voiceRoles: [{ id: 'sample:7', role: 'host' }] }), null)
  assert.equal(answeringCohostId({ replyByCohost: true, voiceRoles: [{ id: 'sample:6', role: 'cohost' }] }), null)
  assert.equal(answeringCohostId(null), null)
})
