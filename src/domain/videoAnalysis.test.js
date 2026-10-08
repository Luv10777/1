import test from 'node:test'
import assert from 'node:assert/strict'
import { VIDEO_ANALYSIS_LIMITS, validateAnalysisFile, analysisMimeType, analysisTime, nearestAnalysisFrame, analysisReportMode, isChineseAnalysisPrompt, localizeAnalysisPrompts } from './videoAnalysis.js'

test('video upload accepts the exact size limit and rejects oversized or disguised inputs', () => {
  const file = { name: '素材.MP4', size: VIDEO_ANALYSIS_LIMITS.maxBytes, type: 'video/mp4' }
  assert.equal(validateAnalysisFile(file), '')
  assert.match(validateAnalysisFile({ ...file, size: file.size + 1 }), /100 MB/)
  assert.match(validateAnalysisFile({ ...file, name: '素材.avi' }), /MP4/)
  assert.match(validateAnalysisFile({ ...file, size: 0 }), /为空/)
  assert.match(validateAnalysisFile({ ...file, type: 'text/plain' }), /有效/)
  assert.equal(validateAnalysisFile({ ...file, type: '' }), '')
  assert.equal(analysisMimeType({ name: '手机.MOV' }), 'video/quicktime')
})

test('an existing report keeps the submitted video mode when the next analysis selection changes', () => {
  assert.equal(analysisReportMode({ mode: 'ai' }, 'real'), 'ai')
  assert.equal(analysisReportMode({ mode: 'real' }, 'ai'), 'real')
  assert.equal(analysisReportMode(null, 'real'), 'real')
})

test('history keyframes resolve to available samples and timestamps carry correctly', () => {
  const frames = [{ seconds: 0, imageUrl: 'a' }, { seconds: 1.25, imageUrl: 'b' }, { seconds: 2.5, imageUrl: 'c' }]
  assert.equal(nearestAnalysisFrame(frames, 1.4).imageUrl, 'b')
  assert.equal(nearestAnalysisFrame([], 0), null)
  assert.equal(analysisTime(59.99), '01:00')
  assert.equal(analysisTime(1.25), '00:01.3')
})

function historicalAnalysis(mode = 'ai') {
  return { mode, width: 1080, height: 1920, durationMs: 6000, result: {
    summary: '白色杯子放在窗边，人物从右侧伸手拿起杯子。',
    parameters: [{ key: '主体', value: '白色杯子' }, { key: '风格', value: '自然光线与真实质感' }],
    prompt: 'A cinematic shot of a white cup near the window', negativePrompt: 'distorted fingers, blurry image',
    shots: [
      { start: 0, end: 2.4, scene: '白色杯子放在木桌上。', framing: '杯子位于画面中央。', camera: '相机固定。',
        lighting: '窗边柔和侧光。', prompt: 'A white cup on a wooden table', firstFramePrompt: 'Still image of a cup',
        continuity: '保持同一个白色杯子。' },
      { start: 2.4, end: 6, scene: '人物从右侧伸手拿起杯子。', camera: '相机向前缓慢移动。', lighting: '保持窗边侧光。',
        prompt: 'A hand picks up the white cup', firstFramePrompt: 'A cup before being picked up' },
    ],
    keyframes: [{ seconds: 0, title: '桌上的白杯', description: '白色杯子在木桌中央，窗户在左侧。', prompt: 'A white cup beside a window' }],
  } }
}

test('Chinese prompt recognition allows brands and rejects English bodies with a small Chinese suffix', () => {
  for (const value of [null, '', 'A cinematic shot of a white cup', 'A cinematic shot near the window with soft natural light. 中文'])
    assert.equal(isChineseAnalysisPrompt(value), false)
  assert.equal(isChineseAnalysisPrompt('生成竖屏 AI 视频，人物从右侧拿起 DJI 产品，窗边柔光，镜头固定。'), true)
})

test('speech slots do not turn Chinese copy blocks into English or make English bodies pass', () => {
  const slots = Array.from({ length: 60 }, (_, index) => `{{edit:e${index + 1}}}`).join(' ')
  assert.equal(isChineseAnalysisPrompt('竖屏短片，如图中产品位于木桌中央，相机缓慢靠近。' + slots), true)
  assert.equal(isChineseAnalysisPrompt('A cinematic product video with a fixed camera.' + slots), false)
  assert.equal(isChineseAnalysisPrompt(slots), false)
})

