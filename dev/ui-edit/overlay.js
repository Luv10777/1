// 页面编辑模式浮层（仅开发环境注入，见 vitePlugin.js）。
// 打开后：悬停框出区块，点击选中；可改文字、隐藏、删除、写说明。
// 改动只作用于当前页面，同时记录到 .claude/ui-edits.json，由 Claude 同步回 .vue 源码。
const ENDPOINT = '/__ui-edits'
const MARK = 'data-ui-edit'

let on = false
let hovered = null
let selected = null
let editing = null
let edits = []
const undoStack = []

const css = `
[${MARK}]{font:12px/1.5 'Yifangzhi Sans','PingFang SC','Microsoft YaHei',sans-serif;box-sizing:border-box;color:#2c3e50}
[${MARK}] *{box-sizing:border-box}
.uie-toggle{position:fixed;right:16px;bottom:88px;z-index:2147483001;height:36px;padding:0 14px;border:1px solid #2c3e50;border-radius:4px;background:#fff;color:#2c3e50;font-weight:500;cursor:pointer;box-shadow:0 8px 24px rgba(44,62,80,.12)}
.uie-toggle.is-on{background:#c84a3d;border-color:#c84a3d;color:#fff}
.uie-box{position:fixed;z-index:2147483000;pointer-events:none;display:none}
.uie-hover{border:1px dashed #c84a3d;background:rgba(200,74,61,.06)}
.uie-selected{border:2px solid #c84a3d}
.uie-tag{position:absolute;left:-1px;bottom:100%;padding:1px 6px;background:#c84a3d;color:#fff;font:11px/1.6 'JetBrains Mono',monospace;white-space:nowrap;border-radius:2px 2px 0 0}
.uie-panel{position:fixed;right:16px;bottom:136px;z-index:2147483001;width:320px;max-height:70vh;overflow:auto;padding:14px;border:1px solid #e6e1d8;border-radius:8px;background:#fff;box-shadow:0 8px 24px rgba(44,62,80,.12);display:none}
.uie-panel h4{margin:0 0 4px;font-size:13px}
.uie-src{margin:0 0 10px;font:11px/1.5 'JetBrains Mono',monospace;color:#58696e;word-break:break-all}
.uie-label{margin:10px 0 6px;font-size:11px;color:#58696e;letter-spacing:.08em}
.uie-chips{display:flex;flex-wrap:wrap;gap:4px}
.uie-chip{padding:2px 6px;border:1px solid #e6e1d8;border-radius:2px;background:#f8f6f3;font:11px 'JetBrains Mono',monospace;cursor:pointer;color:#2c3e50}
.uie-chip:hover{border-color:#c84a3d;color:#b43f33}
.uie-actions{display:grid;grid-template-columns:1fr 1fr;gap:6px;margin-top:12px}
.uie-btn{height:32px;border:1px solid #2c3e50;border-radius:4px;background:#fff;color:#2c3e50;cursor:pointer;font:inherit}
.uie-btn:hover{background:#efece6}
.uie-btn:disabled{opacity:.45;cursor:not-allowed}
.uie-btn.danger{border-color:#b33d32;color:#b33d32}
.uie-btn.primary{border-color:#c84a3d;background:#c84a3d;color:#fff}
.uie-note{width:100%;min-height:64px;margin-top:8px;padding:8px;border:1px solid #e6e1d8;border-radius:4px;font:inherit;resize:vertical}
.uie-log{margin-top:14px;padding-top:10px;border-top:1px solid #e6e1d8}
.uie-log ol{margin:6px 0;padding-left:18px;color:#58696e}
.uie-log li{margin:2px 0}
.uie-row{display:flex;gap:6px;margin-top:6px}
.uie-hint{margin:0;color:#58696e}
`

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag)
  node.setAttribute(MARK, '')
  for (const [k, v] of Object.entries(attrs)) {
    if (k.startsWith('on')) node.addEventListener(k.slice(2), v)
    else node.setAttribute(k, v)
  }
  node.append(...children)
  return node
}

