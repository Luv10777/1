import test from 'node:test'
import assert from 'node:assert/strict'
import { buildVideoAnalysisReport } from './videoAnalysisReport.js'

function analysis(mode = 'ai') {
  return { name: '复刻素材', mode, durationMs: 6000, width: 1080, height: 1920, createdAt: '2026-10-08T02:00:00Z',
    reverseNeed: '逐镜头复刻', frames: [{ seconds: 0 }], result: {
      summary: '杯子放在窗边，人物伸手拿起杯子。', prompt: '竖屏，窗边的白色杯子，手缓慢拿起杯子。',
      negativePrompt: '避免手指变形和杯子形状变化。', reuseScript: mode === 'real' ? '先拍杯子，再拍人物拿起杯子。' : '先生成白杯首帧，再生成伸手拿杯子的六秒短片。',
      parameters: [{ key: '主体', value: '白色杯子' }],
      recreation: mode === 'real' ? { preparation: '准备白杯和桌子。', cameraSetup: '把手机放在桌面旁。',
        lightingSetup: '利用窗边侧光。', recording: '建议单独录制杯子接触桌面的声音。', editing: '在拿起杯子的动作处剪切。',
        aiWorkflow: '先用杯子参考图生成首帧，再逐镜头生成短片。' }
        : { workflow: '先生成首帧，再生成短片。', consistency: '所有镜头使用相同的杯子参考图。', assembly: '把短片依次拼接。' },
      shots: [{ start: 0, end: 6, scene: '手从右侧伸向白色杯子。', camera: '相机固定。', emotion: '安静', pacing: '慢',
        framing: '近距离看杯子，杯子在画面中央。', lighting: '窗边柔和侧光。', filming: '手机固定在杯子前方，人物从右侧伸手。',
        prompt: '6 秒竖屏短片，白杯位于木桌中央，手从右侧伸入，拿起白杯，镜头固定，窗边柔光。',
        firstFramePrompt: '木桌中央的白杯，窗边柔光，竖屏静态画面。', continuity: '白杯形状与位置保持一致。',
        sound: '建议加入拿起杯子时的轻微摩擦声。', editing: '动作结束后接到人物镜头。' }],
      keyframes: [{ seconds: 0, title: '窗边的杯子', description: '白色杯子放在木桌中央。', prompt: '竖屏静态画面，窗边木桌上的白杯。' }],
      highlights: ['主体清晰'], suggestions: ['保留慢节奏'], limitations: ['机位根据画面推测'],
      audio: { status: 'NO_AUDIO' }, audioAnalyzed: false,
      _model: 'internal-provider-name', _usage: { privateTokenCount: 100 },
    } }
}

test('AI export is a concise Chinese reading report with optional observations and no internal metadata', () => {
  const html = buildVideoAnalysisReport(analysis())
  for (const text of ['AI 视频提示词与分镜复刻报告', '按什么顺序生成', '可直接复制的分镜脚本', '00:00–00:06', '打印 / 保存为 PDF', '10:00']) assert.ok(html.includes(text), text)
  assert.ok(html.includes('本次没有完成声音分析'))
  assert.ok(!html.includes('照着拍的操作步骤'))
  for (const text of ['internal-provider-name', 'privateTokenCount', '"shots":', '"negativePrompt":', '<pre>', '<code>']) assert.ok(!html.includes(text), text)
})

test('real export includes filming and AI conversion even when the page is viewing only one route', () => {
  const html = buildVideoAnalysisReport(analysis('real'))
  for (const text of ['实拍视频拆解与复刻报告', '方案一：照着实拍', '方案二：用 AI 重做', '灯光怎么布置', '声音怎么录',
    '手机固定在杯子前方', '先用如图中产品参考图生成首帧', '可直接复制的分镜脚本']) assert.ok(html.includes(text), text)
  assert.ok(!html.includes('按什么顺序生成'))
})

test('report escapes filenames, model text, prompts and transcripts instead of executing supplied markup', () => {
  const value = analysis('real')
  const unsafe = '<script>alert("name")</script><img src=x onerror="alert(1)">'
  value.name = unsafe
  value.reverseNeed = unsafe
  value.result.summary = unsafe
  value.result.prompt = `生成竖屏短片，杯子位于画面中央，窗边柔光，镜头固定，保持杯子外形一致。${unsafe}`
  value.result.shots[0].filming = unsafe
  value.result.audioAnalyzed = true
  value.result.audio = { status: 'ANALYZED', summary: '口播清晰', speech: '轻声', music: '无', ambience: '无',
    transcript: [{ start: 0, end: 1, text: unsafe }], effects: [], limitations: [] }
  const html = buildVideoAnalysisReport(value)
  assert.ok(!html.includes('<script>alert'))
  assert.ok(!html.includes('<img src=x'))
  assert.ok(html.includes('&lt;script&gt;alert(&quot;name&quot;)&lt;/script&gt;'))
  assert.ok(html.includes('原视频口播'))
})