test('historical prompts become Chinese from observed scenes, movement and actual shot timing in either mode', () => {
  for (const mode of ['ai', 'real']) {
    const value = historicalAnalysis(mode)
    const original = structuredClone(value)
    const result = localizeAnalysisPrompts(value)
    assert.equal(result.promptsLocalized, true)
    for (const prompt of [result.prompt, result.negativePrompt, ...result.shots.flatMap(shot => [shot.prompt, shot.firstFramePrompt]), ...result.keyframes.map(frame => frame.prompt)]) {
      assert.equal(isChineseAnalysisPrompt(prompt), true, prompt)
      assert.doesNotMatch(prompt, /A cinematic|A white cup|distorted fingers|Still image|A hand/)
    }
    assert.match(result.prompt, /竖屏视频，总时长 6\.0 秒/)
    assert.match(result.prompt, /00:00–00:02\.4/)
    assert.match(result.prompt, /00:02\.4–00:06/)
    assert.match(result.prompt, /相机向前缓慢移动/)
    assert.match(result.shots[0].prompt, /时长 2\.4 秒/)
    assert.match(result.shots[1].prompt, /时长 3\.6 秒/)
    assert.match(result.shots[1].prompt, /从右侧伸手拿起如图中产品/)
    assert.match(result.shots[0].firstFramePrompt, /静态参考图片/)
    assert.match(result.shots[0].firstFramePrompt, /窗户在左侧/)
    assert.match(result.keyframes[0].prompt, /窗户在左侧/)
    assert.deepEqual(value, original)
  }
})

test('existing Chinese prompts without products retain their original subject across every location', () => {
  const value = historicalAnalysis()
  value.result.productReferences = []
  const videoPrompt = '  生成竖屏 AI 视频，红色方块在画面中央，窗边柔光，镜头缓慢向前移动。  '
  const imagePrompt = '竖屏静态图片，红色方块在画面中央，窗户在左侧。'
  value.result.prompt = videoPrompt
  value.result.negativePrompt = '避免方块形状变化。'
  value.result.shots.forEach(shot => { shot.prompt = videoPrompt; shot.firstFramePrompt = imagePrompt })
  value.result.keyframes[0].prompt = imagePrompt
  const result = localizeAnalysisPrompts(value)
  assert.equal(result.promptsLocalized, false)
  assert.equal(result.prompt, videoPrompt)
  assert.equal(result.negativePrompt, value.result.negativePrompt)
  assert.deepEqual(result.shots, value.result.shots)
  assert.deepEqual(result.keyframes, value.result.keyframes)
})

test('missing historical per-shot and keyframe prompts get usable Chinese content', () => {
  const value = historicalAnalysis()
  delete value.result.shots[1].prompt
  delete value.result.shots[1].firstFramePrompt
  delete value.result.keyframes[0].prompt
  const result = localizeAnalysisPrompts(value)
  assert.match(result.shots[1].prompt, /从右侧伸手拿起如图中产品/)
  assert.match(result.shots[1].firstFramePrompt, /静态参考图片/)
  assert.match(result.keyframes[0].prompt, /窗户在左侧/)
  assert.equal(localizeAnalysisPrompts(null), null)
})

test('historical product prompts and complete storyboards use the users product without changing observations', () => {
  const value = historicalAnalysis()
  value.result.prompt = '白色杯子放在木桌上，穿白色衣服的人物从右侧拿起杯子，窗边柔光。'
  value.result.shots[0].prompt = '白色杯子在木桌中央，人物穿白色衣服，相机固定，窗边柔光。'
  value.result.shots[0].firstFramePrompt = '木桌中央的白色杯子，白色墙面，窗边柔光。'
  value.result.reuseScript = '先生成白杯，再拍拿起杯子。'
  const original = structuredClone(value)
  const result = localizeAnalysisPrompts(value)
  for (const text of [result.prompt, result.generationScript, result.reuseScript, ...result.shots.flatMap(shot => [shot.prompt, shot.firstFramePrompt]), ...result.keyframes.map(frame => frame.prompt)]) {
    assert.match(text, /如图中产品/)
    assert.doesNotMatch(text, /白色杯子|杯子|白杯/)
  }
  assert.match(result.prompt, /穿白色衣服/)
  assert.match(result.shots[0].firstFramePrompt, /白色墙面/)
  assert.match(result.generationScript, /镜头 1｜00:00–00:02\.4｜时长 2\.4 秒/)
  assert.match(result.generationScript, /镜头 2｜00:02\.4–00:06｜时长 3\.6 秒/)
  assert.match(result.generationScript, /相机向前缓慢移动/)
  assert.equal(result.summary, original.result.summary)
  assert.equal(result.shots[0].scene, original.result.shots[0].scene)
  assert.deepEqual(value, original)
})