const style = el('style', {}, css)
const toggle = el('button', { class: 'uie-toggle', type: 'button', onclick: () => setOn(!on) }, '编辑页面')
const hoverBox = el('div', { class: 'uie-box uie-hover' })
const selectBox = el('div', { class: 'uie-box uie-selected' }, el('span', { class: 'uie-tag' }))
const panel = el('div', { class: 'uie-panel' })

const ACTION_NAMES = { text: '改文字', hide: '隐藏', delete: '删除', note: '说明' }

function describe(node) {
  const cls = [...node.classList].slice(0, 2).map((c) => '.' + c).join('')
  return node.tagName.toLowerCase() + cls
}

function src(node) {
  return node.getAttribute('data-src') || ''
}

function snippet(node) {
  return (node.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 80)
}

function place(box, node) {
  if (!node || !node.isConnected) {
    box.style.display = 'none'
    return
  }
  const r = node.getBoundingClientRect()
  Object.assign(box.style, { display: 'block', left: r.left + 'px', top: r.top + 'px', width: r.width + 'px', height: r.height + 'px' })
}

function target(event) {
  const node = event.target instanceof Element ? event.target : null
  if (!node || node.closest(`[${MARK}]`)) return null
  return node.closest('[data-src]')
}

async function save() {
  try {
    await fetch(ENDPOINT, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(edits) })
  } catch (error) {
    console.warn('[ui-edit] 保存改动失败', error)
  }
}

function record(node, action, extra, revert) {
  const entry = { action, src: src(node), element: describe(node), text: snippet(node), page: location.pathname, at: new Date().toISOString(), ...extra }
  edits.push(entry)
  undoStack.push({ entry, revert })
  save()
  render()
}

function undo() {
  const last = undoStack.pop()
  if (!last) return
  last.revert()
  edits = edits.filter((e) => e !== last.entry)
  save()
  render()
}

function clearAll() {
  while (undoStack.length) undoStack.pop().revert()
  edits = []
  save()
  render()
}

function hideNode(node, action) {
  const before = node.style.getPropertyValue('display')
  const priority = node.style.getPropertyPriority('display')
  node.style.setProperty('display', 'none', 'important')
  record(node, action, {}, () => node.style.setProperty('display', before, priority))
  select(null)
}

function editText(node) {
  const before = node.textContent
  editing = node
  node.contentEditable = 'true'
  node.focus()
  document.getSelection()?.selectAllChildren(node)
  const finish = () => {
    node.removeEventListener('blur', finish)
    node.removeEventListener('keydown', onKey)
    node.contentEditable = 'false'
    editing = null
    const after = node.textContent
    if (after !== before) record(node, 'text', { before, after }, () => { node.textContent = before })
    else render()
  }
  const onKey = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); node.blur() }
    if (e.key === 'Escape') { node.textContent = before; node.blur() }
  }
  node.addEventListener('blur', finish)
  node.addEventListener('keydown', onKey)
}

function noteFor(node) {
  const area = el('textarea', { class: 'uie-note', placeholder: '想怎么改？例如：标题改成宋体 28px、这块挪到右侧、按钮改成次级样式' })
  const ok = el('button', { class: 'uie-btn primary', type: 'button', onclick: () => {
    const note = area.value.trim()
    if (note) record(node, 'note', { note }, () => {})
  } }, '记下')
  panel.querySelector('.uie-actions')?.after(area, el('div', { class: 'uie-row' }, ok))
  area.focus()
}

