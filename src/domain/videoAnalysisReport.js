import { analysisAudioSummary, analysisReportMode, analysisRecreationSteps, analysisShotDetails, analysisTime, PRODUCT_REFERENCE_GUIDANCE } from './videoAnalysis.js'
import { customizeAnalysisRecreation } from './videoAnalysisCustomization.js'

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character])
}

const paragraph = value => `<p>${escapeHtml(value)}</p>`
const section = (title, content) => `<section><h2>${escapeHtml(title)}</h2>${content}</section>`
const items = values => values?.length ? `<ul>${values.map(value => `<li>${escapeHtml(value)}</li>`).join('')}</ul>` : ''
const facts = values => `<dl>${values.map(({ label, text }) => `<div><dt>${escapeHtml(label)}</dt><dd>${escapeHtml(text)}</dd></div>`).join('')}</dl>`
const timeRange = value => `${analysisTime(value.start)}–${analysisTime(value.end)}`

function promptBlock(title, value) {
  if (!value) return ''
  return `<div class="prompt"><div class="prompt-head"><h4>${escapeHtml(title)}</h4><button type="button" class="copy-button" data-copy>复制</button></div>${paragraph(value)}</div>`
}

function shotReport(shots, mode, route) {
  return (shots || []).map((shot, index) => `<article class="shot">
    <h3>镜头 ${index + 1} <small>${timeRange(shot)} · ${(Number(shot.end) - Number(shot.start)).toFixed(1)} 秒</small></h3>
    <h4>原视频画面</h4>${paragraph(shot.scene)}
    ${facts([...analysisShotDetails(shot, mode, route), { label: '画面情绪', text: shot.emotion }, { label: '镜头节奏', text: shot.pacing }])}
  </article>`).join('')
}

function planReport(result, mode, route) {
  return analysisRecreationSteps(result, mode, route).map(step => `<article><h3>${escapeHtml(step.title)}</h3>${paragraph(step.text)}</article>`).join('')
}

