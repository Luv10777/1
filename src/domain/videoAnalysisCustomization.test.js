import test from 'node:test'
import assert from 'node:assert/strict'
import { analysisEditableContent, customizeAnalysisRecreation, analysisRecreationRoute } from './videoAnalysisCustomization.js'

function analysis() {
  return { id: 7, mode: 'ai', durationMs: 6000, width: 1080, height: 1920, result: {
    schemaVersion: 4, productReferences: ['旧牌', '白色陶瓷杯子', '杯子'], parameters: [],
    prompt: '生成六秒竖屏视频，木桌中央展示如图中产品，人物缓慢拿起，窗边柔光，相机固定。{{edit:e1}} {{edit:e2}} {{edit:e3}}',
    reuseScript: '镜头 1｜00:00–00:03｜如图中产品静置，窗边柔光。{{edit:e1}} {{edit:e2}}\n镜头 2｜00:03–00:06｜人物拿起如图中产品，相机固定。{{edit:e3}}',
    negativePrompt: '避免商品变形、手指异常和画面闪烁。', shots: [],
    editableContent: [
      { id: 'e1', kind: 'brand', label: '原视频品牌', original: '旧牌', source: 'video', start: 0, end: 6 },
      { id: 'e2', kind: 'dialogue', label: '开场口播', original: '试试旧牌的白色陶瓷杯子', source: 'audio', start: 0, end: 3 },
      { id: 'e3', kind: 'subtitle', label: '结尾字幕', original: '旧牌，今日优惠 99 元', source: 'video', start: 3, end: 6 },
    ],
    audioAnalyzed: true, audio: { status: 'ANALYZED', transcript: [{ start: 0, end: 3, text: '试试旧牌的白色陶瓷杯子' }] },
  } }
}

test('brand changes update unedited dialogue and subtitles in both copy blocks without changing originals', () => {
  const source = analysis(), before = structuredClone(source)
  const result = customizeAnalysisRecreation(source, { e1: '欢喜' })
  for (const value of [result.prompt, result.generationScript]) {
    assert.match(value, /品牌：“欢喜”/)
    assert.match(value, /00:00–00:03 对白／口播：“试试欢喜的如图中产品”/)
    assert.match(value, /欢喜，今日优惠 99 元/)
    assert.doesNotMatch(value, /旧牌|陶瓷|杯子|\{\{edit:/)
  }
  assert.equal(result.reuseScript, result.generationScript)
  assert.deepEqual(source, before)
  assert.equal(result.editableContent[1].original, '试试旧牌的白色陶瓷杯子')
  assert.equal(result.audio.transcript[0].text, '试试旧牌的白色陶瓷杯子')
})

test('defaults remove the original brand and explicit empty values remove speech and text', () => {
  const defaults = customizeAnalysisRecreation(analysis())
  assert.equal(defaults.editableContent[0].replacement, '')
  assert.doesNotMatch(defaults.prompt, /旧牌|陶瓷|杯子/)
  const result = customizeAnalysisRecreation(analysis(), { e2: '', e3: '  ' })
  assert.match(result.prompt, /00:00–00:03不安排对白／口播/)
  assert.match(result.generationScript, /00:03–00:06不安排画面字幕/)
  assert.doesNotMatch(result.prompt, /今日优惠|99 元|试试/)
})

test('explicit merchant edits preserve literal punctuation and cannot be changed by other replacements', () => {
  const source = analysis()
  source.result.editableContent.push({ id: 'e4', kind: 'text', label: '价格', original: '今日优惠', source: 'video', start: 3, end: 6 })
  source.result.prompt += ' {{edit:e4}}'
  source.result.reuseScript += ' {{edit:e4}}'
  const literal = '今日优惠 $& $1 [新品] (A+B) {{edit:e4}} 旧牌'
  const result = customizeAnalysisRecreation(source, { e1: '新品牌', e2: literal, e4: '换价' })
  for (const text of [result.prompt, result.generationScript]) assert.ok(text.includes(literal))
  assert.equal(result.editableContent[1].replacement, literal)
})

test('a new brand containing the original name is preserved and explicit sentences stay under merchant control', () => {
  const result = customizeAnalysisRecreation(analysis(), { e1: '旧牌升级版', e2: '我为自己的商品写的口播' })
  assert.match(result.prompt, /品牌：“旧牌升级版”/)
  assert.match(result.prompt, /旧牌升级版，今日优惠/)
  assert.match(result.prompt, /我为自己的商品写的口播/)
  assert.doesNotMatch(result.prompt, /试试/)
})

test('historical records only expose actual transcript evidence and recorded brand information', () => {
  const source = analysis()
  delete source.result.editableContent
  source.result.schemaVersion = 3
  source.result.parameters = [{ key: '品牌', value: '旧牌' }, { key: '商品颜色', value: '白色' }]
  source.result.prompt = '生成竖屏短片，窗边展示如图中产品，原对白“试试旧牌的白色陶瓷杯子”。'
  source.result.reuseScript = '镜头 1｜00:00–00:06｜展示如图中产品，原对白“试试旧牌的白色陶瓷杯子”。'
  const items = analysisEditableContent(source)
  assert.equal(items.length, 2)
  const result = customizeAnalysisRecreation(source, { brand0: '新牌', speech0: '我的新口播' })
  for (const value of [result.prompt, result.generationScript]) {
    assert.match(value, /我的新口播/)
    assert.doesNotMatch(value, /旧牌|杯子/)
  }
  source.result.audioAnalyzed = false
  assert.deepEqual(analysisEditableContent(source).map(item => item.kind), ['brand'])
  source.result.audioAnalyzed = true
  source.result.audio.status = 'FAILED'
  assert.deepEqual(analysisEditableContent(source).map(item => item.kind), ['brand'])
})

test('recorded empty editable content remains empty and real filming stays independent from AI conversion', () => {
  const source = analysis()
  source.mode = 'real'
  source.result.reuseScript = '手机摆在旧牌白色陶瓷杯子前方，演员拿起杯子。'
  const result = customizeAnalysisRecreation(source, { e1: '新牌', e2: '我的口播' })
  assert.equal(result.reuseScript, source.result.reuseScript)
  assert.match(result.generationScript, /我的口播/)
  assert.doesNotMatch(result.generationScript, /旧牌|陶瓷|杯子/)
  source.result.editableContent = []
  assert.deepEqual(analysisEditableContent(source), [])
})

test('the workbench receives the full edited prompt, script, avoidance rules, aspect ratio and duration without a long URL', () => {
  const source = analysis(), edits = { e1: '欢喜', e2: '欢迎选购我的商品', e3: '限时 59 元' }
  const result = customizeAnalysisRecreation(source, edits), route = analysisRecreationRoute(source, edits)
  assert.equal(route.path, '/video/workbench')
  assert.deepEqual(route.query, { ratio: '9:16', duration: '6' })
  assert.equal(route.state.recreationPrompt, `整体复刻提示词：\n${result.prompt}\n\n分镜脚本：\n${result.generationScript}\n\n避免出现：\n${result.negativePrompt}`)
  assert.doesNotMatch(route.state.recreationPrompt, /旧牌|杯子|\{\{edit:/)
  source.width = 1440; source.height = 1000; source.durationMs = 60000
  assert.deepEqual(analysisRecreationRoute(source).query, { ratio: 'auto', duration: '60' })
})
