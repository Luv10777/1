import test from 'node:test'
import assert from 'node:assert/strict'
import { analysisEditableContent, customizeAnalysisRecreation, analysisRecreationRoute } from './videoAnalysisCustomization.js'

function analysis() {
  return { id: 7, mode: 'ai', durationMs: 6000, width: 1080, height: 1920, result: {
    schemaVersion: 4, productReferences: ['旧牌', '白色陶瓷杯子', '杯子'], parameters: [],
    prompt: '生成六秒竖屏视频，木桌中央展示白色陶瓷杯子，人物缓慢拿起，窗边柔光，相机固定。{{edit:e1}} {{edit:e2}} {{edit:e3}}',
    reuseScript: '镜头 1｜00:00–00:03｜白色陶瓷杯子静置，窗边柔光。{{edit:e1}} {{edit:e2}}\n镜头 2｜00:03–00:06｜人物拿起白色陶瓷杯子，相机固定。{{edit:e3}}',
    negativePrompt: '避免商品变形、手指异常和画面闪烁。', shots: [],
    editableContent: [
      { id: 'e1', kind: 'brand', label: '原视频品牌', original: '旧牌', source: 'video', start: 0, end: 6 },
      { id: 'e2', kind: 'dialogue', label: '开场口播', original: '试试旧牌的白色陶瓷杯子', source: 'audio', start: 0, end: 3 },
      { id: 'e3', kind: 'subtitle', label: '结尾字幕', original: '旧牌，今日优惠 99 元', source: 'video', start: 3, end: 6 },
    ],
    audioAnalyzed: true, audio: { status: 'ANALYZED', transcript: [{ start: 0, end: 3, text: '试试旧牌的白色陶瓷杯子' }] },
  } }
}

