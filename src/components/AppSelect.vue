<script>
import { Comment, Fragment, Teleport, Text, defineComponent, h, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { Listbox, ListboxButton, ListboxOption, ListboxOptions } from '@headlessui/vue'
import { Check, ChevronDown } from 'lucide-vue-next'

// 原生 <select> 的替代品：模板里照旧写 <option> / <optgroup>，这里读出来渲染成统一样式的下拉菜单。
// 菜单挂到 body 上，弹窗和卡片的 overflow 不会把它裁掉；下方放不下时向上展开。
const text = (children) => {
  if (children == null) return ''
  if (typeof children === 'string' || typeof children === 'number') return String(children)
  if (Array.isArray(children)) return children.map(child => text(child?.children ?? child)).join('')
  return ''
}
const enabled = value => value !== undefined && value !== false && value !== null

function collect(nodes, group, out) {
  for (const node of nodes || []) {
    if (!node || node.type === Comment || node.type === Text) continue
    if (node.type === Fragment) collect(node.children, group, out)
    else if (node.type === 'optgroup') collect(node.children, node.props?.label, out)
    else if (node.type === 'option') {
      const label = text(node.children).trim()
      out.push({ value: node.props && 'value' in node.props ? node.props.value : label, label, disabled: enabled(node.props?.disabled), group })
    }
  }
  return out
}
const same = (a, b) => a === b || (a != null && b != null && a !== '' && b !== '' && String(a) === String(b))

export default defineComponent({
  name: 'AppSelect',
  inheritAttrs: false,
  props: {
    modelValue: { type: null, default: undefined },
    modelModifiers: { type: Object, default: () => ({}) },
    disabled: { type: Boolean, default: false },
    name: { type: String, default: undefined },
    required: { type: Boolean, default: false },
  },
  emits: ['update:modelValue', 'change'],
  setup(props, { attrs, slots, emit }) {
    const root = ref(null)
    const tick = ref(0)
    const reposition = () => { if (root.value?.querySelector('[aria-expanded="true"]')) tick.value++ }
    onMounted(() => { window.addEventListener('scroll', reposition, true); window.addEventListener('resize', reposition) })
    onBeforeUnmount(() => { window.removeEventListener('scroll', reposition, true); window.removeEventListener('resize', reposition) })

    const choose = (value) => {
      const next = props.modelModifiers.number && typeof value === 'string' && value !== '' ? Number(value) : value
      emit('update:modelValue', next)
      nextTick(() => emit('change', next))
    }
    const menuStyle = () => {
      void tick.value
      const button = root.value?.querySelector('.app-select-button')
      if (!button) return {}
      const rect = button.getBoundingClientRect()
      const below = window.innerHeight - rect.bottom - 12
      const above = rect.top - 12
      const up = below < 220 && above > below
      return {
        left: `${Math.max(8, Math.min(rect.left, window.innerWidth - Math.max(rect.width, 180) - 8))}px`,
        minWidth: `${Math.max(rect.width, 180)}px`,
        maxHeight: `${Math.min(320, up ? above : below)}px`,
        ...(up ? { bottom: `${window.innerHeight - rect.top + 4}px` } : { top: `${rect.bottom + 4}px` }),
      }
    }

    return () => {
      const options = collect(slots.default?.(), null, [])
      const value = props.modelValue === undefined ? attrs.value : props.modelValue
      const current = options.find(option => same(option.value, value))
      const { class: klass, style, value: _value, onChange: _onChange, ...rest } = attrs
      const rows = []
      let lastGroup = null
      for (const option of options) {
        // <option value="" disabled>请选择…</option> 只用作占位文字，不出现在菜单里。
        if (option.disabled && (option.value === '' || option.value == null)) continue
        if (option.group && option.group !== lastGroup) rows.push(h('li', { class: 'app-select-group', role: 'presentation' }, option.group))
        lastGroup = option.group
        rows.push(h(ListboxOption, { value: option.value, disabled: option.disabled, as: 'template', key: `${option.group}:${option.value}` }, {
          default: ({ active, selected }) => h('li', { class: ['app-select-option', { active, selected, disabled: option.disabled }] }, [
            h('span', { class: 'app-select-option-label' }, option.label),
            selected ? h(Check, { size: 15, class: 'app-select-check', 'aria-hidden': 'true' }) : null,
          ]),
        }))
      }
      return h(Listbox, { modelValue: current ? current.value : value, disabled: props.disabled, by: same, 'onUpdate:modelValue': choose }, {
        default: ({ open }) => h('div', { ref: root, class: 'app-select' }, [
          h(ListboxButton, { ...rest, class: ['app-select-button', klass], style }, {
            default: () => [
              h('span', { class: ['app-select-value', { placeholder: !current || current.disabled }] }, current?.label || ''),
              h(ChevronDown, { size: 15, class: 'app-select-chevron', 'aria-hidden': 'true' }),
            ],
          }),
          props.required || props.name
            ? h('input', { class: 'app-select-native', tabindex: -1, 'aria-hidden': 'true', name: props.name, required: props.required, value: current && !current.disabled ? String(current.value) : '',
                onInvalid: () => root.value?.querySelector('.app-select-button')?.focus(), onInput: () => {} })
            : null,
          open ? h(Teleport, { to: 'body' }, h(ListboxOptions, { class: 'app-select-menu', style: menuStyle() }, { default: () => rows })) : null,
        ]),
      })
    }
  },
})
</script>

<style>
.app-select { display: contents; }
:where(.app-select-button) { display: inline-flex; align-items: center; gap: 8px; min-height: 36px; padding: 0 10px 0 12px; border: 1px solid var(--color-border-subtle); border-radius: 4px; background-color: var(--color-bg-surface); color: var(--color-primary); font: inherit; font-size: 13px; text-align: left; cursor: pointer; }
.app-select-button { appearance: none; background-image: none !important; }
.app-select-button:hover:not(:disabled) { border-color: var(--color-border-strong); }
.app-select-button:disabled { cursor: not-allowed; opacity: .55; }
.app-select-value { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.app-select-value.placeholder { color: var(--color-text-muted); }
.app-select-chevron { flex: none; margin-left: auto; color: var(--color-text-muted); transition: transform .18s; }
.app-select-button[aria-expanded='true'] .app-select-chevron { transform: rotate(180deg); }
/* 只给表单校验用的隐藏输入框：页面里 `xx input { width: 100% }` 之类的规则不能把它撑开。 */
.app-select-native { position: absolute !important; width: 1px !important; min-width: 0 !important; height: 1px !important; min-height: 0 !important; margin: 0 !important; padding: 0 !important; border: 0 !important; opacity: 0 !important; pointer-events: none; }
.app-select-menu { position: fixed; z-index: 2000; margin: 0; padding: 6px; overflow-y: auto; list-style: none; border: 1px solid var(--color-border-subtle); border-radius: 8px; background: var(--color-bg-surface); box-shadow: var(--shadow-paper-elevated); outline: none; color: var(--color-primary); font-family: var(--font-sans); }
.app-select-group { padding: 8px 10px 4px; font-size: 12px; color: var(--color-text-muted); }
.app-select-option { display: flex; align-items: center; gap: 8px; min-height: 36px; padding: 6px 10px; border-radius: 4px; font-size: 13px; cursor: pointer; }
.app-select-option.active { background: var(--color-bg-subtle); }
.app-select-option.selected { color: var(--color-accent-text); font-weight: 500; }
.app-select-option.disabled { cursor: not-allowed; opacity: .5; }
.app-select-option-label { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.app-select-check { flex: none; }
@media (prefers-reduced-motion: reduce) { .app-select-chevron { transition: none; } }
</style>
