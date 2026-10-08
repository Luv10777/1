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
    assert.match(result.shots[1].prompt, /从右侧伸手拿起杯子/)
    assert.match(result.shots[0].firstFramePrompt, /静态参考图片/)
    assert.match(result.shots[0].firstFramePrompt, /窗户在左侧/)
    assert.match(result.keyframes[0].prompt, /窗户在左侧/)
    assert.deepEqual(value, original)
  }
})

test('existing Chinese prompt content is preserved verbatim across every prompt location', () => {
  const value = historicalAnalysis()
  const videoPrompt = '  生成竖屏 AI 视频，白色杯子在木桌中央，窗边柔光，镜头缓慢向前移动。  '
  const imagePrompt = '竖屏静态图片，白色杯子在木桌中央，窗户在左侧。'
  value.result.prompt = videoPrompt
  value.result.negativePrompt = '避免手指变形和杯子形状变化。'
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
  assert.match(result.shots[1].prompt, /从右侧伸手拿起杯子/)
  assert.match(result.shots[1].firstFramePrompt, /静态参考图片/)
  assert.match(result.keyframes[0].prompt, /窗户在左侧/)
  assert.equal(localizeAnalysisPrompts(null), null)
})
