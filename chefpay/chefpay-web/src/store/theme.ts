import { create } from 'zustand'

const STORAGE_KEY = 'chefpay_web_theme_v1'

type Theme = 'light' | 'dark'

function loadTheme(): Theme {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored === 'light' || stored === 'dark') return stored
  } catch {
    // ignore — fall through to system preference
  }
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

function applyTheme(theme: Theme) {
  document.documentElement.classList.toggle('dark', theme === 'dark')
}

interface ThemeState {
  theme: Theme
  isDark: boolean
  toggleTheme: () => void
  setTheme: (theme: Theme) => void
}

const initial = loadTheme()
applyTheme(initial)

export const useThemeStore = create<ThemeState>((set, get) => ({
  theme: initial,
  isDark: initial === 'dark',
  setTheme: (theme) => {
    applyTheme(theme)
    try {
      localStorage.setItem(STORAGE_KEY, theme)
    } catch {
      // best-effort persistence only
    }
    set({ theme, isDark: theme === 'dark' })
  },
  toggleTheme: () => get().setTheme(get().theme === 'dark' ? 'light' : 'dark'),
}))