test('AI reports put copyable prompts and timed storyboards before the analysis and remove old products from copy blocks', () => {
  const html = buildVideoAnalysisReport(analysis())
  const titles = [...html.matchAll(/<h2>(.*?)<\/h2>/g)].map(match => match[1])
  assert.match(titles[0], /可直接复制的复刻提示词/)
  assert.match(titles[1], /可直接复制的分镜脚本/)
  assert.ok(html.includes('先在生成工具上传你自己的商品参考图'))
  const copyBlocks = [...html.matchAll(/<div class="prompt">.*?<p>(.*?)<\/p><\/div>/gs)].map(match => match[1])
  assert.equal(copyBlocks.length, 3, 'overall prompt, optional negative prompt and concise storyboard')
  for (const block of copyBlocks) assert.doesNotMatch(block, /白杯|白色杯子|杯子/)
  assert.ok(copyBlocks.some(block => /镜头 1｜00:00–00:06｜时长 6\.0 秒/.test(block)))
  assert.ok(!html.includes('手从右侧伸向白色杯子。'), 'AI reports omit the individual shot section')
  assert.ok(html.includes('button type="button" class="copy-button" data-copy'))
  assert.doesNotMatch(html, /本镜头的视频生成提示词|本镜头的首帧图片提示词/)
  assert.ok(html.includes('<details class="reference"><summary>查看画面拆解与声音参考</summary>'))
})

test('historical exports present Chinese prompts for both modes without an English appendix', () => {
  for (const mode of ['ai', 'real']) {
    const value = analysis(mode)
    delete value.result.recreation
    value.result.prompt = 'A white cup near the window'
    value.result.negativePrompt = 'distorted fingers'
    value.result.shots[0].prompt = 'A hand picks up the cup'
    delete value.result.shots[0].firstFramePrompt
    value.result.keyframes[0].prompt = 'Still image of a white cup'
    const html = buildVideoAnalysisReport(value)
    assert.ok(html.includes('中文提示词根据本记录已有的画面拆解整理'))
    assert.ok(html.includes('生成一段竖屏视频，总时长 6.0 秒'))
    assert.ok(html.includes('镜头 1｜00:00–00:06｜时长 6.0 秒'))
    assert.ok(!html.includes('生成一张竖屏静态参考图片'), 'the concise report omits individual first-frame prompts')
    if (mode === 'real') assert.ok(html.includes('手从右侧伸向白色杯子'))
    else assert.ok(!html.includes('原视频逐镜观察'))
    assert.ok(html.includes('避免画面模糊'))
    for (const text of ['A white cup near the window', 'distorted fingers', 'A hand picks up the cup', 'Still image of a white cup', '英文原文', 'undefined'])
      assert.ok(!html.includes(text), text)
  }
})

test('export uses the same merchant edits as copy and generation, while preserving escaped originals for comparison', () => {
  const value = analysis()
  value.result.schemaVersion = 4
  value.result.productReferences = ['旧品牌', '杯子', '白杯']
  value.result.editableContent = [
    { id: 'e1', kind: 'brand', label: '品牌名称', original: '旧品牌', source: 'video', start: 0, end: 6 },
    { id: 'e2', kind: 'dialogue', label: '口播 1', original: '试试旧品牌的杯子，今日活动 99 元。', source: 'audio', start: 0, end: 6 },
  ]
  value.result.audioAnalyzed = true
  value.result.audio = { status: 'ANALYZED', speech: '口播清晰', music: '无', ambience: '无', effects: [], limitations: [],
    transcript: [{ start: 0, end: 6, text: '试试旧品牌的杯子，今日活动 99 元。' }] }
  value.result.prompt = '生成竖屏六秒视频，如图中产品放在窗边，人物伸手拿起，相机固定，柔和侧光。{{edit:e1}} {{edit:e2}}'
  value.result.reuseScript = '镜头 1｜00:00–00:06｜人物拿起如图中产品，窗边柔光，相机固定。{{edit:e1}} {{edit:e2}}'
  const html = buildVideoAnalysisReport(value, { e2: '商家的新品牌 <img src=x onerror="alert(1)"> 新活动 59 元' })
  const copyBlocks = [...html.matchAll(/<div class="prompt">.*?<p>(.*?)<\/p><\/div>/gs)].map(match => match[1])
  for (const text of [copyBlocks[0], copyBlocks[2]]) {
    assert.match(text, /商家的新品牌/)
    assert.match(text, /新活动 59 元/)
    assert.doesNotMatch(text, /旧品牌|\{\{edit:/)
  }
  assert.match(html, /口播修改对照/)
  assert.match(html, /原视频口播/)
  assert.match(html, /试试旧品牌的杯子，今日活动 99 元。/)
  assert.doesNotMatch(html, /<h3>品牌名称/)
  assert.match(html, /&lt;img src=x onerror=&quot;alert\(1\)&quot;&gt;/)
  assert.doesNotMatch(html, /<img src=x/)
  assert.doesNotMatch(html, /原视频逐镜观察|本镜头的视频生成提示词|本镜头的首帧图片提示词/)
})