test('explicit product aliases cover brand, appearance, packaging and shorthand without changing filming scripts', () => {
  for (const mode of ['ai', 'real']) {
    const value = historicalAnalysis(mode)
    value.result.schemaVersion = 3
    value.result.productReferences = ['某牌金色玻璃香水瓶', '金色玻璃香水瓶', '某牌', '香水瓶', '瓶盖']
    const originalPrompt = '某牌金色玻璃香水瓶在木桌中央，人物穿金色裙子，打开瓶盖，相机缓慢向前移动，暖金色侧光。'
    value.result.prompt = originalPrompt
    value.result.reuseScript = originalPrompt
    value.result.shots.forEach(shot => { shot.scene = originalPrompt; shot.camera = '相机缓慢向前移动，暖金色侧光。'; shot.prompt = originalPrompt; shot.firstFramePrompt = originalPrompt; shot.continuity = originalPrompt })
    value.result.keyframes[0].prompt = originalPrompt
    value.result.recreation = { workflow: originalPrompt, preparation: originalPrompt, aiWorkflow: originalPrompt }
    const result = localizeAnalysisPrompts(value)
    for (const text of [result.prompt, result.generationScript, ...result.shots.flatMap(shot => [shot.prompt, shot.firstFramePrompt]), result.keyframes[0].prompt, result.recreation.aiWorkflow]) {
      assert.doesNotMatch(text, /某牌|金色玻璃|香水瓶|瓶盖/)
      assert.match(text, /如图中产品/)
      assert.match(text, /金色裙子/)
      assert.match(text, /暖金色侧光/)
    }
    if (mode === 'real') {
      assert.equal(result.reuseScript, originalPrompt)
      assert.equal(result.recreation.preparation, originalPrompt)
    } else assert.doesNotMatch(result.reuseScript, /某牌|香水瓶/)
  }
})

test('the copyable storyboard stays one short line per shot without repeating image and video prompts', () => {
  const result = localizeAnalysisPrompts(historicalAnalysis())
  const lines = result.generationScript.split('\n')
  assert.equal(lines.length, 3, 'one product-reference rule followed by two short shot lines')
  assert.doesNotMatch(result.generationScript, /静态参考图片|首帧画面：|动作与运镜：/)
  assert.match(lines[1], /镜头 1.*如图中产品.*相机固定/)
  assert.match(lines[2], /镜头 2.*伸手拿起如图中产品.*相机向前缓慢移动/)
})

test('historical appearance variants are removed only next to a known product while scene colors stay intact', () => {
  const value = historicalAnalysis()
  value.result.parameters.push({ key: '品牌', value: '示例牌' })
  value.result.parameters.push({ key: '商品颜色', value: '白色' }, { key: '商品材质', value: '陶瓷' })
  value.result.prompt = '示例牌白色陶瓷杯子在玻璃桌面中央，人物穿白色衣服，窗边金色光线，镜头固定。'
  value.result.shots[0].scene = '示例牌透明玻璃杯子在玻璃桌面上。'
  const result = localizeAnalysisPrompts(value)
  assert.doesNotMatch(result.prompt, /示例牌|白色陶瓷|杯子/)
  assert.doesNotMatch(result.prompt, /如图中产品如图中产品/)
  assert.doesNotMatch(result.generationScript, /示例牌|透明玻璃杯子/)
  assert.match(result.prompt, /玻璃桌面中央/)
  assert.match(result.prompt, /人物穿白色衣服/)
  assert.match(result.prompt, /金色光线/)
  value.result.prompt = 'A white cup beside a window'
  const historical = localizeAnalysisPrompts(value)
  assert.doesNotMatch(historical.prompt, /商品颜色|商品材质|品牌：/)
  assert.match(historical.prompt, /相机固定/)
})