export function buildVideoAnalysisReport(analysis, edits = {}) {
  const result = customizeAnalysisRecreation(analysis, edits) || {}
  const mode = analysisReportMode(analysis)
  const real = mode === 'real'
  const title = real ? '实拍视频拆解与复刻报告' : 'AI 视频提示词与分镜复刻报告'
  const audio = result.audioAnalyzed === true && result.audio?.status === 'ANALYZED' ? result.audio : null
  const duration = Math.max(0, Number(analysis.durationMs || 0) / 1000)
  const generatedAt = analysis.createdAt && !Number.isNaN(Date.parse(analysis.createdAt))
    ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'medium', timeStyle: 'short' }).format(new Date(analysis.createdAt)) : ''
  const sections = []
  let sectionNumber = 0
  const addSection = (title, content) => sections.push(section(`${++sectionNumber}. ${title}`, content))
  if (!real) {
    addSection('可直接复制的复刻提示词', paragraph(PRODUCT_REFERENCE_GUIDANCE) + promptBlock('整体中文视频提示词', result.prompt)
      + `<details><summary>生成时需要避免的问题</summary>${promptBlock('避免出现', result.negativePrompt)}</details>`)
    addSection('可直接复制的分镜脚本', promptBlock('按镜头时间顺序生成', result.generationScript))
  }
  if (result.editableContent?.length) addSection('商家文案修改对照', result.editableContent.map(item => `<article><h3>${escapeHtml(item.label)} <small>${escapeHtml(item.timeLabel)}</small></h3>${facts([
    { label: '原视频内容', text: item.original }, { label: '商家修改内容', text: item.replacement || '不使用此项' },
  ])}</article>`).join(''))
  const primarySectionCount = sections.length
  if (!real) addSection('制作参考', planReport(result, mode, 'ai'))
  addSection('原视频观察与复刻重点', paragraph(result.summary || '暂无视频概要。') + facts([
      { label: '分析类型', text: real ? '实拍视频：照着拍，或用 AI 重做' : 'AI 视频：提示词复刻与逐镜生成' },
      { label: '视频时长', text: `${duration.toFixed(1)} 秒` },
      { label: '画面尺寸', text: analysis.width && analysis.height ? `${analysis.width} × ${analysis.height}` : '未记录' },
      { label: '拆解镜头', text: `${result.shots?.length || 0} 个` },
      { label: '声音分析', text: analysisAudioSummary(result) },
      ...(analysis.reverseNeed ? [{ label: '本次关注点', text: analysis.reverseNeed }] : []),
    ]))
  if (result.parameters?.length) addSection('画面特点与复刻重点', facts(result.parameters.map(item => ({ label: item.key, text: item.value }))))
  if (!result.recreation) sections.push('<p class="note">这是一份历史分析，保留了当时的拆解内容。重新分析可获得更具体的逐镜头操作步骤及完整复刻方案。</p>')
  if (result.promptsLocalized) sections.push('<p class="note">中文提示词根据本记录已有的画面拆解整理，可直接复制使用；重新分析可获得更完整的分镜提示词。</p>')
  if (real) {
    addSection('方案一：照着实拍', planReport(result, mode, 'filming')
      + promptBlock('按顺序执行的拍摄脚本', result.reuseScript) + shotReport(result.shots, mode, 'filming'))
    addSection('方案二：用 AI 重做', paragraph(PRODUCT_REFERENCE_GUIDANCE) + promptBlock('整体视频生成提示词', result.prompt)
      + promptBlock('可直接复制的分镜脚本', result.generationScript)
      + promptBlock('生成时需要避免的问题', result.negativePrompt) + planReport(result, mode, 'ai'))
  }
  if (result.keyframes?.length) addSection('关键画面参考', result.keyframes.map(frame => `<article><h3>${analysisTime(frame.seconds)} · ${escapeHtml(frame.title)}</h3>
    ${paragraph(frame.description || frame.title)}</article>`).join(''))
  let sound = paragraph(analysisAudioSummary(result))
  if (audio) {
    sound += facts([{ label: '人声与表达', text: audio.speech }, { label: '配乐与节奏', text: audio.music }, { label: '环境声音', text: audio.ambience }])
    sound += `<h3>原视频口播</h3>${audio.transcript?.length ? audio.transcript.map(segment => `<p><strong>${timeRange(segment)}</strong> ${escapeHtml(segment.text)}</p>`).join('') : paragraph('未识别到可转录的人声。')}`
    if (audio.effects?.length) sound += `<h3>音效出现的时刻</h3>${audio.effects.map(effect => `<p><strong>${timeRange(effect)}</strong> ${escapeHtml(effect.description)}</p>`).join('')}`
    sound += items(audio.limitations)
  }
  addSection('口播、配乐与音效', sound)
  addSection('值得保留的亮点与改进建议', `<h3>值得保留的亮点</h3>${items(result.highlights) || paragraph('本次未单列亮点。')}
    <h3>复刻与改进建议</h3>${items(result.suggestions) || paragraph('本次未单列改进建议。')}`)
  addSection('观察范围与制作建议', paragraph(`报告根据 ${analysis.frames?.length || 0} 张抽样画面整理。镜头切换时刻、相机移动和动作衔接是根据画面变化估计的。${audio ? '原口播来自声音分析，声音时间戳也为估计。' : '本次没有完成声音分析，声音安排仅为制作建议。'}复刻脚本和生成提示词是建议做法，可结合自己的素材调整。`)
    + items(result.limitations))

  return `<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${escapeHtml(analysis.name || '视频')} · ${title}</title>
<style>
:root{--report-ink:#141517;--report-muted:#6b6e76;--report-paper:#fff;--report-wash:#f4f4f5;--report-rule:#dedee1;--report-accent:#e64615}
*{box-sizing:border-box}body{margin:0;background:var(--report-wash);font-family:Roboto,"Microsoft YaHei","PingFang SC",Arial,sans-serif;font-size:16px;line-height:1.8;color:var(--report-ink)}
main{max-width:720px;margin:32px auto;padding:40px;background:var(--report-paper)}header{padding-bottom:24px;border-bottom:1px solid var(--report-rule)}
.brand{margin:0;color:var(--report-accent);font-size:13px;font-weight:700}h1{font-size:30px;line-height:1.35;margin:12px 0}h2{font-size:23px;line-height:1.5;margin:0 0 16px}h3{font-size:18px;line-height:1.6;margin:20px 0 8px}h4{font-size:15px;margin:12px 0 4px}
p{margin:8px 0;white-space:pre-wrap;overflow-wrap:anywhere}small,.meta,.note{font-size:13px;color:var(--report-muted)}h3 small{display:block;font-weight:400}section{margin-top:32px}article{margin-top:16px}
dl{margin:16px 0}dl>div{display:grid;grid-template-columns:148px minmax(0,1fr);gap:16px;padding:12px 0;border-bottom:1px solid var(--report-rule)}dt{color:var(--report-muted);font-size:14px}dd{margin:0;white-space:pre-wrap;overflow-wrap:anywhere}
.prompt{margin:16px 0;padding:16px;background:var(--report-wash);border-radius:8px}.prompt h4{margin-top:0}.shot{padding:8px 0 24px;border-bottom:1px solid var(--report-rule)}ul{padding-left:24px}li{margin:8px 0;white-space:pre-wrap;overflow-wrap:anywhere}
.prompt-head{display:flex;align-items:center;justify-content:space-between;gap:12px}.prompt-head h4{margin:0}.copy-button{flex-shrink:0}
.reference{margin-top:32px;border-top:1px solid var(--report-rule);padding-top:16px}.reference>summary{cursor:pointer;color:var(--report-muted)}
.toolbar{display:flex;justify-content:flex-end;margin:16px 0}button{font:inherit;font-size:14px;border:1px solid var(--report-rule);background:var(--report-paper);border-radius:6px;padding:8px 16px;cursor:pointer}button:focus-visible{outline:2px solid var(--report-accent);outline-offset:3px}
footer{margin-top:32px;padding-top:16px;border-top:1px solid var(--report-rule);font-size:13px;color:var(--report-muted)}
@media(max-width:640px){main{margin:0;padding:24px 20px}h1{font-size:26px}dl>div{grid-template-columns:1fr;gap:4px}.toolbar{justify-content:flex-start}}
@page{size:A4;margin:18mm}@media print{body{background:#fff;font-size:11pt}main{max-width:none;margin:0;padding:0}.toolbar,.copy-button{display:none}h2,h3,h4{break-after:avoid}dl>div,.prompt,li{break-inside:avoid}section{margin-top:24px}.prompt{border:1px solid #dedee1;background:#fff}}
</style></head><body><main><header><p class="brand">梧曜 AI · 视频反推</p><h1>${title}</h1>${paragraph(analysis.name || '未命名视频')}
${generatedAt ? `<p class="meta">分析时间：${escapeHtml(generatedAt)}（北京时间）</p>` : ''}</header>
<div class="toolbar"><button type="button" onclick="window.print()">打印 / 保存为 PDF</button></div>
${real ? sections.join('\n') : sections.slice(0, primarySectionCount).join('\n') + '<details class="reference"><summary>查看画面拆解与声音参考</summary>' + sections.slice(primarySectionCount).join('\n') + '</details>'}<footer>上传自己的商品参考图，复制整体提示词和分镜脚本到视频生成工具中。具体生成效果取决于工具和参考素材。</footer>
</main><script>
document.addEventListener('click', async function(event) {
  var button = event.target.closest('button[data-copy]');
  if (!button) return;
  var paragraph = button.closest('.prompt').querySelector('p');
  try {
    if (!navigator.clipboard || !window.isSecureContext) throw new Error('fallback');
    await navigator.clipboard.writeText(paragraph.textContent);
    button.textContent = '已复制';
  } catch (error) {
    var field = document.createElement('textarea');
    field.value = paragraph.textContent;
    field.style.position = 'fixed'; field.style.opacity = '0';
    document.body.appendChild(field); field.select();
    var copied = false;
    try { copied = document.execCommand('copy'); } catch (error) { /* Select the visible text below. */ }
    field.remove();
    if (copied) button.textContent = '已复制';
    else {
      var range = document.createRange(); range.selectNodeContents(paragraph);
      var selection = window.getSelection(); selection.removeAllRanges(); selection.addRange(range);
      button.textContent = '已选中，请复制';
    }
  }
});
</script></body></html>`
}