test('only actual speech is editable and defaults preserve the original brand and product words', () => {
  const source = analysis(), before = structuredClone(source)
  const result = customizeAnalysisRecreation(source)
  assert.equal(result.editableContent.length, 1)
  assert.equal(result.editableContent[0].original, '试试旧牌的白色陶瓷杯子')
  assert.equal(result.editableContent[0].replacement, result.editableContent[0].original)
  assert.equal(result.editableContent[0].label, '口播 1')
  for (const value of [result.prompt, result.generationScript]) {
    assert.match(value, /00:00–00:03 口播：“试试旧牌的白色陶瓷杯子”/)
    assert.match(value, /如图中产品/)
    assert.doesNotMatch(value, /展示白色陶瓷杯子|杯子静置|拿起白色陶瓷杯子|本店|今日优惠|\{\{(?:edit|speech):/)
  }
  assert.equal(result.reuseScript, result.generationScript)
  assert.deepEqual(source, before)
})

test('editing updates both copy blocks and clearing removes speech while preserving the original transcript', () => {
  const source = analysis(), result = customizeAnalysisRecreation(source, { e2: '欢迎选购我们的新品', e1: '忽略旧品牌编辑' })
  for (const value of [result.prompt, result.generationScript]) {
    assert.match(value, /口播：“欢迎选购我们的新品”/)
    assert.doesNotMatch(value, /旧牌|陶瓷|杯子|忽略旧品牌编辑/)
  }
  for (const empty of ['', '  ']) {
    const cleared = customizeAnalysisRecreation(source, { e2: empty })
    assert.match(cleared.prompt, /00:00–00:03不安排口播/)
    assert.match(cleared.generationScript, /00:00–00:03不安排口播/)
    assert.doesNotMatch(cleared.prompt, /试试|旧牌/)
  }
  assert.equal(source.result.audio.transcript[0].text, result.editableContent[0].original)
})

test('merchant words and punctuation are inserted literally after visual product replacement', () => {
  const literal = '  今日优惠 $& $1 [新品] (A+B) {{edit:e3}} 旧牌的白色陶瓷杯子  '
  const result = customizeAnalysisRecreation(analysis(), { e2: literal })
  for (const text of [result.prompt, result.generationScript]) assert.ok(text.includes(literal))
  assert.equal(result.editableContent[0].replacement, literal)
})

test('historical reports expose complete transcripts even with absent, empty or partial editable lists', () => {
  for (const recorded of [undefined, [], [{ id: 'e2', kind: 'dialogue', source: 'audio', original: '旧牌', start: 0, end: 3 }]]) {
    const source = analysis()
    source.result.editableContent = recorded
    source.result.schemaVersion = 3
    source.result.prompt = '生成竖屏短片，窗边展示白色陶瓷杯子，原对白“试试旧牌的白色陶瓷杯子”。'
    source.result.reuseScript = '镜头 1｜00:00–00:06｜展示白色陶瓷杯子，口播：试试旧牌的白色陶瓷杯子。'
    assert.equal(analysisEditableContent(source)[0].original, source.result.audio.transcript[0].text)
    const result = customizeAnalysisRecreation(source, { speech0: '我的新口播' })
    for (const value of [result.prompt, result.generationScript]) {
      assert.match(value, /我的新口播/)
      assert.doesNotMatch(value, /试试|旧牌|杯子|\{\{/)
    }
  }
})

test('a partial historical speech slot uses the full transcript and missing speech is included too', () => {
  const source = analysis()
  source.result.editableContent[1].original = '旧牌'
  source.result.audio.transcript.push({ start: 3, end: 6, text: '原价 99 元，现在 59 元！' })
  const result = customizeAnalysisRecreation(source)
  assert.deepEqual(result.editableContent.map(item => item.original), source.result.audio.transcript.map(item => item.text))
  for (const value of [result.prompt, result.generationScript]) {
    assert.match(value, /试试旧牌的白色陶瓷杯子/)
    assert.match(value, /00:03–00:06 口播：“原价 99 元，现在 59 元！”/)
    assert.doesNotMatch(value, /\{\{/)
  }
})

test('a short spoken product name does not preserve that product in visual descriptions', () => {
  const source = analysis()
  source.result.audio.transcript[0].text = '杯子'
  source.result.editableContent = []
  source.result.prompt = '生成六秒竖屏视频，木桌中央展示白色陶瓷杯子，相机固定。口播：“杯子”。'
  source.result.reuseScript = '镜头 1｜00:00–00:06｜拿起白色陶瓷杯子。'
  const result = customizeAnalysisRecreation(source)
  assert.match(result.prompt, /展示如图中产品/)
  assert.match(result.prompt, /口播：“杯子”/)
  assert.match(result.generationScript, /拿起如图中产品/)
  assert.match(result.generationScript, /口播：“杯子”/)
})

test('old subtitle and brand slots disappear without leaving empty text instructions in copy blocks', () => {
  const source = analysis()
  source.result.prompt += '卡片文字安排为{{edit:e3}}，切换到人物镜头。'
  source.result.reuseScript += '卡片文字安排为{{edit:e3}}，切换到人物镜头。'
  const result = customizeAnalysisRecreation(source)
  for (const value of [result.prompt, result.generationScript]) {
    assert.match(value, /切换到人物镜头/)
    assert.doesNotMatch(value, /卡片文字安排为|今日优惠|\{\{edit:/)
  }
})

test('failed or absent audio never exposes guessed speech or brand and subtitle editing', () => {
  for (const status of ['FAILED', 'NO_AUDIO', 'NOT_CONFIGURED']) {
    const source = analysis()
    source.result.audio.status = status
    const result = customizeAnalysisRecreation(source)
    assert.deepEqual(result.editableContent, [])
    assert.doesNotMatch(result.prompt, /试试|品牌：|今日优惠|\{\{/)
  }
  const source = analysis()
  source.result.audioAnalyzed = false
  assert.deepEqual(analysisEditableContent(source), [])
  source.result.audioAnalyzed = true
  source.result.audio.transcript = []
  assert.deepEqual(analysisEditableContent(source), [])
})

test('repeated sentences at different times remain independently editable', () => {
  const source = analysis()
  source.result.editableContent.push({ ...source.result.editableContent[1], id: 'e4', start: 3, end: 6 })
  source.result.audio.transcript.push({ ...source.result.audio.transcript[0], start: 3, end: 6 })
  source.result.prompt += ' {{edit:e4}}'
  source.result.reuseScript += ' {{edit:e4}}'
  const result = customizeAnalysisRecreation(source, { e2: '开场改写', e4: '结尾改写' })
  assert.equal(result.editableContent.length, 2)
  for (const value of [result.prompt, result.generationScript]) {
    assert.match(value, /00:00–00:03 口播：“开场改写”/)
    assert.match(value, /00:03–00:06 口播：“结尾改写”/)
    assert.equal(value.split('开场改写').length - 1, 1)
    assert.equal(value.split('结尾改写').length - 1, 1)
    assert.doesNotMatch(value, /试试旧牌/)
  }
})

test('real filming keeps camera instructions and uses the edited speech in its AI conversion script', () => {
  const source = analysis()
  source.mode = 'real'
  source.result.reuseScript = '手机摆在旧牌白色陶瓷杯子前方，演员拿起杯子。口播：“试试旧牌的白色陶瓷杯子”。'
  source.result.shots = [{ start: 0, end: 6, scene: '拿起白色陶瓷杯子', editing: '口播：“试试旧牌的白色陶瓷杯子”。' }]
  const result = customizeAnalysisRecreation(source, { e2: '我的实拍口播' })
  assert.match(result.reuseScript, /手机摆在旧牌白色陶瓷杯子前方/)
  for (const text of [result.prompt, result.reuseScript, result.generationScript]) {
    assert.match(text, /我的实拍口播/)
    assert.doesNotMatch(text, /试试|\{\{/)
  }
  assert.doesNotMatch(result.generationScript, /杯子/)
})

test('the workbench receives edited speech, the full prompt, storyboard and settings without a long URL', () => {
  const source = analysis(), edits = { e2: '欢迎选购我的商品' }
  const result = customizeAnalysisRecreation(source, edits), route = analysisRecreationRoute(source, edits)
  assert.equal(route.path, '/video/workbench')
  assert.deepEqual(route.query, { ratio: '9:16', duration: '6' })
  assert.equal(route.state.recreationPrompt, `整体复刻提示词：\n${result.prompt}\n\n分镜脚本：\n${result.generationScript}\n\n避免出现：\n${result.negativePrompt}`)
  assert.doesNotMatch(route.state.recreationPrompt, /旧牌|杯子|\{\{edit:/)
  source.width = 1440; source.height = 1000; source.durationMs = 60000
  assert.deepEqual(analysisRecreationRoute(source).query, { ratio: 'auto', duration: '60' })
})