function render() {
  panel.replaceChildren()
  if (selected) {
    const isLeaf = !selected.querySelector('*') && snippet(selected)
    const ancestors = []
    for (let p = selected.parentElement?.closest('[data-src]'); p && ancestors.length < 6; p = p.parentElement?.closest('[data-src]')) ancestors.push(p)
    const children = [...selected.querySelectorAll('[data-src]')].filter((c) => c.parentElement?.closest('[data-src]') === selected).slice(0, 8)
    const chips = (list) => el('div', { class: 'uie-chips' }, ...list.map((n) => el('button', { class: 'uie-chip', type: 'button', title: src(n), onclick: () => select(n), onmouseenter: () => place(hoverBox, n) }, describe(n))))
    panel.append(
      el('h4', {}, describe(selected)),
      el('p', { class: 'uie-src' }, src(selected)),
      ancestors.length ? el('p', { class: 'uie-label' }, '外层（由近到远）') : '',
      ancestors.length ? chips(ancestors) : '',
      children.length ? el('p', { class: 'uie-label' }, '内层') : '',
      children.length ? chips(children) : '',
      el('div', { class: 'uie-actions' },
        Object.assign(el('button', { class: 'uie-btn', type: 'button', title: isLeaf ? '' : '只能改最内层的文字，请先选中内层', onclick: () => editText(selected) }, '改文字'), { disabled: !isLeaf }),
        el('button', { class: 'uie-btn', type: 'button', onclick: () => noteFor(selected) }, '写说明'),
        el('button', { class: 'uie-btn', type: 'button', onclick: () => hideNode(selected, 'hide') }, '隐藏'),
        el('button', { class: 'uie-btn danger', type: 'button', onclick: () => hideNode(selected, 'delete') }, '删除'),
      ),
    )
  } else {
    panel.append(el('p', { class: 'uie-hint' }, '点击页面上的区块选中它。Esc 取消选中，再按一次退出编辑模式。'))
  }
  const log = el('div', { class: 'uie-log' }, el('strong', {}, `已记录 ${edits.length} 处改动`))
  if (edits.length) {
    log.append(
      el('ol', {}, ...edits.slice(-6).map((e) => el('li', {}, `${ACTION_NAMES[e.action] || e.action} · ${e.element}${e.after != null ? ' → ' + e.after.slice(0, 24) : ''}${e.note ? ' · ' + e.note.slice(0, 24) : ''}`))),
      el('div', { class: 'uie-row' },
        Object.assign(el('button', { class: 'uie-btn', type: 'button', onclick: undo }, '撤销上一步'), { disabled: !undoStack.length }),
        el('button', { class: 'uie-btn danger', type: 'button', onclick: clearAll }, '清空全部'),
      ),
      el('p', { class: 'uie-hint', style: 'margin-top:8px' }, '改完后告诉 Claude「同步页面改动」。刷新页面会还原显示，但记录会保留。'),
    )
  }
  panel.append(log)
}

function select(node) {
  selected = node
  selectBox.querySelector('.uie-tag').textContent = node ? `${describe(node)}  ${src(node).split('/').pop()}` : ''
  place(selectBox, node)
  render()
}

function onMove(e) {
  if (editing) return
  hovered = target(e)
  place(hoverBox, hovered)
}

function onClick(e) {
  if (editing && editing.contains(e.target)) return
  const node = target(e)
  if (!node) return
  e.preventDefault()
  e.stopPropagation()
  select(node)
}

function onKey(e) {
  if (e.key !== 'Escape' || editing) return
  if (selected) select(null)
  else setOn(false)
}

function reposition() {
  place(hoverBox, hovered)
  place(selectBox, selected)
}

function setOn(value) {
  on = value
  toggle.classList.toggle('is-on', on)
  toggle.textContent = on ? '退出编辑' : '编辑页面'
  panel.style.display = on ? 'block' : 'none'
  const method = on ? 'addEventListener' : 'removeEventListener'
  document[method]('mousemove', onMove, true)
  document[method]('click', onClick, true)
  document[method]('keydown', onKey, true)
  window[method]('scroll', reposition, true)
  window[method]('resize', reposition)
  if (!on) {
    hovered = null
    select(null)
    place(hoverBox, null)
  }
  render()
}

document.head.append(style)
document.body.append(hoverBox, selectBox, panel, toggle)
fetch(ENDPOINT).then((r) => (r.ok ? r.json() : [])).then((saved) => { edits = Array.isArray(saved) ? saved : []; render() }).catch(() => {})
