import { ref } from 'vue'

// 界面版式：新手版只露出对话式的图片 / 视频创作，进阶版就是完整界面。
// 只决定显示哪些入口，不是权限；按账号记在这台浏览器上，默认进阶版，免得老用户一登录发现功能「不见了」。
export const UI_MODES = ['novice', 'pro']
/** 对话页出了新图片或新视频时发出，新手版侧栏的「最近」据此重新读取。 */
export const NOVICE_RECENTS_CHANGED = 'novice-recents-changed'
const STORAGE_PREFIX = 'yifangzhi-ui-mode:'

export function createUiMode(storage) {
  const current = ref('pro')
  let account = ''

  function setAccount(identity) {
    account = identity ? String(identity) : ''
    let saved = null
    try { saved = account ? storage?.getItem(STORAGE_PREFIX + account) : null } catch { /* 存储不可用时按默认显示 */ }
    current.value = UI_MODES.includes(saved) ? saved : 'pro'
  }

  function set(mode) {
    if (!UI_MODES.includes(mode)) return
    current.value = mode
    if (!account) return
    try { storage?.setItem(STORAGE_PREFIX + account, mode) } catch { /* 本次会话照样生效 */ }
  }

  return {
    current,
    get isNovice() { return current.value === 'novice' },
    setAccount,
    set,
  }
}

const browserStorage = (() => {
  try { return typeof window === 'undefined' ? null : window.localStorage } catch { return null }
})()

export const uiMode = createUiMode(browserStorage)
