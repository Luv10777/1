import { ref } from 'vue'

const STORAGE_KEY = 'wuyao-nexus-theme'
const VALID_THEMES = new Set(['light', 'dark'])

function getInitialTheme() {
  if (typeof window === 'undefined') return 'dark'

  try {
    const savedTheme = window.localStorage.getItem(STORAGE_KEY)
    if (VALID_THEMES.has(savedTheme)) return savedTheme
  } catch {
    // Storage can be unavailable in private or restricted browser contexts.
  }

  return window.matchMedia?.('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
}

const current = ref(getInitialTheme())

function apply(themeName = current.value) {
  if (typeof document === 'undefined') return
  document.documentElement.dataset.theme = themeName
  document.documentElement.style.colorScheme = themeName
}

function set(themeName) {
  if (!VALID_THEMES.has(themeName)) return
  current.value = themeName
  apply(themeName)

  try {
    window.localStorage.setItem(STORAGE_KEY, themeName)
  } catch {
    // The selected theme still applies for the current page session.
  }
}

export const theme = {
  current,
  get isLight() {
    return current.value === 'light'
  },
  apply,
  set,
  toggle() {
    set(current.value === 'light' ? 'dark' : 'light')
  },
}
